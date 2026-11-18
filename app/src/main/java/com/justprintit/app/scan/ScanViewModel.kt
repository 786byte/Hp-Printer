package com.justprintit.app.scan

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.justprintit.app.JustPrintItApp
import com.justprintit.app.print.PrintOptions
import com.justprintit.app.print.PrintRequest
import com.justprintit.core.pdf.JpegPdfWriter
import com.justprintit.core.scan.EsclClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ScannedPage(val file: File, val thumbnail: Bitmap)

data class ScanUiState(
    val pages: List<ScannedPage> = emptyList(),
    val scanning: Boolean = false,
    val message: String? = null,
    val error: String? = null,
    val region: EsclClient.Region = EsclClient.Region.A4,
    val dpi: Int = 300,
    val color: Boolean = true,
)

class ScanViewModel(app: Application) : AndroidViewModel(app) {
    private val container = app as JustPrintItApp
    private val _state = MutableStateFlow(
        ScanUiState(dpi = container.settings.current.scanDpi, color = container.settings.current.scanColor,
            region = if (container.settings.current.defaultPaper.startsWith("na_letter")) EsclClient.Region.LETTER else EsclClient.Region.A4),
    )
    val state: StateFlow<ScanUiState> = _state.asStateFlow()
    private var scanJob: Job? = null
    private val dir = File(app.cacheDir, "scans").apply { mkdirs() }

    fun setRegion(r: EsclClient.Region) = _state.update { it.copy(region = r) }
    fun setDpi(d: Int) { _state.update { it.copy(dpi = d) }; container.settings.update { it.copy(scanDpi = d) } }
    fun setColor(c: Boolean) { _state.update { it.copy(color = c) }; container.settings.update { it.copy(scanColor = c) } }
    fun clearMessage() = _state.update { it.copy(message = null, error = null) }

    /** Scans one page from the glass and adds it to the current set. */
    fun scanPage(then: ((File) -> Unit)? = null) {
        if (_state.value.scanning) return
        scanJob = viewModelScope.launch {
            _state.update { it.copy(scanning = true, error = null, message = null) }
            try {
                val s = _state.value
                val file = withContext(Dispatchers.IO) {
                    val scanner = container.printer.scanner()
                    val caps = runCatching { scanner.capabilities() }.getOrNull()
                    val region = caps?.let {
                        s.region.copy(widthPx300 = s.region.widthPx300.coerceAtMost(it.maxWidth),
                            heightPx300 = s.region.heightPx300.coerceAtMost(it.maxHeight))
                    } ?: s.region
                    val dpi = caps?.resolutions?.takeIf { it.isNotEmpty() }?.let { r -> r.minBy { kotlin.math.abs(it - s.dpi) } } ?: s.dpi
                    val mode = if (s.color) EsclClient.ColorMode.COLOR else EsclClient.ColorMode.GRAY
                    val jobUrl = scanner.startJob(EsclClient.settingsXml(region, dpi, mode))
                    val bytes = try { scanner.nextDocument(jobUrl) } catch (e: Exception) { scanner.cancel(jobUrl); throw e }
                    File(dir, "scan_${System.currentTimeMillis()}_${dpi}dpi.jpg").apply { writeBytes(bytes) }
                }
                val thumb = withContext(Dispatchers.IO) {
                    BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = 8 })
                } ?: throw IllegalStateException("The scanner sent an image the phone can't read")
                _state.update { it.copy(pages = it.pages + ScannedPage(file, thumb)) }
                then?.invoke(file)
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: "Scan failed") }
            } finally {
                _state.update { it.copy(scanning = false) }
            }
        }
    }

    fun removePage(page: ScannedPage) = _state.update { s ->
        page.file.delete()
        s.copy(pages = s.pages - page)
    }

    fun clear() = _state.update { s ->
        s.pages.forEach { it.file.delete() }
        s.copy(pages = emptyList())
    }

    private fun dpiOf(f: File) = Regex("_(\\d+)dpi").find(f.name)?.groupValues?.get(1)?.toIntOrNull() ?: 300

    fun suggestedName(ext: String): String =
        "Scan ${SimpleDateFormat("yyyy-MM-dd HHmm", Locale.getDefault()).format(Date())}.$ext"

    /** Writes all pages as one PDF to a user-chosen location. */
    fun savePdf(uri: Uri) = viewModelScope.launch {
        val pages = _state.value.pages
        if (pages.isEmpty()) return@launch
        runCatching {
            withContext(Dispatchers.IO) {
                getApplication<Application>().contentResolver.openOutputStream(uri)!!.use { out ->
                    JpegPdfWriter.write(pages.map { it.file.readBytes() }, dpiOf(pages[0].file), out)
                }
            }
        }.onSuccess { _state.update { it.copy(message = "Saved PDF (${pages.size} page${if (pages.size == 1) "" else "s"})") } }
            .onFailure { e -> _state.update { it.copy(error = "Couldn't save: ${e.message}") } }
    }

    /** Writes each page as a JPG into a user-chosen folder. */
    fun saveJpgs(tree: Uri) = viewModelScope.launch {
        val pages = _state.value.pages
        runCatching {
            withContext(Dispatchers.IO) {
                val folder = DocumentFile.fromTreeUri(getApplication(), tree) ?: error("Folder not available")
                val base = suggestedName("jpg").removeSuffix(".jpg")
                pages.forEachIndexed { i, p ->
                    val name = if (pages.size == 1) base else "$base (${i + 1})"
                    val f = folder.createFile("image/jpeg", name) ?: error("Can't create $name")
                    getApplication<Application>().contentResolver.openOutputStream(f.uri)!!.use { out -> p.file.inputStream().use { it.copyTo(out) } }
                }
            }
        }.onSuccess { _state.update { it.copy(message = "Saved ${pages.size} JPG${if (pages.size == 1) "" else "s"}") } }
            .onFailure { e -> _state.update { it.copy(error = "Couldn't save: ${e.message}") } }
    }

    fun saveSingleJpg(uri: Uri) = viewModelScope.launch {
        val page = _state.value.pages.firstOrNull() ?: return@launch
        runCatching {
            withContext(Dispatchers.IO) {
                getApplication<Application>().contentResolver.openOutputStream(uri)!!.use { out -> page.file.inputStream().use { it.copyTo(out) } }
            }
        }.onSuccess { _state.update { it.copy(message = "Saved JPG") } }
            .onFailure { e -> _state.update { it.copy(error = "Couldn't save: ${e.message}") } }
    }

    /** Builds a PDF in the cache for the share sheet. */
    suspend fun pdfForSharing(): File? = withContext(Dispatchers.IO) {
        val pages = _state.value.pages
        if (pages.isEmpty()) return@withContext null
        File(dir, suggestedName("pdf")).apply {
            outputStream().use { JpegPdfWriter.write(pages.map { it.file.readBytes() }, dpiOf(pages[0].file), it) }
        }
    }

    /** Photocopy: print the scanned pages at actual size. Goes through the same queue (and the same never-auto-retry rules). */
    fun printScans(copies: Int, mono: Boolean) {
        val pages = _state.value.pages
        if (pages.isEmpty()) return
        val jobsDir = File(getApplication<Application>().filesDir, "jobs").apply { mkdirs() }
        val files = pages.map { p -> File(jobsDir, p.file.name).also { p.file.copyTo(it, overwrite = true) } }
        val s = container.settings.current
        val paper = if (_state.value.region == EsclClient.Region.LETTER) "na_letter_8.5x11in" else s.defaultPaper
        try {
            container.queue.start(PrintRequest(files, "image/jpeg", "Copy", PrintOptions(copies = copies, mono = mono, paper = paper, quality = s.defaultQuality)))
        } catch (e: Exception) {
            _state.update { it.copy(error = e.message) }
        }
    }

    /** Scan one page and print it straight away. */
    fun copyNow(copies: Int, mono: Boolean) {
        clear()
        scanPage { printScans(copies, mono) }
    }

    fun cancelScan() { scanJob?.cancel() }
}
