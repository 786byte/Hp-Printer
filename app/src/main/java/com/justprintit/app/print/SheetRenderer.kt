package com.justprintit.app.print

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import com.justprintit.app.data.Paper
import com.justprintit.core.layout.Imposition
import com.justprintit.core.layout.Sheet
import kotlin.math.roundToInt

/** Turns one [Sheet] (pages placed N-up, zoomed, trimmed, maybe upside down) into a bitmap at a given resolution. */
object SheetRenderer {
    fun sizePx(paper: Paper, dpi: Int) =
        (paper.widthMm / 25.4 * dpi).roundToInt() to (paper.heightMm / 25.4 * dpi).roundToInt()

    /**
     * @param marginMm unprintable border; 0 for borderless
     */
    fun render(doc: PrintDocument, sheet: Sheet, opts: PrintOptions, paper: Paper, dpi: Int, marginMm: Double): Bitmap {
        val (w, h) = sizePx(paper, dpi)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.WHITE)
        if (sheet.isBlank) return bmp

        val pxPerMm = dpi / 25.4
        val margin = marginMm * pxPerMm
        val gutter = if (opts.perSheet > 1) 3.0 * pxPerMm else 0.0

        // Each page's content box in page units (whole page, or trimmed to its ink).
        fun contentRect(index: Int): RectF {
            val (pw, ph) = doc.pageSize(index)
            val b = if (opts.trimMargins) doc.contentBounds(index) else null
            return if (b == null) RectF(0f, 0f, pw.toFloat(), ph.toFloat())
            else RectF((b.left * pw).toFloat(), (b.top * ph).toFloat(), (b.right * pw).toFloat(), (b.bottom * ph).toFloat())
        }

        val first = contentRect(sheet.pages.first())
        val plan = Imposition.plan(opts.perSheet, w.toDouble(), h.toDouble(), margin, gutter,
            first.width().toDouble() / first.height())

        // Reading frame -> physical sheet.
        val frameToSheet = Matrix()
        if (plan.rotated) { frameToSheet.setRotate(90f); frameToSheet.postTranslate(w.toFloat(), 0f) }
        if (sheet.rotate180) frameToSheet.postRotate(180f, w / 2f, h / 2f)

        sheet.pages.forEachIndexed { slot, pageIndex ->
            val cell = plan.cells.getOrNull(slot) ?: return@forEachIndexed
            val content = contentRect(pageIndex)
            val box = when (opts.scale) {
                ScaleMode.FIT -> Imposition.fit(content.width().toDouble(), content.height().toDouble(), cell, opts.zoomPercent / 100.0)
                ScaleMode.FILL -> Imposition.fill(content.width().toDouble(), content.height().toDouble(), cell)
            }
            val s = (box.width / content.width()).toFloat()
            val m = Matrix()
            m.setTranslate(-content.left, -content.top)
            m.postScale(s, s)
            m.postTranslate(box.left.toFloat(), box.top.toFloat())
            m.postConcat(frameToSheet)

            val clipF = RectF(cell.left.toFloat(), cell.top.toFloat(), cell.right.toFloat(), cell.bottom.toFloat())
            frameToSheet.mapRect(clipF)
            val clip = Rect(
                clipF.left.roundToInt().coerceIn(0, w), clipF.top.roundToInt().coerceIn(0, h),
                clipF.right.roundToInt().coerceIn(0, w), clipF.bottom.roundToInt().coerceIn(0, h),
            )
            if (!clip.isEmpty) doc.draw(pageIndex, bmp, m, clip)
        }
        return bmp
    }
}
