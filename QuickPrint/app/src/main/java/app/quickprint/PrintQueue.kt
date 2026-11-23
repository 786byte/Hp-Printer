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

    enum class Decision { RETRY, SKIP, STOP, PRINTED }

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

    /** You chose to print a failed / unclear job again (from its row). Puts it back in line. */
    fun requeue(item: QItem) {
        if (item.state != QState.FAILED && item.state != QState.UNCLEAR) return
        if (item === waitingFor) { decide(item, Decision.RETRY); return }
        item.state = QState.WAITING
        item.msg = "Will print again"
        changed()
    }

    /** Your answer to [Listener.onDecisionNeeded]. */
    fun decide(item: QItem, d: Decision) {
        if (item !== waitingFor) return
        waitingFor = null
        when (d) {
            Decision.RETRY -> { item.state = QState.WAITING; item.msg = "Will print again" }
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
        val target = ip
        Thread {
            val out = try {
                DirectPrint.run(ctx, target, item.uris, item.opts, item.copies, item.quality, item.detected, "Quick Print — ${item.name}") { m ->
                    ui.post { item.msg = m; changed() }
                }
            } catch (e: Throwable) {
                Outcome.Failed(e.message ?: e.javaClass.simpleName, false)
            }
            ui.post { finished(item, out) }
        }.start()
    }

    private fun finished(item: QItem, out: Outcome) {
        when (out) {
            is Outcome.Done -> {
                item.state = QState.DONE
                item.sheets = out.sheets
                item.color = out.color
                item.msg = "${out.sheets} page${if (out.sheets == 1) "" else "s"} printed"
                count(item)
                runNext()
            }
            is Outcome.Cancelled -> {
                item.state = QState.SKIPPED
                item.msg = "Cancelled on the printer"
                runNext()
            }
            is Outcome.Failed -> pause(item, QState.FAILED, out.why +
                if (out.nothingSent) " Nothing was printed." else " Some pages may have printed.")
            is Outcome.Unclear -> {
                item.sheets = out.sheets
                item.color = out.color
                pause(item, QState.UNCLEAR, out.why)
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
