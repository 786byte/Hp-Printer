package com.justprintit.core.layout

/** Which sheets of a job to print. With 1 page per sheet a sheet is a page. */
enum class SidesMode(val label: String) {
    ALL("All pages"),
    ODD("Odd pages only"),
    EVEN("Even pages only"),
    GUIDED_DUPLEX("Double-sided (guided)"),
}

/** One printed side: the source page indexes placed on it (null slot = empty cell). Empty list = blank side. */
data class Sheet(val pages: List<Int>, val rotate180: Boolean = false) {
    val isBlank get() = pages.isEmpty()
}

data class DuplexSettings(
    /** Print the backs last-to-first so the stack can go straight back into the tray. */
    val reverseBacks: Boolean = true,
    /** Turn the backs upside down so the user does not have to rotate the stack. */
    val rotateBacks: Boolean = true,
    /** When the front count is odd, feed a blank sheet first so every back lands on the right front. */
    val blankForOddCount: Boolean = true,
)

object SheetPlan {
    /** Groups selected pages into sheets for N pages per sheet, then repeats for copies (collated). */
    fun group(pages: List<Int>, perSheet: Int): List<Sheet> = pages.chunked(perSheet).map { Sheet(it) }

    fun select(sheets: List<Sheet>, mode: SidesMode): List<Sheet> = when (mode) {
        SidesMode.ALL, SidesMode.GUIDED_DUPLEX -> sheets
        SidesMode.ODD -> sheets.filterIndexed { i, _ -> i % 2 == 0 }
        SidesMode.EVEN -> sheets.filterIndexed { i, _ -> i % 2 == 1 }
    }

    data class Duplex(val fronts: List<Sheet>, val backs: List<Sheet>, val instructions: List<String>)

    /**
     * Two-pass manual duplex. Pass 1 prints sheets 1,3,5… Pass 2 prints 2,4,6… arranged so the stack from the
     * output tray goes back into the input tray as-is (printed side up, not turned around).
     */
    fun duplex(sheets: List<Sheet>, settings: DuplexSettings = DuplexSettings()): Duplex {
        val fronts = sheets.filterIndexed { i, _ -> i % 2 == 0 }
        var backs = sheets.filterIndexed { i, _ -> i % 2 == 1 }.map { it.copy(rotate180 = settings.rotateBacks) }
        val oddCount = fronts.size > backs.size
        if (settings.reverseBacks) {
            backs = backs.reversed()
            // The last front lies on top of the stack and has no back: send a blank so it passes through.
            if (oddCount && settings.blankForOddCount) backs = listOf(Sheet(emptyList())) + backs
        } else if (oddCount && settings.blankForOddCount) {
            backs = backs + Sheet(emptyList())
        }
        val steps = buildList {
            add("Take the printed pages out of the output tray. Keep them in the same order.")
            if (settings.rotateBacks) add("Do not turn the stack around or flip it — keep the printed side facing up.")
            else add("Turn the stack around so the edge that came out first goes in first, printed side facing up.")
            add("Put the stack into the input tray and slide the paper guides against it.")
            if (oddCount && settings.blankForOddCount) {
                add("The top sheet only has a front, so the printer will pass it through without printing.")
            }
        }
        return Duplex(fronts, backs, steps)
    }

    /** Expands copies (collated) — the printer gets one job containing every copy. */
    fun withCopies(sheets: List<Sheet>, copies: Int): List<Sheet> = List(copies.coerceAtLeast(1)) { sheets }.flatten()
}
