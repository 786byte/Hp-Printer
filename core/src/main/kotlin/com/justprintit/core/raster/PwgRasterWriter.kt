package com.justprintit.core.raster

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer

/**
 * Streams pages in PWG Raster format (PWG 5102.4), the format every IPP Everywhere / Mopria printer accepts,
 * including the HP Ink Tank / Smart Tank family. Rows are fed one at a time so a full page never has to be
 * held in memory twice.
 */
class PwgRasterWriter(private val out: OutputStream) {
    data class PageSetup(
        val widthPx: Int,
        val heightPx: Int,
        val dpi: Int,
        val gray: Boolean,
        /** PWG media name, e.g. iso_a4_210x297mm. */
        val mediaName: String,
        val mediaType: String = "",
        /** IPP print-quality: 3 draft, 4 normal, 5 high. */
        val quality: Int = 4,
        val totalPages: Int = 0,
        /** Rotate the back side 180° in the printer instead of in software (not used for manual duplex). */
        val crossFeedTransform: Int = 1,
        val feedTransform: Int = 1,
    )

    private var setup: PageSetup? = null
    private var rowsWritten = 0
    private var bytesPerPixel = 3
    private var pending: ByteArray? = null
    private var pendingRepeats = 0
    private val encoded = ByteArrayOutputStream(16 * 1024)

    init {
        out.write("RaS2".toByteArray(Charsets.US_ASCII))
    }

    fun beginPage(page: PageSetup) {
        check(setup == null) { "Previous page not finished" }
        setup = page
        rowsWritten = 0
        bytesPerPixel = if (page.gray) 1 else 3
        out.write(header(page))
    }

    /** One row of ARGB pixels (as produced by Android's Bitmap.getPixels). Transparent pixels are treated as white. */
    fun writeRow(argb: IntArray, offset: Int = 0) {
        val page = checkNotNull(setup) { "beginPage first" }
        val row = ByteArray(page.widthPx * bytesPerPixel)
        if (page.gray) {
            for (x in 0 until page.widthPx) {
                val p = argb[offset + x]
                row[x] = luma(p).toByte()
            }
        } else {
            var o = 0
            for (x in 0 until page.widthPx) {
                val p = flattenOnWhite(argb[offset + x])
                row[o++] = (p shr 16).toByte(); row[o++] = (p shr 8).toByte(); row[o++] = p.toByte()
            }
        }
        val prev = pending
        if (prev != null && prev.contentEquals(row) && pendingRepeats < 255) {
            pendingRepeats++
        } else {
            flushRow()
            pending = row
            pendingRepeats = 0
        }
        rowsWritten++
    }

    fun endPage() {
        val page = checkNotNull(setup)
        check(rowsWritten == page.heightPx) { "Wrote $rowsWritten rows, expected ${page.heightPx}" }
        flushRow()
        setup = null
    }

    private fun flushRow() {
        val row = pending ?: return
        encoded.reset()
        encoded.write(pendingRepeats)
        encodeRow(row, bytesPerPixel, encoded)
        encoded.writeTo(out)
        pending = null
    }

    companion object {
        const val HEADER_SIZE = 1796
        private const val CS_SGRAY = 18
        private const val CS_SRGB = 19

        fun luma(argb: Int): Int {
            val p = flattenOnWhite(argb)
            val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
            return (r * 299 + g * 587 + b * 114 + 500) / 1000
        }

        fun flattenOnWhite(argb: Int): Int {
            val a = (argb ushr 24) and 0xFF
            if (a == 0xFF) return argb and 0xFFFFFF
            if (a == 0) return 0xFFFFFF
            fun ch(shift: Int): Int {
                val c = (argb shr shift) and 0xFF
                return (c * a + 255 * (255 - a) + 127) / 255
            }
            return (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }

        fun header(p: PageSetup): ByteArray {
            val b = ByteBuffer.allocate(HEADER_SIZE) // big-endian by default, zero-filled
            fun str(offset: Int, s: String) {
                val bytes = s.toByteArray(Charsets.US_ASCII).take(63).toByteArray()
                b.position(offset); b.put(bytes)
            }
            fun u32(offset: Int, v: Int) { b.putInt(offset, v) }
            val bpp = if (p.gray) 8 else 24
            str(0, "PwgRaster")
            str(128, p.mediaType)
            u32(276, p.dpi); u32(280, p.dpi)                         // HWResolution
            u32(340, 1)                                              // NumCopies (copies are expanded by the app)
            u32(352, Math.round(p.widthPx * 72.0 / p.dpi).toInt())    // PageSize in points
            u32(356, Math.round(p.heightPx * 72.0 / p.dpi).toInt())
            u32(372, p.widthPx)
            u32(376, p.heightPx)
            u32(384, 8)                                              // BitsPerColor
            u32(388, bpp)                                            // BitsPerPixel
            u32(392, p.widthPx * bpp / 8)                            // BytesPerLine
            u32(396, 0)                                              // ColorOrder chunky
            u32(400, if (p.gray) CS_SGRAY else CS_SRGB)
            u32(420, if (p.gray) 1 else 3)                           // NumColors
            u32(452, p.totalPages)
            u32(456, p.crossFeedTransform)
            u32(460, p.feedTransform)
            u32(480, 0xFFFFFF)                                       // AlternatePrimary (white)
            u32(484, p.quality)
            str(1732, p.mediaName)
            return b.array()
        }

        /**
         * PackBits-style pixel encoding used by PWG/CUPS raster:
         * 0..127 = repeat next pixel (n+1) times, 129..255 = (257-n) literal pixels follow (2..128).
         */
        fun encodeRow(row: ByteArray, bpp: Int, out: ByteArrayOutputStream) {
            val pixels = row.size / bpp
            var i = 0
            fun same(a: Int, b: Int): Boolean {
                for (k in 0 until bpp) if (row[a * bpp + k] != row[b * bpp + k]) return false
                return true
            }
            while (i < pixels) {
                if (i == pixels - 1) { // single trailing pixel
                    out.write(0); out.write(row, i * bpp, bpp); i++; continue
                }
                if (same(i, i + 1)) {
                    var n = 2
                    while (i + n < pixels && n < 128 && same(i, i + n)) n++
                    out.write(n - 1); out.write(row, i * bpp, bpp)
                    i += n
                } else {
                    var n = 1
                    while (i + n < pixels && n < 128 && (i + n == pixels - 1 || !same(i + n, i + n + 1))) n++
                    if (n == 1) { out.write(0); out.write(row, i * bpp, bpp) }
                    else { out.write(257 - n); out.write(row, i * bpp, n * bpp) }
                    i += n
                }
            }
        }

        /** Reference decoder, used by tests to prove the encoder is lossless. */
        fun decodeRow(data: ByteArray, start: Int, pixels: Int, bpp: Int, dest: ByteArray): Int {
            var p = start; var x = 0
            while (x < pixels) {
                val c = data[p++].toInt() and 0xFF
                if (c < 128) {
                    repeat(c + 1) { System.arraycopy(data, p, dest, (x + it) * bpp, bpp) }
                    p += bpp; x += c + 1
                } else {
                    val n = 257 - c
                    System.arraycopy(data, p, dest, x * bpp, n * bpp)
                    p += n * bpp; x += n
                }
            }
            return p
        }
    }
}
