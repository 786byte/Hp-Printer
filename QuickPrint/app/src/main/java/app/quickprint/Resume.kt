package app.quickprint

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * One print request, with everything needed to run it again later.
 * mode: 0 all, 1 odd, 2 even, 3 guided double-sided. side: 1 or 2 within guided double-sided.
 */
data class PrintJobSpec(
    val files: List<Uri>,
    val names: List<String>,
    val base: Opts,
    val copies: Int,
    val quality: Int,
    val detected: Paper?,
    val mode: Int,
    val side: Int = 1,
) {
    val title: String get() = if (names.size == 1) names[0] else "${names.size} files"
    val what: String get() = when (mode) {
        1 -> "odd pages"
        2 -> "even pages"
        3 -> "side $side of 2"
        else -> ""
    }

    /** The options for this pass. */
    fun opts(skip: Int): Opts = when (mode) {
        1 -> base.copy(pages = 1, skip = skip)
        2 -> base.copy(pages = 2, skip = skip)
        3 -> if (side == 1) base.copy(pages = 1, skip = skip) else base.copy(pages = 2, evenPad = true, skip = skip)
        else -> base.copy(pages = 0, skip = skip)
    }
}

/** An unfinished job saved on disk. [done] = sheets of the current pass already printed. */
data class SavedJob(val spec: PrintJobSpec, val done: Int, val passTotal: Int, val why: String)

/**
 * Keeps track of the job being printed so a failure, a crash or closing the app never means starting over:
 * the files are copied into the app, and the number of sheets the printer confirmed is saved as it prints.
 */
object Resume {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("resume", Context.MODE_PRIVATE)
    private fun jobsDir(ctx: Context) = File(ctx.filesDir, "jobs")

    /** Copies picked/shared files into the app (their permission ends when the app closes). Background thread. */
    fun stage(ctx: Context, uris: List<Uri>, names: List<String>): List<Uri> {
        cleanup(ctx)
        val dir = File(jobsDir(ctx), System.currentTimeMillis().toString()).apply { mkdirs() }
        return uris.mapIndexed { i, u ->
            if (u.scheme == "file" && u.path?.startsWith(jobsDir(ctx).path) == true) return@mapIndexed u
            var name = names.getOrElse(i) { "file$i" }.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(80).ifBlank { "file$i" }
            if (PdfTools.isPdf(ctx, u) && !name.endsWith(".pdf", true)) name += ".pdf"
            val f = File(dir, "${i}_$name")
            val ins = ctx.contentResolver.openInputStream(u) ?: throw Exception("Can't open ${names.getOrElse(i) { "file" }}")
            ins.use { input -> f.outputStream().use { input.copyTo(it) } }
            Uri.fromFile(f)
        }
    }

    /** Remember the pass about to be sent. [skip] sheets of it were already printed earlier. */
    fun begin(ctx: Context, spec: PrintJobSpec, skip: Int) {
        prefs(ctx).edit()
            .putString("spec", specToJson(spec).toString())
            .putInt("base", skip)
            .putInt("printed", 0)
            .putInt("sent", 0)
            .putString("why", "")
            .commit()
    }

    fun sent(ctx: Context, sent: Int) { prefs(ctx).edit().putInt("sent", sent).apply() }

    /** The printer confirmed [printed] sheets of the current send. Saved straight away in case the app dies. */
    fun progress(ctx: Context, printed: Int) {
        val p = prefs(ctx)
        if (printed > p.getInt("printed", 0)) p.edit().putInt("printed", printed).commit()
    }

    fun failed(ctx: Context, why: String) { prefs(ctx).edit().putString("why", why).apply() }

    fun clear(ctx: Context) { prefs(ctx).edit().clear().commit() }

    fun load(ctx: Context): SavedJob? {
        val p = prefs(ctx)
        val s = p.getString("spec", null) ?: return null
        return try {
            val spec = specFromJson(JSONObject(s))
            if (spec.files.any { u -> u.scheme == "file" && !File(u.path ?: "").exists() }) { clear(ctx); return null }
            val base = p.getInt("base", 0)
            val sent = p.getInt("sent", 0)
            SavedJob(spec, base + p.getInt("printed", 0), if (sent > 0) base + sent else 0, p.getString("why", "") ?: "")
        } catch (e: Exception) {
            clear(ctx)
            null
        }
    }

    /** Deletes copied files older than a week, except the saved unfinished job's. */
    private fun cleanup(ctx: Context) {
        val keep = load(ctx)?.spec?.files?.mapNotNull { it.path }?.toSet() ?: emptySet()
        val weekAgo = System.currentTimeMillis() - 7L * 24 * 3600 * 1000
        jobsDir(ctx).listFiles()?.forEach { d ->
            if (d.lastModified() < weekAgo && d.listFiles()?.none { it.path in keep } != false) d.deleteRecursively()
        }
    }

    // ---- JSON

    fun optsToJson(o: Opts): JSONObject = JSONObject()
        .put("paper", o.paper.name).put("nup", o.nup).put("margin", o.marginMm.toDouble()).put("fill", o.fill)
        .put("gray", o.gray).put("dpi", o.dpi).put("range", o.range).put("zoom", o.zoom.toDouble()).put("trim", o.trim)
        .put("border", o.border).put("landscape", o.landscape).put("pages", o.pages).put("evenRev", o.evenRev)
        .put("shortEdge", o.shortEdge).put("evenPad", o.evenPad).put("turnBacks", o.turnBacks)

    fun optsFromJson(j: JSONObject): Opts = Opts(
        paper = Paper.valueOf(j.getString("paper")),
        nup = j.getInt("nup"),
        marginMm = j.getDouble("margin").toFloat(),
        fill = j.getBoolean("fill"),
        gray = j.getBoolean("gray"),
        dpi = j.getInt("dpi"),
        range = j.getString("range"),
        zoom = j.getDouble("zoom").toFloat(),
        trim = j.getBoolean("trim"),
        border = j.getBoolean("border"),
        landscape = j.getBoolean("landscape"),
        pages = j.getInt("pages"),
        evenRev = j.getBoolean("evenRev"),
        shortEdge = j.getBoolean("shortEdge"),
        evenPad = j.getBoolean("evenPad"),
        turnBacks = j.optBoolean("turnBacks", false),
    )

    private fun specToJson(s: PrintJobSpec): JSONObject = JSONObject()
        .put("files", JSONArray(s.files.map { it.toString() }))
        .put("names", JSONArray(s.names))
        .put("opts", optsToJson(s.base))
        .put("copies", s.copies).put("quality", s.quality)
        .put("detected", s.detected?.name ?: "")
        .put("mode", s.mode).put("side", s.side)

    private fun specFromJson(j: JSONObject): PrintJobSpec {
        val files = j.getJSONArray("files").let { a -> List(a.length()) { Uri.parse(a.getString(it)) } }
        val names = j.getJSONArray("names").let { a -> List(a.length()) { a.getString(it) } }
        val det = j.optString("detected").takeIf { it.isNotEmpty() }?.let { Paper.valueOf(it) }
        return PrintJobSpec(files, names, optsFromJson(j.getJSONObject("opts")), j.getInt("copies"), j.getInt("quality"),
            det, j.getInt("mode"), j.optInt("side", 1))
    }
}
