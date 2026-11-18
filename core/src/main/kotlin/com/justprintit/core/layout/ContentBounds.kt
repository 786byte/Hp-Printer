package com.justprintit.core.layout

/** Finds the non-white area of a rendered page so white margins can be trimmed and the content enlarged. */
object ContentBounds {
    data class Fraction(val left: Double, val top: Double, val right: Double, val bottom: Double) {
        val width get() = right - left
        val height get() = bottom - top
    }

    /**
     * @param argb pixels, row-major
     * @param padding extra space kept around the content, as a fraction of the page's larger side
     * @return the content box as fractions of the page, or null for a blank page
     */
    fun find(argb: IntArray, width: Int, height: Int, threshold: Int = 235, padding: Double = 0.015): Fraction? {
        var minX = width; var minY = height; var maxX = -1; var maxY = -1
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                val p = argb[row + x]
                val a = (p ushr 24) and 0xFF
                if (a < 16) continue
                val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
                if (r < threshold || g < threshold || b < threshold) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        if (maxX < 0) return null
        val padX = padding * maxOf(width, height) / width
        val padY = padding * maxOf(width, height) / height
        return Fraction(
            (minX.toDouble() / width - padX).coerceAtLeast(0.0),
            (minY.toDouble() / height - padY).coerceAtLeast(0.0),
            ((maxX + 1).toDouble() / width + padX).coerceAtMost(1.0),
            ((maxY + 1).toDouble() / height + padY).coerceAtMost(1.0),
        )
    }

    /** True when any pixel is noticeably coloured (used to count colour vs black & white pages). */
    fun hasColor(argb: IntArray, offset: Int, count: Int, tolerance: Int = 24): Boolean {
        for (i in offset until offset + count) {
            val p = argb[i]
            if ((p ushr 24) and 0xFF < 16) continue
            val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
            if (maxOf(r, g, b) - minOf(r, g, b) > tolerance) return true
        }
        return false
    }
}
