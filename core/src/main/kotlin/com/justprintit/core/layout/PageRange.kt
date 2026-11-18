package com.justprintit.core.layout

/**
 * Parses page ranges like "1-3, 5, 8-" or "-4" (1-based, inclusive) into 0-based page indexes,
 * in the order written. Blank means all pages.
 */
object PageRange {
    class Invalid(message: String) : IllegalArgumentException(message)

    fun parse(text: String, pageCount: Int): List<Int> {
        val spec = text.trim()
        if (spec.isEmpty()) return (0 until pageCount).toList()
        val result = mutableListOf<Int>()
        for (rawPart in spec.split(',', ';')) {
            val part = rawPart.trim().replace('–', '-').replace('—', '-')
            if (part.isEmpty()) continue
            val dash = part.indexOf('-')
            val (from, to) = if (dash < 0) {
                val n = number(part); n to n
            } else {
                val a = part.substring(0, dash).trim()
                val b = part.substring(dash + 1).trim()
                (if (a.isEmpty()) 1 else number(a)) to (if (b.isEmpty()) pageCount else number(b))
            }
            if (from < 1 || to < 1) throw Invalid("Page numbers start at 1")
            if (from > pageCount || to > pageCount) throw Invalid("The document only has $pageCount page${if (pageCount == 1) "" else "s"}")
            if (from <= to) (from..to).forEach { result += it - 1 } else (from downTo to).forEach { result += it - 1 }
        }
        if (result.isEmpty()) throw Invalid("No pages selected")
        return result
    }

    private fun number(s: String): Int = s.toIntOrNull() ?: throw Invalid("\"$s\" is not a page number")
}
