package app.quickprint

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Counts sheets printed from this app, split into colour and black & white. */
object Stats {
    fun p(c: Context) = c.getSharedPreferences("stats", Context.MODE_PRIVATE)
    private fun day() = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
    private fun month() = SimpleDateFormat("yyyyMM", Locale.US).format(Date())

    fun add(c: Context, pages: Int, color: Boolean) {
        val s = p(c)
        val k = if (color) "c" else "b"
        val d = "${k}_${day()}"
        val m = "${k}_${month()}"
        val a = "${k}_all"
        s.edit()
            .putInt(d, s.getInt(d, 0) + pages)
            .putInt(m, s.getInt(m, 0) + pages)
            .putInt(a, s.getInt(a, 0) + pages)
            .putInt("jobs", s.getInt("jobs", 0) + 1)
            .apply()
    }

    private fun line(c: Context, title: String, suffix: String): String {
        val s = p(c)
        val col = s.getInt("c_$suffix", 0)
        val bw = s.getInt("b_$suffix", 0)
        return "$title:  $col colour  ·  $bw B/W  ·  ${col + bw} total"
    }

    fun text(c: Context): String {
        val s = p(c)
        return line(c, "Today", day()) + "\n" +
            line(c, "This month", month()) + "\n" +
            line(c, "All time", "all") + "\n" +
            "Print jobs: " + s.getInt("jobs", 0)
    }

    fun reset(c: Context) {
        p(c).edit().clear().apply()
    }
}
