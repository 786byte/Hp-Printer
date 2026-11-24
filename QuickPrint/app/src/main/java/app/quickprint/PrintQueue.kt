package app.quickprint

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper

enum class QState(val badge: String, val color: Int) {
    WAITING("Waiting", 0xFF667085.toInt()),
    WORKING("Printing", 0xFF0B6BCB.toInt()),
    DONE("Done ✓", 0xFF1E8E3E.toInt()),
    FAILED("Failed ✗", 0xFFD93025.toInt()),
    UNCLEAR("Did it print?", 0xFFE37400.toInt()),
    SKIPPED("Skipped", 0xFF99A0AA.toInt()),
}

class QItem(
    val id: Int,
    val name: String,
    val uris: List<Uri>,
    val opts: Opts,
    val copies: Int,
    val quality: Int,
    val detected: Paper?,
) {
    var state = QState.WAITING
    var msg = ""
    var sheets = 0
    var color = true
    /** Sheets already printed in earlier attempts — a resume leaves them out. */
    var skip = 0
    /** Sheets the printer confirmed in the latest attempt. */
    var printed = 0
    /** Sheets in the latest attempt (0 = unknown). */
    var sent = 0
    val resumeFrom: Int get() = skip + printed
}

/**
 * Prints a list of files one after another, showing each job's live state.
 *
 * Lives outside the activity so the queue keeps going when you leave the app. When a job fails or its result is
 * unclear, the queue stops and waits for your answer ([waitingFor]); it never retries anything by itself.
 */
object PrintQueue {
    interface Listener {
        fun onQueueChanged()
        /** A job needs your decision before the queue continues. */
        fun onDecisionNeeded(item: QItem)
    }

    enum class Decision { RESUME, RETRY, SKIP, STOP, PRINTED }

    private val ui = Handler(Looper.getMainLooper())
    private var nextId = 1
    private var app: Context? = null
    private var ip = ""

    /** All jobs, oldest first. Main thread only. */
    val items = ArrayList<QItem>()
    var running = false
        private set
    var stopAfterCurrent = false
        private set
    /** The job whose result you must answer; the queue is paused until you do. */
    var waitingFor: QItem? = null
        private set
    var listener: Listener? = null

    val current: QItem? get() = items.firstOrNull { it.state == QState.WORKING }

    private fun changed() = listener?.onQueueChanged()

    fun add(name: String, uris: List<Uri>, opts: Opts, copies: Int, quality: Int, detected: Paper?): QItem {
        val item = QItem(nextId++, name, uris, opts, copies, quality, detected)
        items.add(item)
        changed()
        return item
    }

    fun start(ctx: Context, printerIp: String) {
        app = ctx.applicationContext
        ip = printerIp
        stopAfterCurrent = false
        if (running || waitingFor != null) { changed(); return }
        runNext()
    }

    /** Finish the job being printed, then stop. Waiting jobs stay in the list. */
    fun stop() {
        if (running) stopAfterCurrent = true
        changed()
    }

    fun remove(item: QItem) {
        if (item.state == QState.WORKING || item === waitingFor) return
        items.remove(item)
        changed()
    }

    fun clearFinished() {
        items.removeAll { it.state == QState.DONE || it.state == QState.SKIPPED }
        changed()
    }

    /** You chose to print a failed / unclear job again (from its row). [resume] = only the sheets not yet printed. */
    fun requeue(item: QItem, resume: Boolean) {
        if (item.state != QState.FAILED && item.state != QState.UNCLEAR) return
        if (item === waitingFor) { decide(item, if (resume) Decision.RESUME else Decision.RETRY); return }
        again(item, resume)
        changed()
    }

    private fun again(item: QItem, resume: Boolean) {
        item.skip = if (resume) item.resumeFrom else 0
        item.printed = 0
        item.sent = 0
        item.state = QState.WAITING
        item.msg = if (item.skip > 0) "Will resume from sheet ${item.skip + 1}" else "Will print again"
    }

    /** Your answer to [Listener.onDecisionNeeded]. */
    fun decide(item: QItem, d: Decision) {
        if (item !== waitingFor) return
        waitingFor = null
        when (d) {
            Decision.RESUME -> again(item, true)
            Decision.RETRY -> again(item, false)
            Decision.SKIP -> { if (item.state == QState.UNCLEAR) { item.state = QState.SKIPPED; item.msg = "You said it didn't print — skipped" } }
            Decision.PRINTED -> { item.state = QState.DONE; item.msg = "You confirmed it printed"; count(item) }
            Decision.STOP -> { running = false; changed(); return }
        }
        runNext()
    }

    private fun count(item: QItem) {
        app?.let { if (item.sheets > 0) Stats.add(it, item.sheets, item.color) }
    }

    private fun runNext() {
        val ctx = app
        val item = items.firstOrNull { it.state == QState.WAITING }
        if (ctx == null || item == null || stopAfterCurrent) {
            running = false
            stopAfterCurrent = false
            changed()
            return
        }
        running = true
        item.state = QState.WORKING
        item.msg = "Starting…"
        changed()
        val opts = item.opts.copy(skip = item.skip)
        val from = if (item.skip > 0) " (from sheet ${item.skip + 1})" else ""
        Thread {
            val out = try {
                val target = Locator.find(ctx, ip, "") { m -> ui.post { item.msg = m; changed() } }
                if (target == null) {
                    Outcome.Failed("Can't find the printer on this Wi-Fi. Connect to the home Wi-Fi or the printer's own Wi-Fi.", true, 0, 0, !opts.gray)
                } else {
                    if (target != ip) {
                        ip = target
                        ctx.getSharedPreferences("qp", Context.MODE_PRIVATE).edit().putString("ip", target).apply()
                    }
                    DirectPrint.run(ctx, target, item.uris, opts, item.copies, item.quality, item.detected,
                        "Quick Print — ${item.name}", { m -> ui.post { item.msg = m + from; changed() } },
                        { n -> ui.post { item.printed = n; changed() } })
                }
            } catch (e: Throwable) {
                Outcome.Failed(e.message ?: e.javaClass.simpleName, false, null, 0, !opts.gray)
            }
            ui.post { finished(item, out) }
        }.start()
    }

    private fun finished(item: QItem, out: Outcome) {
        when (out) {
            is Outcome.Done -> {
                item.state = QState.DONE
                item.sheets = out.inked
                item.color = out.color
                val all = item.skip + out.sent
                item.msg = "$all sheet${if (all == 1) "" else "s"} printed" + if (item.skip > 0) " (resumed)" else ""
                count(item)
                runNext()
            }
            is Outcome.Cancelled -> {
                item.state = QState.SKIPPED
                item.msg = "Cancelled on the printer"
                runNext()
            }
            is Outcome.Failed -> {
                item.printed = out.printed ?: 0
                item.sent = out.sent
                item.color = out.color
                app?.let { if (item.printed > 0) Stats.add(it, item.printed, out.color) }
                val total = item.skip + out.sent
                pause(item, QState.FAILED, out.why + " " + when {
                    out.printed == null -> "The printer didn't say how many sheets came out — check the tray."
                    item.resumeFrom > 0 -> "Sheets 1–${item.resumeFrom} of $total are printed."
                    else -> "Nothing was printed."
                })
            }
            is Outcome.Unclear -> {
                item.printed = out.printed ?: 0
                item.sent = out.sent
                item.sheets = out.inked
                item.color = out.color
                pause(item, QState.UNCLEAR, out.why + if (out.printed != null)
                    " The printer last confirmed ${item.resumeFrom} of ${item.skip + out.sent} sheets." else "")
            }
            is Outcome.NoDirect -> pause(item, QState.FAILED,
                "This printer doesn't accept direct jobs, so the queue can't print it. Use Print with \"Android print screen\".")
        }
    }

    private fun pause(item: QItem, state: QState, why: String) {
        item.state = state
        item.msg = why
        running = false
        waitingFor = item
        changed()
        listener?.onDecisionNeeded(item)
    }

    fun summary(): String {
        if (items.isEmpty()) return "No jobs yet. Choose several PDFs or photos above, then tap \"Add to queue\"."
        fun n(s: QState) = items.count { it.state == s }
        val parts = listOfNotNull(
            n(QState.DONE).takeIf { it > 0 }?.let { "$it done" },
            n(QState.WORKING).takeIf { it > 0 }?.let { "$it printing" },
            n(QState.WAITING).takeIf { it > 0 }?.let { "$it waiting" },
            n(QState.FAILED).takeIf { it > 0 }?.let { "$it failed" },
            n(QState.UNCLEAR).takeIf { it > 0 }?.let { "$it to check" },
            n(QState.SKIPPED).takeIf { it > 0 }?.let { "$it skipped" },
        )
        val head = "${items.size} job${if (items.size == 1) "" else "s"}: " + parts.joinToString(" · ")
        return head + when {
            waitingFor != null -> "\nPaused — waiting for your answer."
            running && stopAfterCurrent -> "\nStopping after this job."
            running -> ""
            n(QState.WAITING) > 0 -> "\nStopped. Tap Start to continue."
            else -> ""
        }
    }
}
