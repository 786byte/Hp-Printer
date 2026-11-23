package app.quickprint

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintJob
import android.print.PrintJobInfo
import android.print.PrintManager
import android.provider.MediaStore
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var prefs: SharedPreferences
    private lateinit var disc: Discovery

    private var ip = ""
    private var printerName = ""
    private val uris = ArrayList<Uri>()
    private val scans = ArrayList<ByteArray>()
    private var foundListener: ((String, String) -> Unit)? = null
    private var detectedPaper: Paper? = null
    private val capsCache = HashMap<String, Caps>()

    private lateinit var root: LinearLayout
    private lateinit var printerTv: TextView
    private lateinit var inkBox: LinearLayout
    private lateinit var inkNote: TextView
    private lateinit var filesTv: TextView
    private lateinit var rangeEt: EditText
    private lateinit var sendSp: Spinner
    private lateinit var copiesEt: EditText
    private lateinit var counterTv: TextView
    private lateinit var modeSp: Spinner
    private lateinit var evenSp: Spinner
    private lateinit var flipSp: Spinner
    private lateinit var nupSp: Spinner
    private lateinit var paperSp: Spinner
    private lateinit var orientSp: Spinner
    private lateinit var zoomEt: EditText
    private lateinit var marginEt: EditText
    private lateinit var qualSp: Spinner
    private lateinit var grayCb: CheckBox
    private lateinit var fillCb: CheckBox
    private lateinit var trimCb: CheckBox
    private lateinit var borderCb: CheckBox
    private lateinit var printBtn: Button
    private lateinit var statusTv: TextView
    private lateinit var statsTv: TextView
    private lateinit var scanColorSp: Spinner
    private lateinit var scanDpiSp: Spinner
    private lateinit var scanBtn: Button
    private lateinit var scanInfo: TextView

    private val papers = Paper.values()
    private val nups = intArrayOf(1, 2, 3, 4, 6, 9)
    private val dpis = intArrayOf(130, 200, 300)

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("qp", Context.MODE_PRIVATE)
        ip = prefs.getString("ip", "") ?: ""
        printerName = prefs.getString("name", "") ?: ""
        disc = Discovery(this) { name, addr -> foundListener?.invoke(name, addr) }
        buildUi()
        updStats()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        refreshInk()
    }

    override fun onDestroy() {
        disc.stop()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- helpers

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun bg(block: () -> Unit) = Thread { block() }.start()
    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()
    private fun stamp() = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    private val grey = Color.parseColor("#667085")

    private fun label(text: String, size: Float = 14f, color: Int = Color.parseColor("#14171C")): TextView {
        val t = TextView(this)
        t.text = text
        t.textSize = size
        t.setTextColor(color)
        return t
    }

    private fun card(title: String): LinearLayout {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        val bgd = GradientDrawable()
        bgd.setColor(Color.WHITE)
        bgd.cornerRadius = dp(14).toFloat()
        bgd.setStroke(dp(1), Color.parseColor("#E3E6EA"))
        c.background = bgd
        c.setPadding(dp(14), dp(12), dp(14), dp(14))
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.bottomMargin = dp(12)
        c.layoutParams = lp
        val h = label(title.uppercase(), 12f, grey)
        h.setPadding(0, 0, 0, dp(8))
        c.addView(h)
        root.addView(c)
        return c
    }

    private fun button(text: String, primary: Boolean = false, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = text
        b.isAllCaps = false
        if (primary) {
            b.setBackgroundColor(Color.parseColor("#0B6BCB"))
            b.setTextColor(Color.WHITE)
        }
        b.setOnClickListener { onClick() }
        return b
    }

    private fun row(vararg views: View): LinearLayout {
        val r = LinearLayout(this)
        r.orientation = LinearLayout.HORIZONTAL
        for (v in views) r.addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return r
    }

    private fun spinner(items: List<String>, sel: Int = 0): Spinner {
        val s = Spinner(this)
        s.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, items)
        s.setSelection(sel)
        return s
    }

    private fun numberField(hint: String, value: String): EditText {
        val e = EditText(this)
        e.hint = hint
        e.setText(value)
        e.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        e.textSize = 15f
        return e
    }

    private fun labeled(text: String, v: View): LinearLayout {
        val l = LinearLayout(this)
        l.orientation = LinearLayout.VERTICAL
        l.setPadding(0, dp(4), dp(6), dp(2))
        l.addView(label(text, 12f, grey))
        l.addView(v)
        return l
    }

    private fun space() = View(this)

    // ---------------------------------------------------------------- UI

    private fun buildUi() {
        val sv = ScrollView(this)
        sv.setBackgroundColor(Color.parseColor("#F4F5F7"))
        root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(14), dp(18), dp(14), dp(24))
        sv.addView(root)
        setContentView(sv)

        val head = label("Quick Print", 22f)
        head.setPadding(dp(2), 0, 0, dp(2))
        root.addView(head)
        statusTv = label("", 14f, Color.parseColor("#0B6BCB"))
        statusTv.setPadding(dp(2), 0, 0, dp(10))
        root.addView(statusTv)

        // Printer + ink
        val pc = card("Printer & ink")
        printerTv = label("No printer selected", 15f)
        pc.addView(printerTv)
        pc.addView(row(
            button("Find printer") { findPrinter() },
            button("Enter IP") { enterIp() },
            button("Refresh") { refreshInk() }
        ))
        inkNote = label("", 13f, grey)
        pc.addView(inkNote)
        inkBox = LinearLayout(this)
        inkBox.orientation = LinearLayout.VERTICAL
        pc.addView(inkBox)

        // Print
        val cc = card("Print")
        cc.addView(button("Choose PDF / photos") { pickFiles() })
        filesTv = label("Nothing chosen. You can also share a file to Quick Print from any app.", 13f, grey)
        cc.addView(filesTv)
        rangeEt = EditText(this)
        rangeEt.hint = "PDF pages to use, e.g. 1-3,5 (blank = all)"
        rangeEt.inputType = InputType.TYPE_CLASS_TEXT
        rangeEt.textSize = 14f
        cc.addView(rangeEt)

        modeSp = spinner(listOf("All pages", "Odd pages only", "Even pages only", "Double-sided (guided)"))
        evenSp = spinner(listOf("Reversed (usual)", "Normal order"))
        flipSp = spinner(listOf("Long edge (like a book)", "Short edge (like a notepad)"))
        nupSp = spinner(listOf("1 per sheet", "2 per sheet", "3 per sheet", "4 per sheet", "6 per sheet", "9 per sheet"))
        paperSp = spinner(papers.map { it.label })
        orientSp = spinner(listOf("Portrait", "Landscape"))
        zoomEt = numberField("100", "100")
        marginEt = numberField("8", "8")
        qualSp = spinner(listOf("Draft (fast)", "Normal", "High (sharpest)"), 1)

        cc.addView(row(labeled("Which pages", modeSp), labeled("Even-page order", evenSp)))
        cc.addView(row(labeled("Flip edge (double-sided)", flipSp), labeled("Pages per sheet", nupSp)))
        cc.addView(row(labeled("Paper", paperSp), labeled("Orientation", orientSp)))
        cc.addView(row(labeled("Zoom % (100 = fit page)", zoomEt), labeled("Margin (mm)", marginEt)))
        sendSp = spinner(listOf("Direct to printer (best)", "Android print screen"), prefs.getInt("send", 0))
        copiesEt = numberField("1", "1")
        copiesEt.inputType = InputType.TYPE_CLASS_NUMBER
        cc.addView(row(labeled("Quality", qualSp), labeled("Copies", copiesEt)))
        cc.addView(row(labeled("Send using", sendSp), space()))

        grayCb = CheckBox(this)
        grayCb.text = "Black & white (saves colour ink)"
        fillCb = CheckBox(this)
        fillCb.text = "Fill the sheet (crops edges, good for photos)"
        trimCb = CheckBox(this)
        trimCb.text = "Trim white margins (auto-zoom small content)"
        borderCb = CheckBox(this)
        borderCb.text = "Draw a border around each page"
        cc.addView(grayCb)
        cc.addView(fillCb)
        cc.addView(trimCb)
        cc.addView(borderCb)

        cc.addView(label("Presets", 12f, grey))
        cc.addView(row(
            button("Document") { preset() },
            button("Save paper") { preset(nup = 1, qual = 0, gray = true) },
            button("Slides ×3") { preset(nup = 2, gray = true, border = true) }
        ))
        cc.addView(row(
            button("Zoom small PDF") { preset(trim = true) },
            button("Photo 4×6") { preset(paper = papers.indexOf(Paper.P46), qual = 2, fill = true, margin = "0") },
            button("Double-sided") { preset(mode = 3) }
        ))

        printBtn = button("Print", true) { doPrint() }
        val plp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        plp.topMargin = dp(8)
        cc.addView(printBtn, plp)
        cc.addView(label("\"Direct to printer\" sends the job itself over Wi-Fi, so it works with mobile data on " +
            "and shows real progress. If a job fails, the app asks you before trying again.", 12f, grey))

        // History
        val hc = card("Pages printed from this app")
        statsTv = label("", 14f)
        hc.addView(statsTv)
        counterTv = label("", 13f, grey)
        counterTv.setPadding(0, dp(6), 0, 0)
        hc.addView(counterTv)
        hc.addView(button("Reset counters") {
            AlertDialog.Builder(this)
                .setTitle("Reset counters?")
                .setMessage("This clears the page counts shown above.")
                .setPositiveButton("Reset") { _, _ -> Stats.reset(this); updStats() }
                .setNegativeButton("Cancel", null)
                .show()
        })

        // Backup
        val bc = card("Backup")
        bc.addView(label("Your counters and printer settings are backed up to your Google account automatically " +
            "(Android backup). You can also save a backup file, for example to Google Drive, and restore it later.", 13f, grey))
        bc.addView(row(
            button("Save backup file") { exportBackup() },
            button("Restore") { importBackup() }
        ))

        // Scan
        val sc = card("Scan (flatbed glass)")
        scanColorSp = spinner(listOf("Colour", "Black & white"))
        scanDpiSp = spinner(listOf("150 dpi (fast)", "300 dpi", "600 dpi"), 1)
        sc.addView(row(labeled("Mode", scanColorSp), labeled("Resolution", scanDpiSp)))
        scanBtn = button("Scan a page", true) { doScan() }
        val slp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        slp.topMargin = dp(8)
        sc.addView(scanBtn, slp)
        scanInfo = label("No pages scanned yet.", 13f, grey)
        sc.addView(scanInfo)
        sc.addView(row(
            button("Save PDF") { saveScans(true) },
            button("Save JPGs") { saveScans(false) },
            button("Clear") { scans.clear(); updScanInfo() }
        ))
    }

    private fun preset(
        paper: Int = 0, nup: Int = 0, qual: Int = 1, gray: Boolean = false, fill: Boolean = false,
        trim: Boolean = false, border: Boolean = false, mode: Int = 0, margin: String = "8"
    ) {
        paperSp.setSelection(paper)
        nupSp.setSelection(nup)
        qualSp.setSelection(qual)
        grayCb.isChecked = gray
        fillCb.isChecked = fill
        trimCb.isChecked = trim
        borderCb.isChecked = border
        modeSp.setSelection(mode)
        zoomEt.setText("100")
        marginEt.setText(margin)
        orientSp.setSelection(0)
    }

    private fun updStats() {
        statsTv.text = Stats.text(this)
    }

    // ---------------------------------------------------------------- printer

    private fun findPrinter() {
        val names = ArrayList<String>()
        val addrs = ArrayList<String>()
        val ad = ArrayAdapter(this, android.R.layout.simple_list_item_1, names)
        val dlg = AlertDialog.Builder(this)
            .setTitle("Searching Wi-Fi…")
            .setAdapter(ad) { d, which ->
                setPrinter(addrs[which], names[which].substringBefore("  ·"))
                d.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .setOnDismissListener {
                disc.stop()
                foundListener = null
            }
            .create()
        foundListener = { name, addr ->
            if (!addrs.contains(addr)) {
                addrs.add(addr)
                ad.add("$name  ·  $addr")
            }
        }
        dlg.show()
        disc.start()
    }

    private fun enterIp() {
        val et = EditText(this)
        et.hint = "e.g. 192.168.0.105"
        et.setText(ip)
        et.inputType = InputType.TYPE_CLASS_TEXT
        AlertDialog.Builder(this)
            .setTitle("Printer IP address")
            .setMessage("On the printer's screen: Wireless / Network settings.")
            .setView(et)
            .setPositiveButton("Save") { _, _ ->
                val v = et.text.toString().trim().removePrefix("http://").substringBefore("/")
                if (v.isNotEmpty()) setPrinter(v, "Printer")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setPrinter(addr: String, name: String) {
        ip = addr
        printerName = name
        prefs.edit().putString("ip", ip).putString("name", name).apply()
        refreshInk()
    }

    private fun refreshInk() {
        if (ip.isEmpty()) {
            printerTv.text = "No printer selected"
            inkNote.text = "Tap Find printer."
            inkBox.removeAllViews()
            return
        }
        printerTv.text = (if (printerName.isEmpty()) "Printer" else printerName) + "  ·  " + ip
        inkNote.text = "Checking…"
        val target = ip
        bg {
            try {
                val net = Net.wifi(this)
                val st = Ipp.status(target, net)
                ui.post { if (target == ip) showStatus(st) }
                val u = try { Ledm.usage(target, net) } catch (e: Exception) { null }
                ui.post { showUsage(u) }
            } catch (e: Exception) {
                ui.post {
                    inkBox.removeAllViews()
                    inkNote.text = "Can't reach the printer at $target. Is the phone connected to the printer's Wi-Fi?"
                }
            }
        }
    }

    private fun showUsage(u: Usage?) {
        if (u == null) {
            counterTv.text = ""
            return
        }
        var s = "Printer's own counter (all apps, since new): ${u.total} pages"
        if (u.color >= 0) s += "  ·  ${u.color} colour"
        if (u.mono >= 0) s += "  ·  ${u.mono} B/W"
        counterTv.text = s
    }

    private fun showStatus(st: PrinterStatus) {
        val reasons = st.reasons.joinToString(", ") { it.replace("-", " ") }
        inkNote.text = st.state + (if (reasons.isNotEmpty()) "  ·  $reasons" else "")
        if (st.model.isNotEmpty() && printerName.isEmpty()) printerTv.text = st.model + "  ·  " + ip
        inkBox.removeAllViews()
        if (st.markers.isEmpty()) {
            inkBox.addView(label("This printer didn't report ink levels.", 13f, grey))
            return
        }
        for (m in st.markers) {
            val head = LinearLayout(this)
            head.orientation = LinearLayout.HORIZONTAL
            head.gravity = Gravity.CENTER_VERTICAL
            head.setPadding(0, dp(8), 0, dp(2))
            val dot = View(this)
            val gd = GradientDrawable()
            gd.shape = GradientDrawable.OVAL
            gd.setColor(m.color)
            gd.setStroke(dp(1), Color.parseColor("#99A0AA"))
            dot.background = gd
            head.addView(dot, LinearLayout.LayoutParams(dp(14), dp(14)))
            val nm = label("  " + m.name, 14f)
            head.addView(nm, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val known = m.level in 0..100
            head.addView(label(if (known) "${m.level}%" else "level unknown", 13f, grey))
            inkBox.addView(head)
            val pb = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
            pb.max = 100
            pb.progress = if (known) m.level else 0
            pb.progressTintList = ColorStateList.valueOf(if (m.color == Color.WHITE) Color.GRAY else m.color)
            inkBox.addView(pb, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(10)))
        }
    }

    // ---------------------------------------------------------------- choosing files

    private fun pickFiles() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "*/*"
        i.putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/pdf", "image/*"))
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        startActivityForResult(i, 11)
    }

    // ---------------------------------------------------------------- backup

    private fun exportBackup() {
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "application/json"
        i.putExtra(Intent.EXTRA_TITLE, "QuickPrint-backup-" + stamp() + ".json")
        startActivityForResult(i, 21)
    }

    private fun importBackup() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "*/*"
        startActivityForResult(i, 22)
    }

    private fun toJson(sp: SharedPreferences): JSONObject {
        val o = JSONObject()
        for ((k, v) in sp.all) {
            val e = JSONObject()
            when (v) {
                is Int -> e.put("t", "i").put("v", v)
                is Long -> e.put("t", "l").put("v", v)
                is Float -> e.put("t", "f").put("v", v.toDouble())
                is Boolean -> e.put("t", "b").put("v", v)
                is String -> e.put("t", "s").put("v", v)
                else -> continue
            }
            o.put(k, e)
        }
        return o
    }

    private fun fromJson(o: JSONObject, sp: SharedPreferences) {
        val ed = sp.edit().clear()
        for (k in o.keys()) {
            val e = o.getJSONObject(k)
            when (e.getString("t")) {
                "i" -> ed.putInt(k, e.getInt("v"))
                "l" -> ed.putLong(k, e.getLong("v"))
                "f" -> ed.putFloat(k, e.getDouble("v").toFloat())
                "b" -> ed.putBoolean(k, e.getBoolean("v"))
                "s" -> ed.putString(k, e.getString("v"))
            }
        }
        ed.apply()
    }

    private fun writeBackup(u: Uri) {
        try {
            val o = JSONObject()
            o.put("app", "QuickPrint")
            o.put("version", 1)
            o.put("settings", toJson(prefs))
            o.put("stats", toJson(Stats.p(this)))
            val os = contentResolver.openOutputStream(u) ?: throw Exception("Cannot write file")
            os.use { it.write(o.toString(2).toByteArray(Charsets.UTF_8)) }
            toast("Backup saved")
        } catch (e: Exception) {
            toast("Couldn't save backup: " + (e.message ?: ""))
        }
    }

    private fun readBackup(u: Uri) {
        try {
            val ins = contentResolver.openInputStream(u) ?: throw Exception("Cannot open file")
            val o = JSONObject(ins.use { String(it.readBytes(), Charsets.UTF_8) })
            if (o.optString("app") != "QuickPrint") throw Exception("Not a Quick Print backup")
            AlertDialog.Builder(this)
                .setTitle("Restore backup?")
                .setMessage("This replaces your current counters and printer settings with the ones in the file.")
                .setPositiveButton("Restore") { _, _ ->
                    fromJson(o.getJSONObject("settings"), prefs)
                    fromJson(o.getJSONObject("stats"), Stats.p(this))
                    ip = prefs.getString("ip", "") ?: ""
                    printerName = prefs.getString("name", "") ?: ""
                    updStats()
                    refreshInk()
                    toast("Backup restored")
                }
                .setNegativeButton("Cancel", null)
                .show()
        } catch (e: Exception) {
            toast("Couldn't restore: " + (e.message ?: ""))
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        if (requestCode == 21) { data.data?.let { writeBackup(it) }; return }
        if (requestCode == 22) { data.data?.let { readBackup(it) }; return }
        if (requestCode != 11) return
        uris.clear()
        val clip = data.clipData
        if (clip != null) {
            for (k in 0 until clip.itemCount) uris.add(clip.getItemAt(k).uri)
        } else {
            data.data?.let { uris.add(it) }
        }
        updFiles()
    }

    @Suppress("DEPRECATION")
    private fun handleIntent(i: Intent?) {
        if (i == null) return
        val found = ArrayList<Uri>()
        when (i.action) {
            Intent.ACTION_SEND -> {
                val u: Uri? = if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else i.getParcelableExtra(Intent.EXTRA_STREAM)
                if (u != null) found.add(u)
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val l: ArrayList<Uri>? = if (Build.VERSION.SDK_INT >= 33) i.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else i.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
                if (l != null) found.addAll(l)
            }
            Intent.ACTION_VIEW -> i.data?.let { found.add(it) }
        }
        if (found.isNotEmpty()) {
            uris.clear()
            uris.addAll(found)
            updFiles()
        }
    }

    private fun updFiles() {
        filesTv.text = if (uris.isEmpty()) "Nothing chosen."
        else uris.size.toString() + " file" + (if (uris.size == 1) "" else "s") + " ready: " +
            uris.joinToString(", ") { it.lastPathSegment?.substringAfterLast('/') ?: "file" }
        detectSize()
    }

    /** Reads the PDF's page size and selects the matching paper. */
    private fun detectSize() {
        detectedPaper = null
        val first = uris.firstOrNull { PdfTools.isPdf(this, it) } ?: return
        bg {
            val d = try { PdfTools.detect(this, first) } catch (e: Exception) { null }
            ui.post {
                if (d == null || uris.firstOrNull { PdfTools.isPdf(this, it) } != first) return@post
                val p = d.paper
                detectedPaper = p
                if (p != null) {
                    paperSp.setSelection(papers.indexOf(p))
                    filesTv.append("\nPDF page size: ${p.label}. Paper set to ${p.label}.")
                } else {
                    filesTv.append("\nPDF page size: ${d.text} (not a standard paper size).")
                }
            }
        }
    }

    // ---------------------------------------------------------------- printing

    private fun readOpts(): Opts {
        val zoom = (zoomEt.text.toString().toFloatOrNull() ?: 100f).coerceIn(25f, 400f) / 100f
        val margin = (marginEt.text.toString().toFloatOrNull() ?: 8f).coerceIn(0f, 40f)
        return Opts(
            paper = papers[paperSp.selectedItemPosition],
            nup = nups[nupSp.selectedItemPosition],
            marginMm = margin,
            fill = fillCb.isChecked,
            gray = grayCb.isChecked,
            dpi = dpis[qualSp.selectedItemPosition],
            range = rangeEt.text.toString(),
            zoom = zoom,
            trim = trimCb.isChecked,
            border = borderCb.isChecked,
            landscape = orientSp.selectedItemPosition == 1,
            pages = 0,
            evenRev = evenSp.selectedItemPosition == 0,
            shortEdge = flipSp.selectedItemPosition == 1,
            evenPad = false
        )
    }

    private fun doPrint() {
        if (uris.isEmpty()) {
            toast("Choose a file first")
            return
        }
        val det = detectedPaper
        val sel = papers[paperSp.selectedItemPosition]
        if (det != null && det != sel) {
            AlertDialog.Builder(this)
                .setTitle("Paper size check")
                .setMessage("This PDF is ${det.label} size, but ${sel.label} is selected.\n\n" +
                    "Which paper do you want to print on? Make sure that paper is loaded in the tray.")
                .setPositiveButton(det.label) { _, _ ->
                    paperSp.setSelection(papers.indexOf(det))
                    startPrint()
                }
                .setNegativeButton(sel.label) { _, _ -> startPrint() }
                .setNeutralButton("Cancel", null)
                .show()
        } else {
            startPrint()
        }
    }

    private fun startPrint() {
        prefs.edit().putInt("send", sendSp.selectedItemPosition).apply()
        val list = ArrayList(uris)
        val base = readOpts()
        when (modeSp.selectedItemPosition) {
            0 -> runJob(list, base, "") { }
            1 -> runJob(list, base.copy(pages = 1), "odd pages") { }
            2 -> runJob(list, base.copy(pages = 2), "even pages") { }
            else -> runGuided(list, base)
        }
    }

    /** Double-sided in two steps: front sides first, then the backs. */
    private fun runGuided(list: List<Uri>, base: Opts) {
        runJob(list, base.copy(pages = 1), "side 1 of 2") { total ->
            if (total < 2) {
                statusTv.text = "Done. The document has only one sheet, so there is no back side."
                return@runJob
            }
            AlertDialog.Builder(this)
                .setTitle("Side 1 printed")
                .setMessage(
                    "Take the printed pages out and put them back in the paper tray so the BLANK sides get printed. " +
                        "Keep the pages in the same order.\n\n" +
                        "First time? Test with a 4-page PDF. If page 2 comes out upside down, change \"Flip edge\"; " +
                        "if the backs are on the wrong sheets, change \"Even-page order\"."
                )
                .setCancelable(false)
                .setPositiveButton("Print other side") { _, _ ->
                    runJob(list, base.copy(pages = 2, evenPad = true), "side 2 of 2") {
                        statusTv.text = "Double-sided print finished ✓"
                    }
                }
                .setNegativeButton("Stop here") { _, _ -> statusTv.text = "Stopped after side 1" }
                .show()
        }
    }

    private fun runJob(list: List<Uri>, o: Opts, what: String, onDone: (Int) -> Unit) {
        if (sendSp.selectedItemPosition == 0 && ip.isNotEmpty()) runDirect(list, o, what, onDone)
        else runAndroid(list, o, what, onDone)
    }

    private fun tagOf(what: String) = if (what.isEmpty()) "" else " ($what)"

    private fun readCopies() = (copiesEt.text.toString().toIntOrNull() ?: 1).coerceIn(1, 99)

    private class NoDirect : Exception()

    // ---------- direct to printer (IPP over Wi-Fi)

    private fun runDirect(list: List<Uri>, o: Opts, what: String, onDone: (Int) -> Unit) {
        val tag = tagOf(what)
        val target = ip
        val copies = readCopies()
        val det = detectedPaper
        val quality = intArrayOf(3, 4, 5)[qualSp.selectedItemPosition]
        val retry = { runDirect(list, o, what, onDone) }
        printBtn.isEnabled = false
        statusTv.text = "Preparing…$tag"
        bg {
            var prep: Prepared? = null
            try {
                val net = Net.wifi(this)
                val caps = capsCache[target] ?: Ipp.caps(target, net).also { capsCache[target] = it }
                val p = PdfTools.prepare(this, list, o)
                prep = p
                cacheDir.listFiles()?.forEach { if (it.name.startsWith("job_")) it.delete() }
                val orig = p.original
                val file: File
                val format: String
                val raster: Boolean
                val sendCopies: Int
                if (orig != null && caps.formats.contains("application/pdf") && det == o.paper && !o.landscape) {
                    file = orig
                    format = "application/pdf"
                    raster = false
                    sendCopies = copies
                } else {
                    val pwg = caps.formats.contains("image/pwg-raster")
                    if (!pwg && !caps.formats.contains("image/urf")) throw NoDirect()
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
                    file = File(cacheDir, "job_" + System.currentTimeMillis() + if (pwg) ".pwg" else ".urf")
                    PdfTools.writeRaster(p, file, pwg, dpi, o.gray && grayOk, quality, copies, o.paper.media) { i, n ->
                        ui.post { statusTv.text = "Preparing page $i / $n…$tag" }
                    }
                    format = if (pwg) "image/pwg-raster" else "image/urf"
                    raster = true
                    sendCopies = 1
                }
                ui.post { statusTv.text = "Sending to printer…$tag" }
                val id = Ipp.printJob(target, net, file, format, "Quick Print " + stamp(),
                    sendCopies, o.paper.media, !o.gray, quality, raster)
                val res = BuildResult(p.order.size, p.total)
                ui.post {
                    printBtn.isEnabled = true
                    track(target, id, res.printed * copies, !o.gray, tag, { onDone(res.total) }, retry)
                }
            } catch (e: NoDirect) {
                ui.post {
                    printBtn.isEnabled = true
                    toast("This printer doesn't take direct jobs. Using the Android print screen.")
                    runAndroid(list, o, what, onDone)
                }
            } catch (e: Throwable) {
                ui.post {
                    printBtn.isEnabled = true
                    statusTv.text = "Couldn't send$tag"
                    AlertDialog.Builder(this)
                        .setTitle("Couldn't send to the printer")
                        .setMessage((e.message ?: e.javaClass.simpleName) + "\n\n" +
                            "Check that the printer is on and your phone is connected to its Wi-Fi. Nothing was printed or counted.")
                        .setCancelable(false)
                        .setPositiveButton("Try again") { _, _ -> retry() }
                        .setNeutralButton("Use print screen") { _, _ -> runAndroid(list, o, what, onDone) }
                        .setNegativeButton("Cancel") { _, _ -> statusTv.text = "Print cancelled" }
                        .show()
                }
            } finally {
                prep?.close()
            }
        }
    }

    private fun nice(reasons: List<String>): String {
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

    /** Follows a direct job until the printer says it's done. Asks you whenever the result is unclear. */
    private fun track(target: String, id: Int, pages: Int, color: Boolean, tag: String, onDone: () -> Unit, retry: () -> Unit) {
        val finish = {
            Stats.add(this, pages, color)
            updStats()
            statusTv.text = "Printed ✓$tag"
            refreshInk()
            onDone()
        }
        if (id <= 0) {
            statusTv.text = "Sent$tag"
            ui.postDelayed({ askDidItPrint(tag, finish, retry) }, 20000)
            return
        }
        bg {
            var seenPrinting = false
            var fails = 0
            while (true) {
                Thread.sleep(1500)
                val st = try {
                    Ipp.jobState(target, Net.wifi(this), id).also { fails = 0 }
                } catch (e: Exception) {
                    fails++
                    null
                }
                if (st == null) {
                    if (fails >= 12) {
                        ui.post { askDidItPrint(tag, finish, retry) }
                        return@bg
                    }
                    ui.post { statusTv.text = "Checking the printer…$tag" }
                    continue
                }
                when (st.state) {
                    9 -> { ui.post { finish() }; return@bg }
                    -1 -> {
                        ui.post { if (seenPrinting) finish() else askDidItPrint(tag, finish, retry) }
                        return@bg
                    }
                    7 -> { ui.post { statusTv.text = "Cancelled on the printer$tag" }; return@bg }
                    8 -> {
                        ui.post { askFailed(tag, nice(st.reasons), retry) }
                        return@bg
                    }
                    6 -> ui.post { statusTv.text = "Printer paused: ${nice(st.reasons)}. Fix it and printing continues.$tag" }
                    5 -> { seenPrinting = true; ui.post { statusTv.text = "Printing…$tag" } }
                    else -> ui.post { statusTv.text = "Waiting in the printer's queue…$tag" }
                }
            }
        }
    }

    private fun askDidItPrint(tag: String, finish: () -> Unit, retry: () -> Unit) {
        statusTv.text = "Not sure if it printed$tag"
        AlertDialog.Builder(this)
            .setTitle("Did it print?")
            .setMessage("The printer stopped reporting on this job$tag, so the app can't tell whether it finished.\n\n" +
                "Did all the pages come out correctly?")
            .setCancelable(false)
            .setPositiveButton("Yes") { _, _ -> finish() }
            .setNeutralButton("No, print again") { _, _ -> retry() }
            .setNegativeButton("No, stop") { _, _ -> statusTv.text = "Stopped$tag" }
            .show()
    }

    private fun askFailed(tag: String, why: String, retry: () -> Unit) {
        statusTv.text = "Print failed$tag"
        AlertDialog.Builder(this)
            .setTitle("Print failed")
            .setMessage("The printer stopped this job$tag ($why). Nothing has been counted.\n\nTry again?")
            .setCancelable(false)
            .setPositiveButton("Try again") { _, _ -> retry() }
            .setNegativeButton("Cancel") { _, _ -> statusTv.text = "Print cancelled" }
            .show()
    }

    // ---------- Android print screen

    private fun runAndroid(list: List<Uri>, o: Opts, what: String, onDone: (Int) -> Unit) {
        printBtn.isEnabled = false
        statusTv.text = "Preparing…"
        bg {
            var prep: Prepared? = null
            try {
                val p = PdfTools.prepare(this, list, o)
                prep = p
                cacheDir.listFiles()?.forEach { if (it.name.startsWith("job_")) it.delete() }
                val out = File(cacheDir, "job_" + System.currentTimeMillis() + ".pdf")
                PdfTools.writePdf(p, out) { i, n -> ui.post { statusTv.text = "Preparing $i / $n…" } }
                val res = BuildResult(p.order.size, p.total)
                ui.post {
                    printBtn.isEnabled = true
                    submit(out, res, o, what, onDone)
                }
            } catch (e: Throwable) {
                ui.post {
                    printBtn.isEnabled = true
                    statusTv.text = "Couldn't prepare: " + (e.message ?: e.javaClass.simpleName)
                }
            } finally {
                prep?.close()
            }
        }
    }

    private fun mediaOf(o: Opts): PrintAttributes.MediaSize {
        val m = when (o.paper) {
            Paper.A4 -> PrintAttributes.MediaSize.ISO_A4
            Paper.LEGAL -> PrintAttributes.MediaSize.NA_LEGAL
            Paper.LETTER -> PrintAttributes.MediaSize.NA_LETTER
            Paper.A5 -> PrintAttributes.MediaSize.ISO_A5
            Paper.P46 -> PrintAttributes.MediaSize.NA_INDEX_4X6
            Paper.P57 -> PrintAttributes.MediaSize("na_5x7", "5x7 in", 5000, 7000)
        }
        return if (o.landscape) m.asLandscape() else m.asPortrait()
    }

    private fun submit(file: File, res: BuildResult, o: Opts, what: String, onDone: (Int) -> Unit) {
        val pm = getSystemService(Context.PRINT_SERVICE) as PrintManager
        val attrs = PrintAttributes.Builder()
            .setMediaSize(mediaOf(o))
            .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
            .setColorMode(if (o.gray) PrintAttributes.COLOR_MODE_MONOCHROME else PrintAttributes.COLOR_MODE_COLOR)
            .build()
        val job = pm.print("Quick Print " + stamp(), FileAdapter(file, "QuickPrint.pdf", res.printed), attrs)
        if (job == null) {
            statusTv.text = "Couldn't open the print screen."
            return
        }
        watch(job, res, what, { submit(file, res, o, what, onDone) }, onDone)
    }

    /** Polls the print job. On failure, or if it stops updating, it asks you; it never retries by itself. */
    private fun watch(job: PrintJob, res: BuildResult, what: String, retry: () -> Unit, onDone: (Int) -> Unit) {
        val tag = tagOf(what)
        var lastState = -1
        var lastChange = System.currentTimeMillis()
        var color = true
        var copies = 1
        val finish = {
            Stats.add(this@MainActivity, res.printed * copies, color)
            updStats()
            statusTv.text = "Printed ✓$tag"
            refreshInk()
            onDone(res.total)
        }
        val r = object : Runnable {
            override fun run() {
                val info = job.info
                if (info != null) {
                    copies = maxOf(1, info.copies)
                    color = info.attributes?.colorMode != PrintAttributes.COLOR_MODE_MONOCHROME
                }
                val state = info?.state ?: 0
                if (state != lastState) {
                    lastState = state
                    lastChange = System.currentTimeMillis()
                }
                when {
                    job.isCompleted -> finish()
                    job.isFailed -> askFailed(tag, "the print service reported an error", retry)
                    job.isCancelled -> statusTv.text = "Print cancelled$tag"
                    state != PrintJobInfo.STATE_CREATED && System.currentTimeMillis() - lastChange > 90000 ->
                        askDidItPrint(tag, finish, retry)
                    else -> {
                        statusTv.text = when {
                            job.isBlocked -> "Printer needs attention: paper, ink or cover?$tag"
                            job.isStarted -> "Printing…$tag"
                            state == PrintJobInfo.STATE_CREATED -> "Choose your printer on the print screen…$tag"
                            else -> "Waiting for the printer…$tag"
                        }
                        ui.postDelayed(this, 1000)
                    }
                }
            }
        }
        ui.postDelayed(r, 1000)
    }

    private class FileAdapter(val file: File, val name: String, val pages: Int) : PrintDocumentAdapter() {
        override fun onLayout(
            oldAttributes: PrintAttributes?,
            newAttributes: PrintAttributes,
            cancellationSignal: CancellationSignal?,
            callback: LayoutResultCallback,
            extras: Bundle?
        ) {
            if (cancellationSignal?.isCanceled == true) {
                callback.onLayoutCancelled()
                return
            }
            val info = PrintDocumentInfo.Builder(name)
                .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                .setPageCount(pages)
                .build()
            callback.onLayoutFinished(info, true)
        }

        override fun onWrite(
            pages: Array<out PageRange>,
            destination: ParcelFileDescriptor,
            cancellationSignal: CancellationSignal?,
            callback: WriteResultCallback
        ) {
            try {
                FileInputStream(file).use { i ->
                    FileOutputStream(destination.fileDescriptor).use { o -> i.copyTo(o) }
                }
                callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
            } catch (e: Exception) {
                callback.onWriteFailed(e.message)
            }
        }
    }

    // ---------------------------------------------------------------- scanning

    private fun updScanInfo() {
        scanInfo.text = if (scans.isEmpty()) "No pages scanned yet."
        else scans.size.toString() + " page" + (if (scans.size == 1) "" else "s") +
            " scanned. Put the next page on the glass and tap Scan again, or save."
    }

    private fun doScan() {
        if (ip.isEmpty()) {
            toast("Select your printer first")
            return
        }
        val color = scanColorSp.selectedItemPosition == 0
        val dpi = intArrayOf(150, 300, 600)[scanDpiSp.selectedItemPosition]
        val target = ip
        scanBtn.isEnabled = false
        scanInfo.text = "Scanning… (page face down on the glass)"
        bg {
            try {
                val b = Escl.scan(target, Net.wifi(this), color, dpi)
                ui.post {
                    scans.add(b)
                    scanBtn.isEnabled = true
                    updScanInfo()
                }
            } catch (e: Exception) {
                ui.post {
                    scanBtn.isEnabled = true
                    scanInfo.text = "Scan failed: " + (e.message ?: e.javaClass.simpleName)
                }
            }
        }
    }

    private fun saveScans(asPdf: Boolean) {
        if (scans.isEmpty()) {
            toast("Scan a page first")
            return
        }
        val copy = ArrayList(scans)
        bg {
            try {
                val t = stamp()
                val dest: String
                if (asPdf) {
                    val name = "Scan_$t.pdf"
                    Store.save(this, name, "application/pdf", "Download/QuickPrint",
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI, PdfTools.jpegsToPdf(copy))
                    dest = "Downloads/QuickPrint/$name"
                } else {
                    copy.forEachIndexed { i, b ->
                        Store.save(this, "Scan_${t}_${i + 1}.jpg", "image/jpeg", "Pictures/QuickPrint",
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, b)
                    }
                    dest = "Pictures/QuickPrint"
                }
                ui.post {
                    scans.clear()
                    scanInfo.text = "Saved to $dest"
                }
            } catch (e: Exception) {
                ui.post { scanInfo.text = "Couldn't save: " + (e.message ?: e.javaClass.simpleName) }
            }
        }
    }
}
