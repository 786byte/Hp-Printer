package app.quickprint

import android.graphics.Bitmap
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Writes PWG raster ("RaS2") or Apple raster ("UNIRAST"), the formats printers accept for direct printing. */
class RasterWriter(private val out: OutputStream, private val pwg: Boolean, totalPages: Int) {

    init {
        if (pwg) {
            out.write("RaS2".toByteArray(Charsets.US_ASCII))
        } else {
            out.write("UNIRAST".toByteArray(Charsets.US_ASCII))
            out.write(0)
            int32(totalPages)
        }
    }

    private fun int32(v: Int) {
        out.write((v ushr 24) and 255)
        out.write((v ushr 16) and 255)
        out.write((v ushr 8) and 255)
        out.write(v and 255)
    }

    private fun putStr(b: ByteBuffer, at: Int, s: String) {
        val bytes = s.toByteArray(Charsets.US_ASCII)
        for (i in 0 until minOf(63, bytes.size)) b.put(at + i, bytes[i])
    }

    fun page(bmp: Bitmap, gray: Boolean, dpi: Int, wPt: Int, hPt: Int, sizeName: String, quality: Int, totalPages: Int) {
        val w = bmp.width
        val h = bmp.height
        val bpp = if (gray) 1 else 3
        if (pwg) {
            val b = ByteBuffer.allocate(1796).order(ByteOrder.BIG_ENDIAN)
            putStr(b, 0, "PwgRaster")
            b.putInt(276, dpi); b.putInt(280, dpi)
            b.putInt(340, 1)                         // NumCopies
            b.putInt(352, wPt); b.putInt(356, hPt)    // PageSize in points
            b.putInt(372, w); b.putInt(376, h)
            b.putInt(384, 8)                          // bits per colour
            b.putInt(388, bpp * 8)                    // bits per pixel
            b.putInt(392, w * bpp)                    // bytes per line
            b.putInt(396, 0)                          // chunky
            b.putInt(400, if (gray) 18 else 19)       // sGray / sRGB
            b.putInt(420, bpp)                        // number of colours
            b.putInt(452, totalPages)
            b.putInt(456, 1); b.putInt(460, 1)        // cross-feed / feed transform
            b.putInt(472, w); b.putInt(476, h)        // image box right/bottom
            b.putInt(480, 0xFFFFFF)                   // alternate primary
            b.putInt(484, quality)
            putStr(b, 1732, sizeName)
            out.write(b.array())
        } else {
            out.write(bpp * 8)
            out.write(if (gray) 0 else 1)             // 0 sGray, 1 sRGB
            out.write(1)                              // one-sided
            out.write(quality)
            int32(0); int32(0)
            int32(w); int32(h); int32(dpi)
            int32(0); int32(0)
        }
        encode(bmp, gray)
    }

    private fun encode(bmp: Bitmap, gray: Boolean) {
        val w = bmp.width
        val h = bmp.height
        val bpp = if (gray) 1 else 3
        val px = IntArray(w)
        var prev: ByteArray? = null
        var cur = ByteArray(w * bpp)
        var repeat = 0
        for (y in 0 until h) {
            bmp.getPixels(px, 0, w, 0, y, w, 1)
            if (gray) {
                for (x in 0 until w) {
                    val v = px[x]
                    cur[x] = ((((v shr 16) and 255) * 299 + ((v shr 8) and 255) * 587 + (v and 255) * 114) / 1000).toByte()
                }
            } else {
                var k = 0
                for (x in 0 until w) {
                    val v = px[x]
                    cur[k] = (v shr 16).toByte()
                    cur[k + 1] = (v shr 8).toByte()
                    cur[k + 2] = v.toByte()
                    k += 3
                }
            }
            val p = prev
            if (p != null && repeat < 255 && cur.contentEquals(p)) {
                repeat++
                continue
            }
            if (p != null) writeLine(p, repeat, bpp)
            val spare = p ?: ByteArray(w * bpp)
            prev = cur
            cur = spare
            repeat = 0
        }
        prev?.let { writeLine(it, repeat, bpp) }
    }

    private fun same(l: ByteArray, a: Int, b: Int, bpp: Int): Boolean {
        for (i in 0 until bpp) if (l[a * bpp + i] != l[b * bpp + i]) return false
        return true
    }

    private fun writeLine(l: ByteArray, repeat: Int, bpp: Int) {
        out.write(repeat)
        val n = l.size / bpp
        var i = 0
        while (i < n) {
            var run = 1
            while (i + run < n && run < 128 && same(l, i, i + run, bpp)) run++
            if (run > 1) {
                out.write(run - 1)
                out.write(l, i * bpp, bpp)
                i += run
            } else {
                var j = i + 1
                while (j < n && j - i < 128 && !(j + 1 < n && same(l, j, j + 1, bpp))) j++
                val cnt = j - i
                if (cnt == 1) out.write(0) else out.write(257 - cnt)
                out.write(l, i * bpp, cnt * bpp)
                i = j
            }
        }
    }
}
