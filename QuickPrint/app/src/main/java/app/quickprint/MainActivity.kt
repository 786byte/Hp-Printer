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
import android.provider.OpenableColumns
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
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

class MainActivity : Activity(), PrintQueue.Listener {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var prefs: SharedPreferences
    private lateinit var disc: Discovery

    private var ip = ""
    private var printerName = ""
    private val uris = ArrayList<Uri>()
    private val scans = ArrayList<ByteArray>()
    private var foundListener: ((String, String) -> Unit)? = null
    private var detectedPaper: Paper? = null

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
    private lateinit var turnCb: CheckBox
    private lateinit var queueSummary: TextView
    private lateinit var queueBar: ProgressBar
    private lateinit var queueList: LinearLayout
    private lateinit var queueStartBtn: Button
    private var decisionDlg: AlertDialog? = null
    /** True while a print is being sent/followed (hides the "Unfinished print" card). */
    private var jobRunning = false
    private lateinit var resumeCard: LinearLayout
    private lateinit var resumeTv: TextView
    private lateinit var resumeBtn: Button

    private val papers = Paper.values()
    private val nups = intArrayOf(1, 2, 3, 4, 6, 9)
    private val dpis = intArrayOf(130, 200, 300)

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("qp", Context.MODE_PRIVATE)
        CrashSaver.install(applicationContext)
        ip = prefs.getString("ip", "") ?: ""
        printerName = prefs.getString("name", "") ?: ""
        disc = Discovery(this) { name, addr -> foundListener?.invoke(name, addr) }
        buildUi()
        updStats()
        handleIntent(intent)
        CrashSaver.lastCrash(this)?.let { showCrash(it) }
    }

    private fun showCrash(text: String) {
        AlertDialog.Builder(this)
            .setTitle("Quick Print closed unexpectedly last time")
            .setMessage("Sorry about that. If it keeps happening, copy the details and send them so it can be fixed.\n\n" +
                text.take(600))
            .setPositiveButton("Copy details") { _, _ ->
                val cm = getSystemService(android.content.ClipboardManager::class.java)
                cm?.setPrimaryClip(android.content.ClipData.newPlainText("Quick Print crash", text))
                CrashSaver.clear(this)
                toast("Copied")
            }
            .setNegativeButton("OK") { _, _ -> CrashSaver.clear(this) }
            .safeShow()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        PrintQueue.listener = this
        renderQueue()
        PrintQueue.waitingFor?.let { showDecision(it) }
        updResumeCard()
        refreshInk()
    }

    override fun onDestroy() {
        disc.stop()
        if (PrintQueue.listener === this) PrintQueue.listener = null
        decisionDlg?.dismiss()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- helpers

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    /** Runs [block] off the main thread. Any error is shown instead of crashing the app. */
    private fun bg(block: () -> Unit) = Thread {
        try {
            block()
        } catch (t: Throwable) {
            ui.post {
                if (::printBtn.isInitialized && !jobRunning) printBtn.isEnabled = true
                if (::statusTv.isInitialized) statusTv.text = "Something went wrong: " + (t.message ?: t.javaClass.simpleName)
            }
        }
    }.start()

    /** Shows a dialog only while this screen exists (a late dialog on a closed screen crashes Android apps). */
    private fun AlertDialog.Builder.safeShow(): AlertDialog? =
        if (isFinishing || isDestroyed) null else try { show() } catch (e: Exception) { null }
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

        // Unfinished print (shown only when there is one)
        resumeCard = card("Unfinished print")
        resumeTv = label("", 14f)
        resumeCard.addView(resumeTv)
        resumeBtn = button("Resume", true) { resumeSaved(false) }
        resumeCard.addView(resumeBtn)
        resumeCard.addView(row(
            button("Start over") { resumeSaved(true) },
            button("Discard") {
                AlertDialog.Builder(this)
                    .setTitle("Discard the unfinished print?")
                    .setPositiveButton("Discard") { _, _ -> Resume.clear(this); updResumeCard() }
                    .setNegativeButton("Keep", null)
                    .safeShow()
            }
        ))
        resumeCard.visibility = View.GONE

        // Printer + ink
        val pc = card("Printer & ink")
        printerTv = label("No printer selected", 15f)
        pc.addView(printerTv)
        pc.addView(row(
            button("Find printer") { findPrinter() },
            button("Enter IP") { enterIp() },
            button("Refresh") { refreshInk() }
        ))
        pc.addView(button("Printer's own Wi-Fi (Wi-Fi Direct)…") { printerWifi() })
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

        turnCb = CheckBox(this)
        turnCb.text = "Turn back sides upside down (set this with the test page)"
        turnCb.isChecked = prefs.getBoolean("turnBacks", false)
        turnCb.setOnCheckedChangeListener { _, v -> prefs.edit().putBoolean("turnBacks", v).apply() }
        cc.addView(turnCb)
        cc.addView(button("Print a double-sided test (4 pages)") { duplexTest() })

        printBtn = button("Print", true) { doPrint() }
        val plp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        plp.topMargin = dp(8)
        cc.addView(printBtn, plp)
        cc.addView(button("Add to queue (one job per file)") { addToQueue() })
        cc.addView(label("\"Direct to printer\" sends the job itself over Wi-Fi, so it works with mobile data on " +
            "and shows real progress. If a job fails, the app asks you before trying again.", 12f, grey))

        // Queue
        val qc = card("Print queue")
        queueSummary = label("", 14f)
        qc.addView(queueSummary)
        queueBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        qc.addView(queueBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(10)))
        queueList = LinearLayout(this)
        queueList.orientation = LinearLayout.VERTICAL
        qc.addView(queueList)
        queueStartBtn = button("Start", true) { toggleQueue() }
        qc.addView(row(queueStartBtn, button("Clear finished") { PrintQueue.clearFinished() }))
        qc.addView(label("Jobs print one after another. If one fails or the app can't tell whether it printed, " +
            "the queue stops and asks you — it never retries by itself.", 12f, grey))

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
                .safeShow()
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
            .safeShow()
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
        if (!refreshing.compareAndSet(false, true)) return
        inkNote.text = "Checking…"
        bg { try { refreshNow() } finally { refreshing.set(false) } }
    }

    private val refreshing = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun refreshNow() {
        val target = locate { m -> ui.post { inkNote.text = m } }
        if (target == null) {
            ui.post {
                inkBox.removeAllViews()
                inkNote.text = "Can't find the printer. Connect the phone to the home Wi-Fi, or to the printer's own " +
                    "Wi-Fi (DIRECT-…) with the button above. Mobile data can stay on."
            }
            return
        }
        val net = Net.forIp(this, target)
        val st = try { Ipp.status(target, net) } catch (e: Exception) { null }
        if (st == null) {
            ui.post { inkNote.text = "The printer at $target didn't answer. Is it busy or waking up? Tap Refresh." }
            return
        }
        Locator.remember(this, target, st.model)
        val via = Net.describe(this, target)
        ui.post { if (target == ip) { showStatus(st); inkNote.append("  ·  via $via") } }
        val u = try { Ledm.usage(target, net) } catch (e: Exception) { null }
        ui.post { showUsage(u) }
    }

    /** Join the printer's Wi-Fi Direct from the app, keeping mobile data (and on some phones home Wi-Fi) on. */
    private fun printerWifi() {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(20), dp(8), dp(20), 0)
        val ssid = EditText(this)
        ssid.hint = "Network name, e.g. DIRECT-39-HP Smart Tank 580"
        ssid.setText(prefs.getString("direct_ssid", "") ?: "")
        val pass = EditText(this)
        pass.hint = "Password (printer's Wi-Fi Direct page / report)"
        pass.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        pass.setText(prefs.getString("direct_pass", "") ?: "")
        box.addView(ssid)
        box.addView(pass)
        val connected = PrinterWifi.network != null
        AlertDialog.Builder(this)
            .setTitle("Printer's own Wi-Fi")
            .setMessage("Quick Print joins the printer's Wi-Fi only for printing. Mobile data stays on for the internet, " +
                "and phones that support two Wi-Fi connections stay on your home Wi-Fi too.\n\n" +
                "Find the name and password on the printer (Wi-Fi Direct button / network report)." +
                if (connected) "\n\nStatus: connected." else "")
            .setView(box)
            .setPositiveButton("Connect") { _, _ ->
                val n = ssid.text.toString().trim()
                val pw = pass.text.toString()
                if (n.isEmpty()) { toast("Enter the network name"); return@setPositiveButton }
                prefs.edit().putString("direct_ssid", n).putString("direct_pass", pw).apply()
                statusTv.text = "Joining $n… (approve Android's prompt)"
                PrinterWifi.connect(applicationContext, n, pw) { ok, msg ->
                    ui.post {
                        statusTv.text = msg
                        if (ok) refreshInk()
                    }
                }
            }
            .setNeutralButton(if (connected) "Disconnect" else "Cancel") { _, _ ->
                if (connected) { PrinterWifi.disconnect(applicationContext); statusTv.text = "Left the printer's Wi-Fi"; refreshInk() }
            }
            .safeShow()
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
                .safeShow()
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
            uris.joinToString(", ") { nameOf(it) }
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
            evenPad = false,
            turnBacks = turnCb.isChecked
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
                .safeShow()
        } else {
            startPrint()
        }
    }

    private fun startPrint() {
        prefs.edit().putInt("send", sendSp.selectedItemPosition).apply()
        val list = ArrayList(uris)
        val names = list.map { nameOf(it) }
        val mode = modeSp.selectedItemPosition
        val base = readOpts()
        // Guided double-sided must keep fronts and backs in step, so it prints one copy.
        val copies = if (mode == 3) 1 else readCopies()
        val quality = intArrayOf(3, 4, 5)[qualSp.selectedItemPosition]
        val det = detectedPaper
        printBtn.isEnabled = false
        statusTv.text = "Getting the files ready…"
        bg {
            // Copy into the app first: a resume after a restart needs the files, and shared-file access expires.
            val staged = Resume.stage(this, list, names)
            ui.post { launch(PrintJobSpec(staged, names, base, copies, quality, det, mode, 1), 0) }
        }
    }

    /** Runs one pass of [spec], leaving out the first [skip] sheets because they already printed. */
    private fun launch(spec: PrintJobSpec, skip: Int) {
        Resume.begin(this, spec, skip)
        jobRunning = true
        updResumeCard()
        val o = spec.opts(skip)
        val tag = tagOf(spec.what) + if (skip > 0) " — from sheet ${skip + 1}" else ""
        if (sendSp.selectedItemPosition == 0 && ip.isNotEmpty()) runDirect(spec, o, skip, tag)
        else runAndroid(spec, o, skip, tag)
    }

    private fun jobEnded() {
        jobRunning = false
        printBtn.isEnabled = true
        updResumeCard()
    }

    /** A pass printed completely: finish, or move on to the backs of a guided double-sided job. */
    private fun passDone(spec: PrintJobSpec, total: Int) {
        Resume.clear(this)
        jobEnded()
        refreshInk()
        if (spec.mode != 3) { statusTv.text = "Printed ✓${tagOf(spec.what)}"; return }
        if (spec.side == 2) { statusTv.text = "Double-sided print finished ✓"; return }
        if (total < 2) { statusTv.text = "Done. The document has only one sheet, so there is no back side."; return }
        // Remember that the backs are still to do, so closing the app doesn't lose the second step.
        val backs = spec.copy(side = 2)
        Resume.begin(this, backs, 0)
        updResumeCard()
        statusTv.text = "Side 1 printed — waiting for you to reload the paper"
        AlertDialog.Builder(this)
            .setTitle("Side 1 printed")
            .setMessage(
                "1. Take the printed pages out of the output tray. Keep them in the same order.\n" +
                    "2. Put the stack back in the paper tray so the BLANK sides get printed. Don't turn it around.\n" +
                    "3. Slide the paper guides against the stack.\n\n" +
                    "First time? Use \"Print a double-sided test\". If the backs come out upside down, tick " +
                    "\"Turn back sides upside down\"; if they land on the wrong sheets, change \"Even-page order\"."
            )
            .setCancelable(false)
            .setPositiveButton("Print other side") { _, _ -> launch(backs, 0) }
            .setNegativeButton("Later") { _, _ -> statusTv.text = "Side 2 saved — print it any time from \"Unfinished print\"" }
            .safeShow()
    }

    private fun tagOf(what: String) = if (what.isEmpty()) "" else " ($what)"

    private fun readCopies() = (copiesEt.text.toString().toIntOrNull() ?: 1).coerceIn(1, 99)

    // ---------- direct to printer (IPP over Wi-Fi)

    /** Background thread: where the printer answers right now; switches the saved address if it moved. */
    private fun locate(progress: (String) -> Unit): String? {
        val current = ip
        val found = Locator.find(this, current, printerName, progress) ?: return null
        if (found != current) ui.post {
            ip = found
            prefs.edit().putString("ip", found).apply()
            printerTv.text = (if (printerName.isEmpty()) "Printer" else printerName) + "  ·  " + found
            toast("Printer found at $found (${Net.describe(this, found)})")
        }
        return found
    }

    private fun runDirect(spec: PrintJobSpec, o: Opts, skip: Int, tag: String) {
        printBtn.isEnabled = false
        statusTv.text = "Finding the printer…$tag"
        bg {
            val target = locate { m -> ui.post { statusTv.text = m } }
            if (target == null) {
                ui.post {
                    jobEnded()
                    askResume(spec, "Can't find the printer on this Wi-Fi. Connect the phone to the home Wi-Fi or the " +
                        "printer's own Wi-Fi (DIRECT-…), then try again.", skip, 0, true, skip)
                }
                return@bg
            }
            val out = DirectPrint.run(this, target, spec.files, o, spec.copies, spec.quality, spec.detected,
                "Quick Print — ${spec.title}", { m -> ui.post { statusTv.text = m + tag } }, { n -> Resume.progress(this, n) })
            ui.post { handle(spec, skip, tag, out) }
        }
    }

    private fun handle(spec: PrintJobSpec, skip: Int, tag: String, out: Outcome) {
        when (out) {
            is Outcome.Done -> {
                Stats.add(this, out.inked, out.color)
                updStats()
                passDone(spec, out.total)
            }
            is Outcome.Cancelled -> {
                jobEnded()
                Resume.failed(this, "Cancelled on the printer")
                statusTv.text = "Cancelled on the printer$tag — you can resume it from \"Unfinished print\""
            }
            is Outcome.NoDirect -> {
                toast("This printer doesn't take direct jobs. Using the Android print screen.")
                runAndroid(spec, spec.opts(skip), skip, tag)
            }
            is Outcome.Failed -> {
                jobEnded()
                val printed = out.printed
                if (printed != null && printed > 0) { Stats.add(this, printed, out.color); updStats() }
                Resume.sent(this, out.sent)
                Resume.failed(this, out.why)
                updResumeCard()
                askResume(spec, out.why, skip + (printed ?: 0), skip + out.sent, printed != null, skip + (printed ?: 0))
            }
            is Outcome.Unclear -> {
                jobEnded()
                Resume.sent(this, out.sent)
                Resume.failed(this, out.why)
                updResumeCard()
                askUnclear(spec, out.why, skip, out.printed, out.sent, out.inked, out.color, out.total)
            }
        }
    }

    /**
     * A pass stopped part-way. [done] = sheets of this pass known to be printed, [passTotal] = sheets in the pass
     * (0 = unknown), [counted] = how many of them the counters already include.
     */
    private fun askResume(spec: PrintJobSpec, why: String, done: Int, passTotal: Int, known: Boolean, counted: Int) {
        val tag = tagOf(spec.what)
        val of = if (passTotal > 0) " of $passTotal" else ""
        statusTv.text = "Stopped$tag — $done$of sheets printed"
        val msg = why + "\n\n" + when {
            known && done > 0 -> "Sheets 1–$done$of are printed. Resuming prints only the rest."
            known -> "Nothing of this job has printed yet."
            else -> "The printer didn't say how many sheets came out. Count the fully printed sheets in the output tray."
        } + "\n\nThe app never retries by itself."
        val b = AlertDialog.Builder(this).setTitle("Printing stopped").setMessage(msg).setCancelable(false)
        when {
            !known -> b.setPositiveButton("Count & resume…") { _, _ -> askCount(spec, done, passTotal, counted) }
            done > 0 && (passTotal == 0 || done < passTotal) ->
                b.setPositiveButton("Resume from sheet ${done + 1}") { _, _ -> launch(spec, done) }
            else -> b.setPositiveButton("Try again") { _, _ -> launch(spec, done) }
        }
        if (known) b.setNeutralButton("Other…") { _, _ -> askCount(spec, done, passTotal, counted) }
        b.setNegativeButton("Later") { _, _ -> statusTv.text = "Saved — resume any time from \"Unfinished print\"" }
        b.safeShow()
    }

    private fun askUnclear(spec: PrintJobSpec, why: String, skip: Int, printed: Int?, sent: Int, inked: Int, color: Boolean, total: Int) {
        val tag = tagOf(spec.what)
        statusTv.text = "Not sure if it printed$tag"
        val last = if (printed != null) "\n\nThe printer last confirmed ${skip + printed} of ${skip + sent} sheets." else ""
        AlertDialog.Builder(this)
            .setTitle("Did it print?")
            .setMessage("$why$last\n\nCheck the output tray. Did every sheet of this job$tag come out?")
            .setCancelable(false)
            .setPositiveButton("Yes, all of it") { _, _ ->
                Stats.add(this, inked, color)
                updStats()
                passDone(spec, total)
            }
            .setNeutralButton("No / only some…") { _, _ -> askCount(spec, skip + (printed ?: 0), skip + sent, skip) }
            .setNegativeButton("Later") { _, _ -> statusTv.text = "Saved — resume any time from \"Unfinished print\"" }
            .safeShow()
    }

    /** Lets you say how many sheets came out, then resumes after them (or starts over). */
    private fun askCount(spec: PrintJobSpec, suggested: Int, passTotal: Int, counted: Int) {
        val et = EditText(this)
        et.inputType = InputType.TYPE_CLASS_NUMBER
        et.setText(suggested.toString())
        et.setSelection(et.text.length)
        val of = if (passTotal > 0) " (of $passTotal)" else ""
        AlertDialog.Builder(this)
            .setTitle("How many sheets came out?")
            .setMessage("Count the fully printed sheets of this job$of in the output tray. " +
                "A half-printed or smudged sheet doesn't count — it will be printed again.")
            .setView(et)
            .setCancelable(false)
            .setPositiveButton("Resume after these") { _, _ ->
                var n = (et.text.toString().toIntOrNull() ?: 0).coerceAtLeast(0)
                if (passTotal > 0) n = n.coerceAtMost(passTotal)
                if (n > counted) { Stats.add(this, n - counted, !spec.base.gray); updStats() }
                launch(spec, n)
            }
            .setNeutralButton("Start over") { _, _ -> launch(spec, 0) }
            .setNegativeButton("Cancel") { _, _ -> statusTv.text = "Saved — resume any time from \"Unfinished print\"" }
            .safeShow()
    }

    // ---------- unfinished print (survives failures, crashes and restarts)

    private fun updResumeCard() {
        if (!::resumeCard.isInitialized) return
        val saved = if (jobRunning) null else Resume.load(this)
        if (saved == null) { resumeCard.visibility = View.GONE; return }
        resumeCard.visibility = View.VISIBLE
        val of = if (saved.passTotal > 0) " of ${saved.passTotal}" else ""
        resumeTv.text = buildString {
            append(saved.spec.title)
            if (saved.spec.what.isNotEmpty()) append("  ·  ").append(saved.spec.what)
            append("\n").append("${saved.done}$of sheets printed")
            if (saved.why.isNotEmpty()) append("\nLast problem: ").append(saved.why)
        }
        resumeBtn.text = if (saved.done > 0) "Resume from sheet ${saved.done + 1}" else "Print it"
    }

    private fun resumeSaved(fromStart: Boolean) {
        val saved = Resume.load(this) ?: return updResumeCard()
        launch(saved.spec, if (fromStart) 0 else saved.done)
    }

    // ---------- Android print screen

    private fun runAndroid(spec: PrintJobSpec, o: Opts, skip: Int, tag: String) {
        printBtn.isEnabled = false
        statusTv.text = "Preparing…$tag"
        bg {
            var prep: Prepared? = null
            try {
                val p = PdfTools.prepare(this, spec.files, o)
                prep = p
                val out = File(cacheDir, "job_" + System.nanoTime() + ".pdf")
                PdfTools.writePdf(p, out, skip) { i, n -> ui.post { statusTv.text = "Preparing $i / $n…$tag" } }
                val seq = p.sequence(1, skip)
                val res = BuildResult(seq.count { it >= 0 }, p.total)
                ui.post { submit(out, res, o, spec, skip, tag, seq.size) }
            } catch (e: Throwable) {
                ui.post {
                    jobEnded()
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

    private fun submit(file: File, res: BuildResult, o: Opts, spec: PrintJobSpec, skip: Int, tag: String, sheets: Int) {
        val pm = getSystemService(Context.PRINT_SERVICE) as PrintManager
        val attrs = PrintAttributes.Builder()
            .setMediaSize(mediaOf(o))
            .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
            .setColorMode(if (o.gray) PrintAttributes.COLOR_MODE_MONOCHROME else PrintAttributes.COLOR_MODE_COLOR)
            .build()
        val job: PrintJob? = try {
            pm.print("Quick Print " + stamp(), FileAdapter(file, "QuickPrint.pdf", sheets), attrs)
        } catch (e: Exception) {
            null
        }
        if (job == null) {
            jobEnded()
            statusTv.text = "Couldn't open the print screen."
            return
        }
        watch(job, res, spec, skip, tag, sheets)
    }

    /** Polls the print job. On failure, or if it stops updating, it asks you; it never retries by itself. */
    private fun watch(job: PrintJob, res: BuildResult, spec: PrintJobSpec, skip: Int, tag: String, sheets: Int) {
        var lastState = -1
        var lastChange = System.currentTimeMillis()
        var color = true
        var copies = 1
        val r = object : Runnable {
            override fun run() {
                if (isDestroyed) return
                val info = job.info
                copies = maxOf(1, info.copies)
                color = info.attributes.colorMode != PrintAttributes.COLOR_MODE_MONOCHROME
                val state = info.state
                if (state != lastState) {
                    lastState = state
                    lastChange = System.currentTimeMillis()
                }
                when {
                    job.isCompleted -> {
                        Stats.add(this@MainActivity, res.printed * copies, color)
                        updStats()
                        passDone(spec, res.total)
                    }
                    job.isFailed -> {
                        jobEnded()
                        Resume.sent(this@MainActivity, sheets)
                        Resume.failed(this@MainActivity, "The print service reported an error")
                        askResume(spec, "The print service reported an error.", skip, skip + sheets, false, skip)
                    }
                    job.isCancelled -> {
                        jobEnded()
                        statusTv.text = "Print cancelled$tag"
                    }
                    state != PrintJobInfo.STATE_CREATED && System.currentTimeMillis() - lastChange > 90000 -> {
                        jobEnded()
                        Resume.sent(this@MainActivity, sheets)
                        askUnclear(spec, "The print service hasn't reported progress for 90 seconds.", skip, null,
                            sheets, res.printed * copies, color, res.total)
                    }
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

    // ---------------------------------------------------------------- file names

    private fun nameOf(u: Uri): String {
        val n = try {
            contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        } catch (e: Exception) {
            null
        }
        return n ?: u.lastPathSegment?.substringAfterLast('/') ?: "file"
    }

    // ---------------------------------------------------------------- double-sided test

    /** Prints a 4-page test through the guided double-sided flow, so you can see how your printer flips paper. */
    private fun duplexTest() {
        val f = File(cacheDir, "duplex_test.pdf")
        val doc = PdfDocument()
        try {
            val big = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 160f; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER }
            val mid = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 30f; textAlign = Paint.Align.CENTER }
            val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 16f; textAlign = Paint.Align.CENTER; color = Color.DKGRAY }
            for (n in 1..4) {
                val pg = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, n).create())
                val c = pg.canvas
                c.drawPath(Path().apply { moveTo(297f, 70f); lineTo(237f, 160f); lineTo(357f, 160f); close() }, mid)
                c.drawRect(282f, 160f, 312f, 260f, mid)
                c.drawText("TOP", 297f, 300f, mid)
                c.drawText("$n", 297f, 520f, big)
                c.drawText(if (n % 2 == 1) "front of sheet ${(n + 1) / 2}" else "back of sheet ${n / 2}", 297f, 580f, mid)
                c.drawText("Correct when: page 2 is on the back of page 1, page 4 on the back of page 3,", 297f, 700f, small)
                c.drawText("and both sides have the arrow at the same edge (flip like a book).", 297f, 722f, small)
                doc.finishPage(pg)
            }
            f.outputStream().use { doc.writeTo(it) }
        } finally {
            doc.close()
        }
        uris.clear()
        uris.add(Uri.fromFile(f))
        updFiles()
        modeSp.setSelection(3)
        nupSp.setSelection(0)
        flipSp.setSelection(0)
        grayCb.isChecked = true
        qualSp.setSelection(0)
        rangeEt.setText("")
        toast("Test loaded. Load 2 sheets of plain paper, then tap Print.")
    }

    // ---------------------------------------------------------------- queue

    private fun addToQueue() {
        if (uris.isEmpty()) { toast("Choose one or more files first"); return }
        if (ip.isEmpty()) { toast("Select your printer first"); return }
        if (modeSp.selectedItemPosition == 3) {
            toast("Guided double-sided needs you between the two sides, so print it with the Print button instead.")
            return
        }
        val base = readOpts().copy(pages = modeSp.selectedItemPosition)
        val copies = readCopies()
        val quality = intArrayOf(3, 4, 5)[qualSp.selectedItemPosition]
        val files = ArrayList(uris)
        statusTv.text = "Checking ${files.size} file${if (files.size == 1) "" else "s"}…"
        bg {
            val checked = files.map { u ->
                val name = nameOf(u)
                // Copy each file into the app so the queue can still read it later (and after a failure).
                val staged = Resume.stage(this, listOf(u), listOf(name)).first()
                val det = if (PdfTools.isPdf(this, staged)) (try { PdfTools.detect(this, staged)?.paper } catch (e: Exception) { null }) else null
                Triple(staged, name, det)
            }
            ui.post { askPapers(checked, 0, base, copies, quality, 0) }
        }
    }

    /** Adds files one by one; asks about each PDF whose page size differs from the chosen paper. */
    private fun askPapers(list: List<Triple<Uri, String, Paper?>>, i: Int, base: Opts, copies: Int, quality: Int, added: Int) {
        if (i >= list.size) {
            statusTv.text = "Added $added job${if (added == 1) "" else "s"} to the queue"
            if (added > 0 && !PrintQueue.running && PrintQueue.waitingFor == null) PrintQueue.start(this, ip)
            return
        }
        val (u, name, det) = list[i]
        val add = { paper: Paper ->
            PrintQueue.add(name, listOf(u), base.copy(paper = paper), copies, quality, det)
            askPapers(list, i + 1, base, copies, quality, added + 1)
        }
        if (det != null && det != base.paper) {
            AlertDialog.Builder(this)
                .setTitle("Paper size check")
                .setMessage("\"$name\" is ${det.label} size, but ${base.paper.label} is selected.\n\nPrint it on ${det.label} or ${base.paper.label}?")
                .setCancelable(false)
                .setPositiveButton(det.label) { _, _ -> add(det) }
                .setNegativeButton(base.paper.label) { _, _ -> add(base.paper) }
                .setNeutralButton("Don't add") { _, _ -> askPapers(list, i + 1, base, copies, quality, added) }
                .safeShow()
        } else {
            add(base.paper)
        }
    }

    private fun toggleQueue() {
        if (PrintQueue.running) { PrintQueue.stop(); return }
        PrintQueue.waitingFor?.let { showDecision(it); return }
        if (ip.isEmpty()) { toast("Select your printer first"); return }
        if (PrintQueue.items.none { it.state == QState.WAITING }) { toast("No jobs waiting"); return }
        PrintQueue.start(this, ip)
    }

    override fun onQueueChanged() = renderQueue()

    override fun onDecisionNeeded(item: QItem) = showDecision(item)

    private fun renderQueue() {
        if (!::queueList.isInitialized) return
        val items = PrintQueue.items
        queueSummary.text = PrintQueue.summary()
        val finished = items.count { it.state != QState.WAITING && it.state != QState.WORKING }
        queueBar.max = maxOf(1, items.size)
        queueBar.progress = finished
        queueBar.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        queueStartBtn.text = when {
            PrintQueue.running && PrintQueue.stopAfterCurrent -> "Stopping…"
            PrintQueue.running -> "Stop after this job"
            PrintQueue.waitingFor != null -> "Answer the question"
            else -> "Start"
        }
        queueList.removeAllViews()
        items.forEachIndexed { idx, it -> queueList.addView(queueRow(idx + 1, it)) }
    }

    private fun queueRow(n: Int, it: QItem): View {
        val r = LinearLayout(this)
        r.orientation = LinearLayout.HORIZONTAL
        r.gravity = Gravity.CENTER_VERTICAL
        r.setPadding(0, dp(8), 0, dp(8))

        val badge = label(it.state.badge, 12f, Color.WHITE)
        val bd = GradientDrawable()
        bd.setColor(it.state.color)
        bd.cornerRadius = dp(10).toFloat()
        badge.background = bd
        badge.setPadding(dp(8), dp(3), dp(8), dp(3))
        badge.gravity = Gravity.CENTER
        r.addView(badge, LinearLayout.LayoutParams(dp(92), ViewGroup.LayoutParams.WRAP_CONTENT))

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(10), 0, dp(4), 0)
        val title = label("$n. ${it.name}", 14f)
        title.maxLines = 2
        col.addView(title)
        val detail = buildString {
            append(it.opts.paper.label)
            if (it.opts.gray) append(" · B/W")
            if (it.copies > 1) append(" · ${it.copies} copies")
            if (it.opts.pages == 1) append(" · odd pages") else if (it.opts.pages == 2) append(" · even pages")
            if (it.msg.isNotEmpty()) append("\n").append(it.msg)
        }
        col.addView(label(detail, 12f, if (it.state == QState.FAILED) Color.parseColor("#D93025") else grey))
        r.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        when (it.state) {
            QState.WAITING -> r.addView(button("Remove") { PrintQueue.remove(it) })
            QState.FAILED, QState.UNCLEAR -> r.addView(button(if (it === PrintQueue.waitingFor) "Answer" else "Retry…") {
                if (it === PrintQueue.waitingFor) showDecision(it) else confirmRequeue(it)
            })
            else -> Unit
        }
        return r
    }

    private fun confirmRequeue(it: QItem) {
        val from = it.resumeFrom
        val go = { resume: Boolean ->
            PrintQueue.requeue(it, resume)
            if (!PrintQueue.running && PrintQueue.waitingFor == null && ip.isNotEmpty()) PrintQueue.start(this, ip)
        }
        val b = AlertDialog.Builder(this)
            .setTitle("Print \"${it.name}\" again?")
            .setMessage(if (from > 0) "Sheets 1–$from already printed. Resume prints only the rest; Start over prints everything."
                else "It goes back in the queue with the same settings. Check the output tray first.")
            .setNegativeButton("Cancel", null)
        if (from > 0) {
            b.setPositiveButton("Resume from sheet ${from + 1}") { _, _ -> go(true) }
            b.setNeutralButton("Start over") { _, _ -> go(false) }
        } else {
            b.setPositiveButton("Print again") { _, _ -> go(false) }
        }
        b.safeShow()
    }

    /** Asks what to do with a failed or unclear queue job. The queue waits until you answer. */
    private fun showDecision(item: QItem) {
        if (isFinishing || isDestroyed || decisionDlg?.isShowing == true) return
        val pos = PrintQueue.items.indexOf(item) + 1
        val total = PrintQueue.items.size
        val b = AlertDialog.Builder(this).setCancelable(false)
        if (item.state == QState.UNCLEAR) {
            b.setTitle("Did job $pos of $total print?")
                .setMessage("${item.name}\n\n${item.msg}\n\nCheck the output tray. Did all its pages come out?")
                .setPositiveButton("Yes, it printed") { _, _ -> PrintQueue.decide(item, PrintQueue.Decision.PRINTED) }
            if (item.resumeFrom > 0) b.setNeutralButton("No, print the rest") { _, _ -> PrintQueue.decide(item, PrintQueue.Decision.RESUME) }
            else b.setNeutralButton("No, print it again") { _, _ -> PrintQueue.decide(item, PrintQueue.Decision.RETRY) }
            b
                .setNegativeButton("No, skip it") { _, _ -> PrintQueue.decide(item, PrintQueue.Decision.SKIP) }
        } else {
            b.setTitle("Job $pos of $total failed")
                .setMessage("${item.name}\n\n${item.msg}\n\nFix the problem on the printer, then choose what to do.")
            if (item.resumeFrom > 0) b.setPositiveButton("Resume from sheet ${item.resumeFrom + 1}") { _, _ -> PrintQueue.decide(item, PrintQueue.Decision.RESUME) }
            else b.setPositiveButton("Retry this job") { _, _ -> PrintQueue.decide(item, PrintQueue.Decision.RETRY) }
            b.setNeutralButton("Stop the queue") { _, _ -> PrintQueue.decide(item, PrintQueue.Decision.STOP) }
                .setNegativeButton("Skip it, go on") { _, _ -> PrintQueue.decide(item, PrintQueue.Decision.SKIP) }
        }
        decisionDlg = b.safeShow()
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
        scanBtn.isEnabled = false
        scanInfo.text = "Scanning… (page face down on the glass)"
        bg {
            try {
                val at = locate { m -> ui.post { scanInfo.text = m } } ?: throw Exception("Can't find the printer on this Wi-Fi")
                val b = Escl.scan(at, Net.forIp(this, at), color, dpi)
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
