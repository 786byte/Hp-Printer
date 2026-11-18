package com.justprintit.core.pdf

import java.io.ByteArrayOutputStream
import java.io.OutputStream

/**
 * Writes a PDF where each page is one JPEG, embedded as-is (DCTDecode) — no re-compression, so scans
 * stay sharp and small.
 */
object JpegPdfWriter {
    data class JpegInfo(val width: Int, val height: Int, val components: Int)

    fun readInfo(jpeg: ByteArray): JpegInfo {
        require(jpeg.size > 4 && (jpeg[0].toInt() and 0xFF) == 0xFF && (jpeg[1].toInt() and 0xFF) == 0xD8) { "Not a JPEG" }
        var i = 2
        while (i + 9 < jpeg.size) {
            if ((jpeg[i].toInt() and 0xFF) != 0xFF) { i++; continue }
            val marker = jpeg[i + 1].toInt() and 0xFF
            if (marker == 0xFF) { i++; continue }
            if (marker == 0xD8 || marker == 0x01 || marker in 0xD0..0xD7) { i += 2; continue }
            val len = ((jpeg[i + 2].toInt() and 0xFF) shl 8) or (jpeg[i + 3].toInt() and 0xFF)
            val isSof = marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC
            if (isSof) {
                val h = ((jpeg[i + 5].toInt() and 0xFF) shl 8) or (jpeg[i + 6].toInt() and 0xFF)
                val w = ((jpeg[i + 7].toInt() and 0xFF) shl 8) or (jpeg[i + 8].toInt() and 0xFF)
                return JpegInfo(w, h, jpeg[i + 9].toInt() and 0xFF)
            }
            i += 2 + len
        }
        throw IllegalArgumentException("JPEG size not found")
    }

    /** @param dpi resolution the images were scanned at, used to give each PDF page its real paper size. */
    fun write(pages: List<ByteArray>, dpi: Int, out: OutputStream) {
        require(pages.isNotEmpty())
        val buf = ByteArrayOutputStream()
        val offsets = mutableListOf<Int>()
        fun obj(body: () -> Unit) { offsets += buf.size(); body() }
        fun w(s: String) = buf.write(s.toByteArray(Charsets.ISO_8859_1))

        val n = pages.size
        // Object numbers: 1 catalog, 2 pages, then per page: page, content, image.
        fun pageObj(i: Int) = 3 + i * 3
        w("%PDF-1.4\n%âãÏÓ\n")
        obj { w("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n") }
        obj {
            val kids = (0 until n).joinToString(" ") { "${pageObj(it)} 0 R" }
            w("2 0 obj\n<< /Type /Pages /Count $n /Kids [$kids] >>\nendobj\n")
        }
        pages.forEachIndexed { i, jpeg ->
            val info = readInfo(jpeg)
            val wPt = info.width * 72.0 / dpi
            val hPt = info.height * 72.0 / dpi
            val p = pageObj(i)
            val fmt = { d: Double -> "%.2f".format(java.util.Locale.ROOT, d) }
            obj {
                w("$p 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${fmt(wPt)} ${fmt(hPt)}] ")
                w("/Resources << /XObject << /Im0 ${p + 2} 0 R >> >> /Contents ${p + 1} 0 R >>\nendobj\n")
            }
            val content = "q ${fmt(wPt)} 0 0 ${fmt(hPt)} 0 0 cm /Im0 Do Q"
            obj { w("${p + 1} 0 obj\n<< /Length ${content.length} >>\nstream\n$content\nendstream\nendobj\n") }
            val cs = when (info.components) { 1 -> "/DeviceGray"; 4 -> "/DeviceCMYK"; else -> "/DeviceRGB" }
            obj {
                w("${p + 2} 0 obj\n<< /Type /XObject /Subtype /Image /Width ${info.width} /Height ${info.height} ")
                w("/ColorSpace $cs /BitsPerComponent 8 /Filter /DCTDecode /Length ${jpeg.size} >>\nstream\n")
                buf.write(jpeg)
                w("\nendstream\nendobj\n")
            }
        }
        val xref = buf.size()
        w("xref\n0 ${offsets.size + 1}\n0000000000 65535 f \n")
        offsets.forEach { w("%010d 00000 n \n".format(it)) }
        w("trailer\n<< /Size ${offsets.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        buf.writeTo(out)
    }
}
