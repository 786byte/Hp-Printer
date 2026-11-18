package com.justprintit.app.print

import android.content.Context
import android.graphics.Bitmap
import android.net.wifi.WifiManager
import android.os.PowerManager
import com.justprintit.app.data.AppSettings
import com.justprintit.app.data.JobStatus
import com.justprintit.app.data.JobStore
import com.justprintit.app.data.Paper
import com.justprintit.app.data.PrinterRepository
import com.justprintit.core.ipp.IppClient
import com.justprintit.core.ipp.JobStatus as IppJobStatus
import com.justprintit.core.ipp.PrinterInfo
import com.justprintit.core.layout.ContentBounds
import com.justprintit.core.layout.DuplexSettings
import com.justprintit.core.layout.PageRange
import com.justprintit.core.layout.Sheet
import com.justprintit.core.layout.SheetPlan
import com.justprintit.core.layout.SidesMode
import com.justprintit.core.raster.PwgRasterWriter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.coroutines.coroutineContext
import kotlin.math.abs

/** What the user is shown about the current job. */
sealed interface PrintState {
    data object Idle : PrintState
    data class Working(val name: String, val step: String, val sheet: Int, val totalSheets: Int) : PrintState
    data class NeedsFlip(val name: String, val instructions: List<String>, val backCount: Int) : PrintState
    data class Done(val name: String, val pages: Int) : PrintState
    data class Canceled(val name: String) : PrintState
    /**
     * The job failed. Nothing is retried until the user picks an option in the dialog.
     * @param printedSheets sheets the printer confirmed, so "print the rest" can skip them; null when unknown.
     */
    data class Failed(
        val jobId: Long,
        val name: String,
        val message: String,
        val printedSheets: Int?,
        val totalSheets: Int,
    ) : PrintState
}

/** Everything needed to (re)run a job. Stored as JSON in history so a failed job can be retried later. */
data class PrintRequest(
    val files: List<File>,
    val mime: String,
    val name: String,
    val options: PrintOptions,
)

class PrintQueue(
    private val context: Context,
    private val scope: CoroutineScope,
    private val settings: AppSettings,
    private val store: JobStore,
    private val printer: PrinterRepository,
) {
    private val _state = MutableStateFlow<PrintState>(PrintState.Idle)
    val state: StateFlow<PrintState> = _state.asStateFlow()

    private var job: Job? = null
    private var currentIppJob: Pair<IppClient, Int>? = null

    /** Sheets of the current/last job, per pass, kept for "retry the rest" and the duplex second pass. */
    private var activeRequest: PrintRequest? = null
    private var activeJobId: Long = 0
    private var pendingBacks: List<Sheet> = emptyList()
    private var flipInstructions: List<String> = emptyList()
    private var lastPassSheets: List<Sheet> = emptyList()

    val isBusy get() = job?.isActive == true

    fun dismiss() {
        if (_state.value !is PrintState.Working) _state.value = PrintState.Idle
    }

    /** Computes the sheets for a request (used by the preview and by printing). */
    fun planSheets(doc: PrintDocument, opts: PrintOptions): List<Sheet> {
        val pages = PageRange.parse(opts.pageRange, doc.pageCount)
        return SheetPlan.group(pages, opts.perSheet)
    }

    fun duplexSettings() = settings.current.let {
        DuplexSettings(it.duplexReverseBacks, it.duplexRotateBacks, it.duplexBlankForOdd)
    }

    fun start(request: PrintRequest) {
        if (isBusy) return
        val doc = PrintDocument.open(request.files, request.mime, request.name)
        val all = try { planSheets(doc, request.options) } catch (e: Exception) { doc.close(); throw e }
        val sheets: List<Sheet>
        if (request.options.sides == SidesMode.GUIDED_DUPLEX) {
            val d = SheetPlan.duplex(all, duplexSettings())
            sheets = d.fronts
            pendingBacks = d.backs
            flipInstructions = d.instructions
        } else {
            sheets = SheetPlan.select(all, request.options.sides)
            pendingBacks = emptyList()
        }
        val withCopies = if (request.options.sides == SidesMode.GUIDED_DUPLEX) sheets else SheetPlan.withCopies(sheets, request.options.copies)
        val id = store.insert("print", request.name, withCopies.size + pendingBacks.size,
            request.files.map { it.absolutePath }, request.mime, request.options.toJson())
        activeRequest = request
        activeJobId = id
        runPass(doc, withCopies, id, closeDocAfter = true)
    }

    /** Second half of the guided double-sided flow, started only when the user taps "Print back sides". */
    fun printBacks() {
        val req = activeRequest ?: return
        if (isBusy || pendingBacks.isEmpty()) return
        val backs = pendingBacks
        pendingBacks = emptyList()
        store.setStatus(activeJobId, JobStatus.RUNNING)
        runPass(PrintDocument.open(req.files, req.mime, req.name), backs, activeJobId, closeDocAfter = true)
    }

    fun skipBacks() {
        pendingBacks = emptyList()
        store.setStatus(activeJobId, JobStatus.DONE)
        _state.value = PrintState.Idle
    }

    /** User chose "Retry" in the failure dialog or in history. [fromSheet] skips sheets already printed. */
    fun retry(jobId: Long, fromSheet: Int = 0) {
        if (isBusy) return
        val rec = store.get(jobId) ?: return
        val files = rec.files.map(::File)
        if (files.isEmpty() || !files.all { it.exists() }) {
            _state.value = PrintState.Failed(jobId, rec.name, "The document is no longer available. Open it again to print.", null, rec.totalSheets)
            return
        }
        val req = PrintRequest(files, rec.mime, rec.name, PrintOptions.fromJson(rec.options))
        if (fromSheet > 0 && jobId == activeJobId && lastPassSheets.size > fromSheet) {
            // Resume the same pass from where the printer stopped.
            val rest = lastPassSheets.drop(fromSheet)
            store.setStatus(jobId, JobStatus.RUNNING)
            runPass(PrintDocument.open(files, rec.mime, rec.name), rest, jobId, closeDocAfter = true)
        } else {
            start(req)
        }
    }

    fun cancel() {
        val ipp = currentIppJob
        job?.cancel()
        if (ipp != null) scope.launch(Dispatchers.IO) { runCatching { ipp.first.cancelJob(ipp.second) } }
        if (_state.value is PrintState.NeedsFlip) {
            pendingBacks = emptyList()
            store.setStatus(activeJobId, JobStatus.CANCELED)
            _state.value = PrintState.Idle
        }
    }

    private fun runPass(doc: PrintDocument, sheets: List<Sheet>, jobId: Long, closeDocAfter: Boolean) {
        val opts = activeRequest?.options ?: return
        lastPassSheets = sheets
        job = scope.launch(Dispatchers.IO) {
            val locks = acquireLocks()
            try {
                _state.value = PrintState.Working(doc.name, "Checking the printer…", 0, sheets.size)
                val client = printer.client()
                val info = PrinterInfo.parse(client.getPrinterAttributes(*PrinterInfo.REQUESTED))
                if (!info.canPrint) {
                    val why = info.errors.joinToString("\n") { it.text }.ifBlank { info.stateMessage ?: "The printer is not ready (${info.stateText})." }
                    throw PrintFailure(why, 0)
                }
                sendSheets(client, info, doc, sheets, opts)
                if (pendingBacks.isNotEmpty()) {
                    store.setStatus(jobId, JobStatus.WAITING_FLIP)
                    _state.value = PrintState.NeedsFlip(doc.name, flipInstructions, pendingBacks.size)
                } else {
                    store.setStatus(jobId, JobStatus.DONE)
                    val rec = store.get(jobId)
                    _state.value = PrintState.Done(doc.name, rec?.pages ?: sheets.size)
                }
            } catch (e: CancellationException) {
                store.setStatus(jobId, JobStatus.CANCELED, "Cancelled")
                _state.value = PrintState.Canceled(doc.name)
            } catch (e: PrintFailure) {
                store.setStatus(jobId, JobStatus.FAILED, e.message)
                _state.value = PrintState.Failed(jobId, doc.name, e.message ?: "Printing failed", e.printedSheets, sheets.size)
            } catch (e: Exception) {
                store.setStatus(jobId, JobStatus.FAILED, e.message)
                _state.value = PrintState.Failed(jobId, doc.name, friendly(e), null, sheets.size)
            } catch (e: OutOfMemoryError) {
                store.setStatus(jobId, JobStatus.FAILED, "Out of memory")
                _state.value = PrintState.Failed(jobId, doc.name,
                    "Not enough memory to prepare this page. Close other apps or try a smaller paper size, then retry.", null, sheets.size)
            } finally {
                currentIppJob = null
                releaseLocks(locks)
                if (closeDocAfter) doc.close()
            }
        }
    }

    private class PrintFailure(message: String, val printedSheets: Int?) : Exception(message)

    /**
     * Sends [sheets] as one IPP job (PWG raster) and waits for the printer to finish it.
     * @return number of sheets printed
     */
    private suspend fun sendSheets(
        client: IppClient,
        info: PrinterInfo,
        doc: PrintDocument,
        sheets: List<Sheet>,
        opts: PrintOptions,
    ): Int {
        val paper = Paper.of(opts.paper)
        val dpi = chooseDpi(info)
        val marginMm = if (opts.borderless && info.supportsBorderless) 0.0 else info.minMargin / 100.0
        val gray = opts.mono
        val colorPerSheet = BooleanArray(sheets.size)
        val ctx = coroutineContext

        val jobAttrs: com.justprintit.core.ipp.IppGroup.() -> Unit = {
            keyword("media", paper.keyword)
            keyword("print-color-mode", if (gray) "monochrome" else "color")
            integer("print-quality", opts.quality.coerceIn(3, 5), com.justprintit.core.ipp.IppTag.ENUM)
            keyword("sides", "one-sided")
            if (paper.isPhoto && "photographic-glossy" in info.mediaTypes) keyword("media-type", "photographic-glossy")
        }

        if (info.supportsPwg) {
            val ippJobId = client.printJob(doc.name, "image/pwg-raster", jobAttrs) { out ->
                val w = PwgRasterWriter(out)
                sheets.forEachIndexed { i, sheet ->
                    ctx.ensureActive()
                    _state.value = PrintState.Working(doc.name, "Sending page ${i + 1} of ${sheets.size}", i + 1, sheets.size)
                    val bmp = SheetRenderer.render(doc, sheet, opts, paper, dpi, marginMm)
                    try {
                        w.beginPage(PwgRasterWriter.PageSetup(bmp.width, bmp.height, dpi, gray, paper.keyword,
                            quality = opts.quality, totalPages = sheets.size))
                        val row = IntArray(bmp.width)
                        var color = false
                        for (y in 0 until bmp.height) {
                            bmp.getPixels(row, 0, bmp.width, 0, y, bmp.width, 1)
                            if (!gray && !color && ContentBounds.hasColor(row, 0, row.size)) color = true
                            w.writeRow(row)
                        }
                        w.endPage()
                        colorPerSheet[i] = color
                    } finally { bmp.recycle() }
                }
            }
            currentIppJob = client to ippJobId
            val done = waitForPrinter(client, ippJobId, doc.name, sheets.size)
            recordPages(sheets, colorPerSheet, done)
            if (done < sheets.size) throw PrintFailure(lastJobProblem ?: "The printer stopped the job.", done)
            return done
        }

        if (info.supportsJpeg) {
            // Fallback: one small job per sheet.
            sheets.forEachIndexed { i, sheet ->
                ctx.ensureActive()
                _state.value = PrintState.Working(doc.name, "Sending page ${i + 1} of ${sheets.size}", i + 1, sheets.size)
                val bmp = SheetRenderer.render(doc, sheet, opts, paper, dpi, marginMm)
                val jpeg = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }.toByteArray()
                if (!gray) {
                    val row = IntArray(bmp.width)
                    for (y in 0 until bmp.height step 4) {
                        bmp.getPixels(row, 0, bmp.width, 0, y, bmp.width, 1)
                        if (ContentBounds.hasColor(row, 0, row.size)) { colorPerSheet[i] = true; break }
                    }
                }
                bmp.recycle()
                val id = client.printJob("${doc.name} (${i + 1})", "image/jpeg", jobAttrs) { it.write(jpeg) }
                currentIppJob = client to id
                val done = waitForPrinter(client, id, doc.name, 1)
                recordPages(listOf(sheet), booleanArrayOf(colorPerSheet[i]), done)
                if (done < 1) throw PrintFailure(lastJobProblem ?: "The printer stopped the job.", i)
            }
            return sheets.size
        }
        throw PrintFailure("This printer doesn't accept a format the app can send (needs PWG raster or JPEG).", 0)
    }

    private var lastJobProblem: String? = null

    /** Polls the printer until the job ends. Returns sheets the printer reports as printed. */
    private suspend fun waitForPrinter(client: IppClient, ippJobId: Int, name: String, total: Int): Int {
        lastJobProblem = null
        var misses = 0
        var lastImpressions = 0
        while (true) {
            coroutineContext.ensureActive()
            val st = try { client.getJob(ippJobId).also { misses = 0 } } catch (e: Exception) {
                if (++misses >= 5) {
                    lastJobProblem = "Lost contact with the printer, so the app can't confirm which pages printed. Check the output tray before retrying."
                    return lastImpressions.coerceAtMost(total)
                }
                delay(2_000); continue
            }
            lastImpressions = st.impressionsCompleted ?: st.sheetsCompleted ?: lastImpressions
            _state.value = PrintState.Working(name, "Printing… ${if (lastImpressions > 0) "$lastImpressions of $total done" else ""}".trim(),
                lastImpressions.coerceAtMost(total), total)
            if (st.isFinished) {
                return when (st.state) {
                    IppJobStatus.JOB_COMPLETED -> total
                    else -> {
                        val reasons = st.reasons.filter { it != "none" && it != "job-canceled-by-user" }
                        lastJobProblem = st.message?.takeIf { it.isNotBlank() }
                            ?: reasons.firstOrNull()?.let { PrinterInfo.describeReason(it).text }
                            ?: if (st.state == IppJobStatus.JOB_CANCELED) "The job was cancelled on the printer." else "The printer aborted the job."
                        // Read the printer's state for a clearer reason (out of paper, jam…).
                        runCatching {
                            val pi = PrinterInfo.parse(client.getPrinterAttributes(*PrinterInfo.REQUESTED))
                            pi.errors.firstOrNull()?.let { lastJobProblem = it.text }
                        }
                        (st.impressionsCompleted ?: st.sheetsCompleted ?: 0).coerceIn(0, total)
                    }
                }
            }
            if (st.state == IppJobStatus.JOB_STOPPED || st.state == IppJobStatus.JOB_HELD) {
                // Printer paused the job (e.g. out of paper). Keep waiting: it resumes once fixed on the printer.
                val pi = runCatching { PrinterInfo.parse(client.getPrinterAttributes(*PrinterInfo.REQUESTED)) }.getOrNull()
                val why = pi?.errors?.firstOrNull()?.text ?: "Printer paused — check its display"
                _state.value = PrintState.Working(name, "$why. Fix it on the printer and printing continues.", lastImpressions, total)
            }
            delay(2_000)
        }
    }

    private fun recordPages(sheets: List<Sheet>, colorPerSheet: BooleanArray, printed: Int) {
        var color = 0; var mono = 0
        for (i in 0 until printed.coerceAtMost(sheets.size)) {
            if (sheets[i].isBlank) continue
            if (colorPerSheet[i]) color++ else mono++
        }
        store.addPages(activeJobId, color, mono)
    }

    private fun chooseDpi(info: PrinterInfo): Int {
        val r = info.pwgResolutions
        if (r.isEmpty() || 300 in r) return 300
        return r.minBy { abs(it - 300) }
    }

    private fun friendly(e: Exception): String = when {
        e is IllegalArgumentException -> e.message ?: "Invalid settings"
        else -> "Couldn't send the job: ${e.message ?: e.javaClass.simpleName}. Some pages may have printed — check the output tray before retrying."
    }

    private data class Locks(val wake: PowerManager.WakeLock, val wifi: WifiManager.WifiLock)

    private fun acquireLocks(): Locks {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "justprintit:print").apply { acquire(30 * 60_000L) }
        @Suppress("DEPRECATION")
        val wifi = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "justprintit:print").apply { acquire() }
        return Locks(wake, wifi)
    }

    private fun releaseLocks(l: Locks) {
        runCatching { if (l.wake.isHeld) l.wake.release() }
        runCatching { if (l.wifi.isHeld) l.wifi.release() }
    }
}
