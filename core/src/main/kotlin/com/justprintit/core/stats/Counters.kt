package com.justprintit.core.stats

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/** One finished (or partly finished) job's printed sides. */
data class PrintRecord(val timeMillis: Long, val colorPages: Int, val monoPages: Int)

data class Tally(val color: Int = 0, val mono: Int = 0) {
    val total get() = color + mono
    operator fun plus(r: PrintRecord) = Tally(color + r.colorPages, mono + r.monoPages)
}

data class CounterSummary(
    val today: Tally,
    val thisMonth: Tally,
    val allTime: Tally,
    /** Last [days] days, oldest first. */
    val daily: List<Pair<LocalDate, Tally>>,
    /** Last 12 months, oldest first. */
    val monthly: List<Pair<YearMonth, Tally>>,
)

object Counters {
    fun summarize(records: List<PrintRecord>, zone: ZoneId, now: Instant = Instant.now(), days: Int = 30): CounterSummary {
        val today = now.atZone(zone).toLocalDate()
        val month = YearMonth.from(today)
        val byDay = HashMap<LocalDate, Tally>()
        var all = Tally()
        for (r in records) {
            val d = Instant.ofEpochMilli(r.timeMillis).atZone(zone).toLocalDate()
            byDay[d] = (byDay[d] ?: Tally()) + r
            all += r
        }
        val byMonth = byDay.entries.groupBy({ YearMonth.from(it.key) }, { it.value })
            .mapValues { (_, v) -> v.fold(Tally()) { a, t -> Tally(a.color + t.color, a.mono + t.mono) } }
        return CounterSummary(
            today = byDay[today] ?: Tally(),
            thisMonth = byMonth[month] ?: Tally(),
            allTime = all,
            daily = (days - 1 downTo 0).map { today.minusDays(it.toLong()).let { d -> d to (byDay[d] ?: Tally()) } },
            monthly = (11 downTo 0).map { month.minusMonths(it.toLong()).let { m -> m to (byMonth[m] ?: Tally()) } },
        )
    }

    private operator fun Tally.plus(t: Tally) = Tally(color + t.color, mono + t.mono)
}
