package app.quickprint

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

enum class Paper(val label: String, val w: Int, val h: Int, val media: String) {
    A4("A4", 595, 842, "iso_a4_210x297mm"),
    LEGAL("Legal", 612, 1008, "na_legal_8.5x14in"),
    LETTER("Letter", 612, 792, "na_letter_8.5x11in"),
    A5("A5", 420, 595, "iso_a5_148x210mm"),
    P46("Photo 4×6 in", 288, 432, "na_index-4x6_4x6in"),
    P57("Photo 5×7 in", 360, 504, "na_5x7_5x7in")
}

/** What size the PDF's first page is. paper = null when it isn't a standard size. */
data class Detected(val paper: Paper?, val text: String)

/** A ready-to-draw print job: sheet size in points, which sheets in which order. */
class Prepared(
    val w: Int,
    val h: Int,
    val order: List<Int>,
    val total: Int,
    val original: File?,
    private val drawFn: (Canvas, Int) -> Unit,
    private val closers: List<() -> Unit>
) {
    fun draw(c: Canvas, sheet: Int) = drawFn(c, sheet)
    fun close() {
        for (cl in closers) {
            try { cl() } catch (e: Exception) { }
        }
    }
}

/**
 * pages: 0 = all sheets, 1 = odd sheets only, 2 = even sheets only.
 * evenRev: print the even sheets in reverse order (usual for manual double-sided).
 * evenPad: when sheet count is odd and evenRev is on, start with a blank sheet so the stack lines up.
 * shortEdge: the user flips the paper like a notepad instead of like a book.
 * turnBacks: this printer's paper path needs the backs upside down (found with the double-sided test page).
 * Even sheets are rotated 180° when exactly one of the two is set.
 */
data class Opts(
    val paper: Paper,
    val nup: Int,
    val marginMm: Float,
    val fill: Boolean,
    val gray: Boolean,
    val dpi: Int,
    val range: String,
    val zoom: Float = 1f,
    val trim: Boolean = false,
    val border: Boolean = false,
    val landscape: Boolean = false,
    val pages: Int = 0,
    val evenRev: Boolean = true,
    val shortEdge: Boolean = false,
    val evenPad: Boolean = false,
    val turnBacks: Boolean = false
)

class BuildResult(val printed: Int, val total: Int)

private class Fit(val rot: Boolean, val nw: Float, val nh: Float)

private interface Src {
    fun draw(c: Canvas, cell: RectF, o: Opts, paint: Paint)
}

private fun computeFit(sw: Float, sh: Float, cell: RectF, fill: Boolean, zoom: Float): Fit {
    val cw = cell.width()
    val ch = cell.height()
    val rot = ((sw > sh) != (cw > ch)) && abs(sw / sh - 1f) > 0.05f
    val ew = if (rot) sh else sw
    val eh = if (rot) sw else sh
    val k = (if (fill) max(cw / ew, ch / eh) else min(cw / ew, ch / eh)) * zoom
    val dw = ew * k
    val dh = eh * k
    return Fit(rot, if (rot) dh else dw, if (rot) dw else dh)
}

private fun toGray(src: Bitmap): Bitmap {
    val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
    val p = Paint()
    p.colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
    Canvas(out).drawBitmap(src, 0f, 0f, p)
    return out
}

private fun place(c: Canvas, bmp0: Bitmap, f: Fit, cell: RectF, o: Opts, paint: Paint) {
    val bmp = if (o.gray) toGray(bmp0) else bmp0
    c.save()
    c.clipRect(cell)
    c.translate(cell.centerX(), cell.centerY())
    if (f.rot) c.rotate(90f)
    c.drawBitmap(bmp, null, RectF(-f.nw / 2, -f.nh / 2, f.nw / 2, f.nh / 2), paint)
    c.restore()
    if (bmp !== bmp0) bmp.recycle()
}

private class PdfSrc(val r: PdfRenderer, val index: Int) : Src {

    /** Finds the box (in PDF points) that contains everything that isn't white. */
    private fun contentBox(p: PdfRenderer.Page): RectF {
        val w = p.width
        val h = p.height
        val full = RectF(0f, 0f, w.toFloat(), h.toFloat())
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.WHITE)
        p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        val px = IntArray(w)
        var top = h
        var bottom = -1
        var left = w
        var right = -1
        for (y in 0 until h) {
            bmp.getPixels(px, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                val v = px[x]
                val rr = (v shr 16) and 255
                val gg = (v shr 8) and 255
                val bb = v and 255
                if (rr < 245 || gg < 245 || bb < 245) {
                    if (y < top) top = y
                    if (y > bottom) bottom = y
                    if (x < left) left = x
                    if (x > right) right = x
                }
            }
        }
        bmp.recycle()
        if (bottom < 0) return full
        val pad = 6f
        val box = RectF(
            max(0f, left - pad), max(0f, top - pad),
            min(w.toFloat(), right + 1 + pad), min(h.toFloat(), bottom + 1 + pad)
        )
        return if (box.width() * box.height() > 0.95f * w * h) full else box
    }

    override fun draw(c: Canvas, cell: RectF, o: Opts, paint: Paint) {
        val p = r.openPage(index)
        try {
            val crop = if (o.trim) contentBox(p) else RectF(0f, 0f, p.width.toFloat(), p.height.toFloat())
            val fit = computeFit(crop.width(), crop.height(), cell, o.fill, o.zoom)
            var pw = fit.nw * o.dpi / 72f
            var ph = fit.nh * o.dpi / 72f
            val big = max(pw, ph)
            if (big > 3500f) {
                pw = pw * 3500f / big
                ph = ph * 3500f / big
            }
            val bw = max(1, pw.toInt())
            val bh = max(1, ph.toInt())
            val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            val sx = bw / crop.width()
            val sy = bh / crop.height()
            val m = Matrix()
            m.setScale(sx, sy)
            m.postTranslate(-crop.left * sx, -crop.top * sy)
            p.render(bmp, null, m, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
            place(c, bmp, fit, cell, o, paint)
            bmp.recycle()
        } finally {
            p.close()
        }
    }
}

private class ImgSrc(val ctx: Context, val uri: Uri) : Src {
    override fun draw(c: Canvas, cell: RectF, o: Opts, paint: Paint) {
        val bmp = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri)) { d, info, _ ->
            d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val big = max(info.size.width, info.size.height)
            if (big > 3500) d.setTargetSampleSize(ceil(big / 3500f).toInt())
        }
        val fit = computeFit(bmp.width.toFloat(), bmp.height.toFloat(), cell, o.fill, o.zoom)
        place(c, bmp, fit, cell, o, paint)
        bmp.recycle()
    }
}

object PdfTools {
    /** "1-3,5,8-" -> [1,2,3,5,8,...n]; blank = all. 1-based. */
    fun parseRange(s: String, n: Int): List<Int> {
        val t = s.replace(" ", "")
        if (t.isEmpty()) return (1..n).toList()
        val out = ArrayList<Int>()
        for (part in t.split(",")) {
            if (part.isEmpty()) continue
            if (part.contains("-")) {
                val ab = part.split("-")
                var a = ab.getOrNull(0)?.toIntOrNull() ?: 1
                var b = ab.getOrNull(1)?.toIntOrNull() ?: n
                if (a > b) { val x = a; a = b; b = x }
                for (i in max(1, a)..min(n, b)) out.add(i)
            } else {
                val k = part.toIntOrNull()
                if (k != null && k in 1..n) out.add(k)
            }
        }
        return out
    }

    fun isPdf(ctx: Context, u: Uri): Boolean {
        val type = ctx.contentResolver.getType(u) ?: ""
        return type == "application/pdf" || (u.lastPathSegment ?: "").lowercase().endsWith(".pdf")
    }

    fun detect(ctx: Context, uri: Uri): Detected? {
        val pfd = ctx.contentResolver.openFileDescriptor(uri, "r") ?: return null
        val r = PdfRenderer(pfd)
        try {
            if (r.pageCount == 0) return null
            val pg = r.openPage(0)
            val w = pg.width
            val h = pg.height
            pg.close()
            val a = min(w, h).toFloat()
            val b = max(w, h).toFloat()
            val paper = Paper.values().firstOrNull {
                abs(a - min(it.w, it.h)) / a < 0.025f && abs(b - max(it.w, it.h)) / b < 0.025f
            }
            return Detected(paper, String.format(java.util.Locale.US, "%.1f × %.1f in", w / 72f, h / 72f))
        } finally {
            r.close()
        }
    }

    /** Loads the files and works out the layout. Call close() when done. */
    fun prepare(ctx: Context, uris: List<Uri>, o: Opts): Prepared {
        val srcs = ArrayList<Src>()
        val closers = ArrayList<() -> Unit>()
        try {
            var singlePdf: File? = null
            for ((idx, u) in uris.withIndex()) {
                if (isPdf(ctx, u)) {
                    val f = File(ctx.cacheDir, "src_${System.nanoTime()}_$idx.pdf")
                    val ins = ctx.contentResolver.openInputStream(u) ?: throw Exception("Cannot open file")
                    ins.use { i -> f.outputStream().use { o2 -> i.copyTo(o2) } }
                    val pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
                    val r = PdfRenderer(pfd)
                    closers.add { r.close(); pfd.close(); f.delete() }
                    if (uris.size == 1 && o.range.isBlank()) singlePdf = f
                    for (pg in parseRange(o.range, r.pageCount)) srcs.add(PdfSrc(r, pg - 1))
                } else {
                    srcs.add(ImgSrc(ctx, u))
                }
            }
            if (srcs.isEmpty()) throw Exception("No pages selected")

            val plain = o.pages == 0 && o.nup == 1 && !o.gray && o.zoom == 1f && !o.trim && !o.border
            val original = if (plain) singlePdf else null

            val w = if (o.landscape) o.paper.h else o.paper.w
            val h = if (o.landscape) o.paper.w else o.paper.h
            val m = o.marginMm * 72f / 25.4f
            val g = if (o.nup > 1) 8f else 0f
            var cols: Int
            var rows: Int
            when (o.nup) {
                1 -> { cols = 1; rows = 1 }
                2 -> { cols = 1; rows = 2 }
                3 -> { cols = 1; rows = 3 }
                4 -> { cols = 2; rows = 2 }
                6 -> { cols = 2; rows = 3 }
                else -> { cols = 3; rows = 3 }
            }
            if (o.landscape) { val t = cols; cols = rows; rows = t }
            val cw = (w - 2 * m - (cols - 1) * g) / cols
            val ch = (h - 2 * m - (rows - 1) * g) / rows
            val per = o.nup
            val total = (srcs.size + per - 1) / per

            var order: List<Int> = (0 until total).toList()
            if (o.pages == 1) {
                order = order.filter { it % 2 == 0 }
            } else if (o.pages == 2) {
                var e = order.filter { it % 2 == 1 }
                if (o.evenRev) {
                    e = e.reversed()
                    if (o.evenPad && total % 2 == 1) e = listOf(-1) + e
                }
                order = e
            }
            if (order.isEmpty()) throw Exception("Nothing to print on this side (the document has only one sheet)")

            val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
            val line = Paint()
            line.style = Paint.Style.STROKE
            line.color = Color.rgb(90, 90, 90)
            line.strokeWidth = 0.6f

            val draw: (Canvas, Int) -> Unit = { c, s ->
                c.drawColor(Color.WHITE)
                c.save()
                if (o.pages == 2 && (o.shortEdge != o.turnBacks)) c.rotate(180f, w / 2f, h / 2f)
                if (s >= 0) {
                    for (j in 0 until per) {
                        val k = s * per + j
                        if (k >= srcs.size) break
                        val x = m + (j % cols) * (cw + g)
                        val y = m + (j / cols) * (ch + g)
                        val cell = RectF(x, y, x + cw, y + ch)
                        srcs[k].draw(c, cell, o, paint)
                        if (o.border) c.drawRect(cell, line)
                    }
                }
                c.restore()
            }
            return Prepared(w, h, order, total, original, draw, closers)
        } catch (e: Throwable) {
            for (cl in closers) {
                try { cl() } catch (x: Exception) { }
            }
            throw e
        }
    }

    /** For the Android print screen. */
    fun writePdf(p: Prepared, out: File, progress: (Int, Int) -> Unit) {
        val orig = p.original
        if (orig != null) {
            orig.copyTo(out, overwrite = true)
            return
        }
        val doc = PdfDocument()
        try {
            for ((n, s) in p.order.withIndex()) {
                progress(n + 1, p.order.size)
                val page = doc.startPage(PdfDocument.PageInfo.Builder(p.w, p.h, n + 1).create())
                p.draw(page.canvas, s)
                doc.finishPage(page)
            }
            out.outputStream().use { doc.writeTo(it) }
        } finally {
            doc.close()
        }
    }

    /** For sending straight to the printer. Landscape sheets are turned so the paper feeds normally. */
    fun writeRaster(
        p: Prepared, out: File, pwg: Boolean, dpi: Int, gray: Boolean, quality: Int,
        copies: Int, sizeName: String, progress: (Int, Int) -> Unit
    ) {
        val land = p.w > p.h
        val wPt = min(p.w, p.h)
        val hPt = max(p.w, p.h)
        val pxW = Math.round(wPt * dpi / 72f)
        val pxH = Math.round(hPt * dpi / 72f)
        val k = dpi / 72f
        val totalPages = p.order.size * copies
        val bmp = Bitmap.createBitmap(pxW, pxH, Bitmap.Config.ARGB_8888)
        try {
            java.io.BufferedOutputStream(out.outputStream(), 256 * 1024).use { os ->
                val rw = RasterWriter(os, pwg, totalPages)
                var n = 0
                repeat(copies) {
                    for (s in p.order) {
                        n++
                        progress(n, totalPages)
                        val c = Canvas(bmp)
                        c.drawColor(Color.WHITE)
                        if (land) {
                            c.translate(pxW.toFloat(), 0f)
                            c.rotate(90f)
                        }
                        c.scale(k, k)
                        p.draw(c, s)
                        rw.page(bmp, gray, dpi, wPt, hPt, sizeName, quality, totalPages)
                    }
                }
            }
        } finally {
            bmp.recycle()
        }
    }

    fun jpegsToPdf(list: List<ByteArray>): ByteArray {
        val doc = PdfDocument()
        try {
            list.forEachIndexed { i, b ->
                val bmp = BitmapFactory.decodeByteArray(b, 0, b.size)
                val page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, i + 1).create())
                val k = min(595f / bmp.width, 842f / bmp.height)
                val w = bmp.width * k
                val h = bmp.height * k
                page.canvas.drawColor(Color.WHITE)
                page.canvas.drawBitmap(
                    bmp, null,
                    RectF((595 - w) / 2, (842 - h) / 2, (595 + w) / 2, (842 + h) / 2),
                    Paint(Paint.FILTER_BITMAP_FLAG)
                )
                doc.finishPage(page)
                bmp.recycle()
            }
            val bo = ByteArrayOutputStream()
            doc.writeTo(bo)
            return bo.toByteArray()
        } finally {
            doc.close()
        }
    }
}

object Store {
    fun save(ctx: Context, name: String, mime: String, relPath: String, collection: Uri, data: ByteArray) {
        val v = ContentValues()
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, name)
        v.put(MediaStore.MediaColumns.MIME_TYPE, mime)
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, relPath)
        val uri = ctx.contentResolver.insert(collection, v) ?: throw Exception("Cannot save file")
        val os = ctx.contentResolver.openOutputStream(uri) ?: throw Exception("Cannot save file")
        os.use { it.write(data) }
    }
}
