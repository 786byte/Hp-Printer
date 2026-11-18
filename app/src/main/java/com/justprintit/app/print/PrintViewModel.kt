package com.justprintit.app.print

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.justprintit.app.JustPrintItApp
import com.justprintit.app.data.Paper
import com.justprintit.core.layout.Sheet
import com.justprintit.core.layout.SheetPlan
import com.justprintit.core.layout.SidesMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class PrintUiState(
    val doc: PrintDocument? = null,
    val loading: Boolean = false,
    val options: PrintOptions = PrintOptions(),
    val rangeError: String? = null,
    /** Sheets of paper that will go through the printer (incl. copies). */
    val sheetCount: Int = 0,
    val previewIndex: Int = 0,
    val previewSheets: List<Sheet> = emptyList(),
    val preview: Bitmap? = null,
    val error: String? = null,
)

class PrintViewModel(app: Application) : AndroidViewModel(app) {
    private val container = app as JustPrintItApp
    private val queue = container.queue
    private val _state = MutableStateFlow(PrintUiState(options = defaults()))
    val state: StateFlow<PrintUiState> = _state.asStateFlow()
    private var previewJob: Job? = null
    private val renderLock = Mutex()

    private fun defaults(): PrintOptions {
        val s = container.settings.current
        return PrintOptions(paper = s.defaultPaper, quality = s.defaultQuality, mono = s.defaultMono)
    }

    fun open(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val doc = withContext(Dispatchers.IO) { PrintDocument.import(getApplication(), uris) }
                _state.value.doc?.close()
                // Sensible starting point for photos: fit on photo paper if the printer is set to it.
                val base = defaults().let { d ->
                    if (doc.isPhotos) d.copy(quality = 5, scale = ScaleMode.FIT) else d
                }
                _state.update { PrintUiState(doc = doc, options = base) }
                recompute()
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message ?: "Couldn't open that file") }
            }
        }
    }

    fun openTestPage() {
        viewModelScope.launch {
            val file = withContext(Dispatchers.IO) { TestPages.duplexTest(getApplication()) }
            val doc = withContext(Dispatchers.IO) { PrintDocument.open(listOf(file), "application/pdf", "Double-sided test") }
            _state.value.doc?.close()
            _state.update { PrintUiState(doc = doc, options = defaults().copy(sides = SidesMode.GUIDED_DUPLEX, mono = true, quality = 3)) }
            recompute()
        }
    }

    fun close() {
        _state.value.doc?.close()
        _state.value = PrintUiState(options = defaults())
    }

    fun update(transform: (PrintOptions) -> PrintOptions) {
        _state.update { it.copy(options = transform(it.options)) }
        recompute()
    }

    fun showPreview(index: Int) {
        _state.update { it.copy(previewIndex = index.coerceIn(0, (it.previewSheets.size - 1).coerceAtLeast(0))) }
        renderPreview()
    }

    private fun recompute() {
        val s = _state.value
        val doc = s.doc ?: return
        try {
            val all = queue.planSheets(doc, s.options)
            val shown: List<Sheet>
            val paperSheets: Int
            if (s.options.sides == SidesMode.GUIDED_DUPLEX) {
                val d = SheetPlan.duplex(all, queue.duplexSettings())
                // Preview in reading order (front, back, front…) — what you'll hold in your hand.
                shown = all
                paperSheets = d.fronts.size
            } else {
                shown = SheetPlan.select(all, s.options.sides)
                paperSheets = shown.size * s.options.copies
            }
            _state.update {
                it.copy(rangeError = null, previewSheets = shown, sheetCount = paperSheets, loading = false,
                    previewIndex = it.previewIndex.coerceIn(0, (shown.size - 1).coerceAtLeast(0)))
            }
            renderPreview()
        } catch (e: Exception) {
            _state.update { it.copy(rangeError = e.message, sheetCount = 0, loading = false) }
        }
    }

    private fun renderPreview() {
        previewJob?.cancel()
        previewJob = viewModelScope.launch {
            delay(120) // debounce slider drags
            val s = _state.value
            val doc = s.doc ?: return@launch
            val sheet = s.previewSheets.getOrNull(s.previewIndex) ?: return@launch
            val info = container.printer.status.value.info
            val margin = if (s.options.borderless && info?.supportsBorderless == true) 0.0 else (info?.minMargin ?: 300) / 100.0
            val bmp = withContext(Dispatchers.Default) {
                renderLock.withLock {
                    runCatching {
                        val r = SheetRenderer.render(doc, sheet, s.options, Paper.of(s.options.paper), 60, margin)
                        if (s.options.mono) r.toGray() else r
                    }.getOrNull()
                }
            }
            _state.update { it.copy(preview = bmp) }
        }
    }

    private fun Bitmap.toGray(): Bitmap {
        val px = IntArray(width * height)
        getPixels(px, 0, width, 0, 0, width, height)
        for (i in px.indices) {
            val l = com.justprintit.core.raster.PwgRasterWriter.luma(px[i])
            px[i] = (0xFF shl 24) or (l shl 16) or (l shl 8) or l
        }
        setPixels(px, 0, width, 0, 0, width, height)
        return this
    }

    fun print(): Boolean {
        val s = _state.value
        val doc = s.doc ?: return false
        if (s.rangeError != null) return false
        return try {
            queue.start(PrintRequest(doc.files, doc.mime, doc.name, s.options))
            container.settings.update { it.copy(defaultPaper = s.options.paper) }
            true
        } catch (e: Exception) {
            _state.update { it.copy(error = e.message) }
            false
        }
    }

    fun clearError() = _state.update { it.copy(error = null) }

    override fun onCleared() {
        // The document file stays on disk for the queue / history; only the renderer is closed.
        _state.value.doc?.close()
    }
}
