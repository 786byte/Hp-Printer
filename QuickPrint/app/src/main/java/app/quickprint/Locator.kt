package app.quickprint

import android.content.Context

/**
 * Finds the printer wherever it is right now. On home Wi-Fi it has the router's address
 * (e.g. 192.168.10.158); on its own Wi-Fi Direct it is usually 192.168.223.1. Every address that worked is
 * remembered, so switching between the two networks needs no setup.
 */
object Locator {
    const val WIFI_DIRECT_IP = "192.168.223.1"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("qp", Context.MODE_PRIVATE)

    fun known(ctx: Context): List<String> =
        (prefs(ctx).getString("known_ips", "") ?: "").split(',').filter { it.isNotBlank() }

    /** Call after the printer answered at [ip]. */
    fun remember(ctx: Context, ip: String, model: String?) {
        val list = (listOf(ip) + known(ctx)).distinct().take(6)
        val e = prefs(ctx).edit().putString("known_ips", list.joinToString(","))
        if (!model.isNullOrBlank()) e.putString("model", model)
        e.apply()
    }

    /** True if the IPP printer at [ip] is the one we saved (same model), or if we never saw its model. */
    private fun isOurs(ctx: Context, ip: String): Boolean {
        val want = prefs(ctx).getString("model", "") ?: ""
        if (want.isBlank()) return true
        return try {
            Ipp.status(ip, Net.forIp(ctx, ip)).model.let { it.isBlank() || it == want }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Returns an address where the printer answers, trying [current] first, then remembered addresses,
     * the Wi-Fi Direct address, and finally a Wi-Fi search. Null if it can't be found. Blocking.
     */
    fun find(ctx: Context, current: String, savedName: String, progress: (String) -> Unit = {}): String? {
        if (current.isNotBlank() && Net.reachable(ctx, current, timeout = 2500)) return current
        val candidates = (known(ctx) + WIFI_DIRECT_IP).filter { it != current }.distinct()
        for (c in candidates) {
            progress("Looking for the printer at $c…")
            if (Net.reachable(ctx, c) && isOurs(ctx, c)) return c
        }
        progress("Searching Wi-Fi for the printer…")
        val found = search(ctx, 6000)
        val ordered = found.sortedByDescending { (name, _) ->
            when {
                savedName.isNotBlank() && name == savedName -> 2
                name.contains("HP", ignoreCase = true) -> 1
                else -> 0
            }
        }
        for ((_, ip) in ordered) if (Net.reachable(ctx, ip) && isOurs(ctx, ip)) return ip
        return null
    }

    /** mDNS search for [ms] milliseconds, from a background thread. */
    fun search(ctx: Context, ms: Long): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        val lock = Object()
        val d = Discovery(ctx.applicationContext) { name, ip -> synchronized(lock) { if (out.none { it.second == ip }) out.add(name to ip) } }
        try {
            d.start()
            Thread.sleep(ms)
        } finally {
            d.stop()
        }
        return synchronized(lock) { ArrayList(out) }
    }
}
