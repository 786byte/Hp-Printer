package com.justprintit.core.layout

import kotlin.math.max
import kotlin.math.min

data class Box(val left: Double, val top: Double, val width: Double, val height: Double) {
    val right get() = left + width
    val bottom get() = top + height
}

/**
 * Places N document pages on one sheet ("pages per sheet"). The layout is computed in a "reading frame";
 * when [rotated] is true the reading frame is landscape and must be drawn onto the portrait sheet turned
 * 90° clockwise (the reader turns the sheet a quarter turn anticlockwise to read it, pages run left→right).
 */
data class Imposition(
    val rotated: Boolean,
    val frameWidth: Double,
    val frameHeight: Double,
    val cells: List<Box>,
) {
    companion object {
        val SUPPORTED = listOf(1, 2, 4, 6, 9, 16)

        /**
         * @param sheetW sheet size (any unit)
         * @param margin printable margin on each side, same unit
         * @param gutter space between cells
         * @param pageAspect width / height of the source pages
         */
        fun plan(n: Int, sheetW: Double, sheetH: Double, margin: Double, gutter: Double, pageAspect: Double): Imposition {
            require(n >= 1)
            var best: Imposition? = null
            var bestScale = -1.0
            for (rotated in listOf(false, true)) {
                val fw = if (rotated) sheetH else sheetW
                val fh = if (rotated) sheetW else sheetH
                for (cols in 1..n) {
                    if (n % cols != 0) continue
                    val rows = n / cols
                    val cw = (fw - 2 * margin - gutter * (cols - 1)) / cols
                    val ch = (fh - 2 * margin - gutter * (rows - 1)) / rows
                    if (cw <= 0 || ch <= 0) continue
                    // Scale a unit-height page of the given aspect into the cell.
                    val scale = min(cw / pageAspect, ch)
                    // Prefer the unrotated layout on ties (e.g. 1-up), then fewer columns.
                    if (scale > bestScale * 1.0001) {
                        bestScale = scale
                        val cells = (0 until rows).flatMap { r ->
                            (0 until cols).map { c ->
                                Box(margin + c * (cw + gutter), margin + r * (ch + gutter), cw, ch)
                            }
                        }
                        best = Imposition(rotated, fw, fh, cells)
                    }
                }
            }
            return checkNotNull(best) { "Sheet too small for $n pages" }
        }

        /** Fit a content box of size w×h into [cell] at the given zoom (1.0 = fit), centred. */
        fun fit(contentW: Double, contentH: Double, cell: Box, zoom: Double): Box {
            val s = min(cell.width / contentW, cell.height / contentH) * zoom
            val w = contentW * s; val h = contentH * s
            return Box(cell.left + (cell.width - w) / 2, cell.top + (cell.height - h) / 2, w, h)
        }

        /** Fill [cell] completely (cropping overflow) — used for borderless photos. */
        fun fill(contentW: Double, contentH: Double, cell: Box): Box {
            val s = max(cell.width / contentW, cell.height / contentH)
            val w = contentW * s; val h = contentH * s
            return Box(cell.left + (cell.width - w) / 2, cell.top + (cell.height - h) / 2, w, h)
        }
    }
}
