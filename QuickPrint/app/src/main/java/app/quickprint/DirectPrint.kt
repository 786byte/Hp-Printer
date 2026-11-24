package app.quickprint

import android.content.Context
import android.net.Uri
import java.io.File
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * How a direct job ended. The UI decides what to ask; nothing here ever retries.
 * "Sheets" are printed sides in this send (copies included, already-printed sheets skipped).
 */
sealed class Outcome {
    /** The printer reported the job finished. [inked] = sheets with something on them (for counters). */
    data class Done(val sent: Int, val inked: Int, val color: Boolean, val total: Int) : Outcome()

    /**
     * Definitely failed. [printed] = sheets the printer confirmed before it stopped (0 when nothing was sent),
     * or null when it didn't say — then the user has to count.
     */
    data class Failed(val why: String, val nothingSent: Boolean, val printed: Int?, val sent: Int, val color: Boolean) : Outcome()

    /** The app can't tell whether it (fully) printed. [printed] = what the printer last confirmed, if anything. */
    data class Unclear(val why: String, val printed: Int?, val sent: Int, val inked: Int, val color: Boolean, val total: Int) : Outcome()

    object Cancelled : Outcome()

    /** The printer accepts none of the formats the app can send directly. */
    object NoDirect : Outcome()
}

object DirectPrint {
    private val capsCache = HashMap<String, Caps>()

    private fun stamp() = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    /**
     * Prepares, sends and follows one job, blocking until the printer finishes it (call from a background thread).
     * [Opts.skip] sheets are left out, so a failed job can carry on where it stopped.
     * @param progress short status text for the UI
     * @param onPrinted called whenever the printer confirms more finished sheets (count within this send)
     */
    fun run(
        ctx: Context, ip: String, uris: List<Uri>, o: Opts, copies: Int, quality: Int,
        detected: Paper?, jobName: String, progress: (String) -> Unit, onPrinted: (Int) -> Unit = {}
    ): Outcome {
        var prep: Prepared? = null
        var file: File? = null
        var ownFile = false
        val net = Net.forIp(ctx, ip)
        var id = 0
        var sent = 0
        var inked = 0
        var total = 0
        val color = !o.gray
        try {
            progress("Preparing…")
            val caps = synchronized(capsCache) { capsCache[ip] } ?: Ipp.caps(ip, net).also { synchronized(capsCache) { capsCache[ip] = it } }
            val p = PdfTools.prepare(ctx, uris, o)
            prep = p
            total = p.total
            val seq = p.sequence(copies, o.skip)
            if (seq.isEmpty()) return Outcome.Done(0, 0, color, total)
            sent = seq.size
            inked = seq.count { it >= 0 }
            val orig = p.original
            val format: String
            val raster: Boolean
            val sendCopies: Int
            if (orig != null && o.skip == 0 && caps.formats.contains("application/pdf") && detected == o.paper && !o.landscape) {
                // Nothing to change: send the original PDF so text stays perfectly sharp.
                file = orig
                format = "application/pdf"
                raster = false
                sendCopies = copies
            } else {
                val pwg = caps.formats.contains("image/pwg-raster")
                if (!pwg && !caps.formats.contains("image/urf")) return Outcome.NoDirect
                val dpi: Int
                val grayOk: Boolean
                if (pwg) {
                    dpi = if (caps.pwgRes.contains(300) || caps.pwgRes.isEmpty()) 300 else caps.pwgRes.min()
                    grayOk = caps.pwgTypes.any { it.startsWith("sgray_8") }
                } else {
                    val rs = caps.urf.firstOrNull { it.startsWith("RS") }
                        ?.removePrefix("RS")?.split("-")?.mapNotNull { it.toIntOrNull() } ?: emptyList()
                    dpi = if (rs.contains(300) || rs.isEmpty()) 300 else rs.min()
                    grayOk = caps.urf.contains("W8")
                }
                val f = File(ctx.cacheDir, "job_" + System.nanoTime() + if (pwg) ".pwg" else ".urf")
                file = f
                ownFile = true
                PdfTools.writeRaster(p, f, pwg, dpi, o.gray && grayOk, quality, copies, o.skip, o.paper.media) { i, n ->
                    progress("Preparing page $i / $n…")
                }
                format = if (pwg) "image/pwg-raster" else "image/urf"
                raster = true
                sendCopies = 1
            }
            progress("Sending to printer…")
            id = try {
                Ipp.printJob(ip, net, file, format, jobName.ifBlank { "Quick Print " + stamp() },
                    sendCopies, o.paper.media, color, quality, raster)
            } catch (e: IppException) {
                // The printer answered and said no: nothing will print.
                return Outcome.Failed(e.message ?: "The printer refused the job", true, 0, sent, color)
            } catch (e: Exception) {
                if (neverConnected(e)) return Outcome.Failed(reachMsg(ip, e), true, 0, sent, color)
                // The connection broke while sending or waiting for the answer: the printer may print some or all of it.
                return Outcome.Unclear("The connection dropped while sending (${e.message ?: e.javaClass.simpleName}).",
                    null, sent, inked, color, total)
            }
        } catch (e: Throwable) {
            val why = when {
                e is OutOfMemoryError -> "Not enough memory to prepare the pages. Try Draft quality or fewer pages."
                neverConnected(e) -> reachMsg(ip, e)
                else -> e.message ?: e.javaClass.simpleName
            }
            return Outcome.Failed(why, true, 0, sent, color)
        } finally {
            prep?.close()
            if (ownFile) file?.delete()
        }
        return follow(ctx, ip, id, sent, inked, color, total, progress, onPrinted)
    }

    private fun neverConnected(e: Throwable): Boolean =
        e is ConnectException || e is NoRouteToHostException || e is UnknownHostException ||
            (e is SocketTimeoutException && (e.message ?: "").contains("connect", true))

    private fun reachMsg(ip: String, e: Throwable) =
        "Can't reach the printer at $ip (${e.javaClass.simpleName}). Is it on, and is the phone on its Wi-Fi or the home Wi-Fi?"

    /** Polls Get-Job-Attributes until the job ends. */
    private fun follow(
        ctx: Context, ip: String, id: Int, sent: Int, inked: Int, color: Boolean, total: Int,
        progress: (String) -> Unit, onPrinted: (Int) -> Unit
    ): Outcome {
        if (id <= 0) {
            progress("Sent — the printer gave no job number to follow")
            Thread.sleep(20_000)
            return Outcome.Unclear("The printer accepted the job but doesn't report its progress.", null, sent, inked, color, total)
        }
        var seenPrinting = false
        var fails = 0
        var done = -1   // sheets the printer confirmed, -1 = never told us
        fun confirmed(): Int? = if (done >= 0) done.coerceAtMost(sent) else null
        while (true) {
            Thread.sleep(1500)
            val st = try {
                Ipp.jobState(ip, Net.forIp(ctx, ip), id).also { fails = 0 }
            } catch (e: Exception) {
                fails++
                null
            }
            if (st == null) {
                if (fails >= 20) return Outcome.Unclear("The printer stopped answering while printing (Wi-Fi dropped?).",
                    confirmed(), sent, inked, color, total)
                progress("Checking the printer…" + (confirmed()?.let { " ($it of $sent done)" } ?: ""))
                continue
            }
            if (st.done > done) {
                done = st.done
                onPrinted(done.coerceAtMost(sent))
            }
            val count = confirmed()?.let { " — $it of $sent done" } ?: ""
            when (st.state) {
                9 -> { onPrinted(sent); return Outcome.Done(sent, inked, color, total) }
                -1 -> return if (seenPrinting && (done < 0 || done >= sent)) Outcome.Done(sent, inked, color, total)
                    else Outcome.Unclear("The job disappeared from the printer before it finished.", confirmed(), sent, inked, color, total)
                7 -> return Outcome.Cancelled
                8 -> return Outcome.Failed("The printer stopped the job (${nice(st.reasons)}).", false, confirmed(), sent, color)
                6 -> progress("Printer paused: ${nice(st.reasons)}. Fix it on the printer and printing continues$count.")
                5 -> { seenPrinting = true; progress("Printing$count…") }
                else -> progress("Waiting in the printer's queue…")
            }
        }
    }

    fun nice(reasons: List<String>): String {
        val r = reasons.map { it.removeSuffix("-report").removeSuffix("-warning").removeSuffix("-error") }
            .filter { it != "none" && !it.startsWith("job-") && it != "processing-to-stop-point" }
            .map {
                when {
                    it.startsWith("media-empty") || it == "media-needed" -> "out of paper"
                    it.startsWith("media-jam") -> "paper jam"
                    it.startsWith("marker-supply") -> "ink low or empty"
                    it.contains("door-open") || it.contains("cover-open") -> "cover or door open"
                    else -> it.replace("-", " ")
                }
            }.distinct()
        return if (r.isEmpty()) "needs attention" else r.joinToString(", ")
    }
}
