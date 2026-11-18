package com.justprintit.app.print

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import java.io.File

/** A 2-page PDF for checking the guided double-sided setup: arrows show which way is up on each side. */
object TestPages {
    fun duplexTest(context: Context): File {
        val dir = File(context.filesDir, "jobs").apply { mkdirs() }
        val file = File(dir, "duplex_test_${System.currentTimeMillis()}.pdf")
        val pdf = PdfDocument()
        listOf("FRONT" to "Page 1", "BACK" to "Page 2").forEachIndexed { i, (side, label) ->
            val page = pdf.startPage(PdfDocument.PageInfo.Builder(595, 842, i + 1).create())
            val c = page.canvas
            val big = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 72f; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER }
            val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 20f; textAlign = Paint.Align.CENTER; color = Color.DKGRAY }
            val arrow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
            c.drawPath(Path().apply { moveTo(297f, 90f); lineTo(237f, 190f); lineTo(357f, 190f); close() }, arrow)
            c.drawRect(282f, 190f, 312f, 300f, arrow)
            c.drawText("TOP", 297f, 340f, small)
            c.drawText(side, 297f, 470f, big)
            c.drawText(label, 297f, 540f, small)
            c.drawText(if (i == 0) "Follow the steps in the app to print the back." else "Both arrows should point the same way.", 297f, 620f, small)
            c.drawText("Just Print It — double-sided test", 297f, 800f, small)
            pdf.finishPage(page)
        }
        file.outputStream().use { pdf.writeTo(it) }
        pdf.close()
        return file
    }
}
