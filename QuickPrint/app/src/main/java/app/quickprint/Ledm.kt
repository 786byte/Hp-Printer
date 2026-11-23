package app.quickprint

import android.net.Network

data class Usage(val total: Int, val mono: Int, val color: Int)

/** HP's own status pages (LEDM). Gives the printer's lifetime page counter and, as a backup, ink levels. */
object Ledm {
    private fun fetch(ip: String, net: Network?, path: String): String? =
        Http.getText("http://$ip$path", net) ?: Http.getText("https://$ip$path", net)

    private fun num(s: String, tag: String): Int =
        Regex("<[A-Za-z0-9]*:?$tag[^>]*>\\s*(\\d+)").find(s)?.groupValues?.get(1)?.toIntOrNull() ?: -1

    fun usage(ip: String, net: Network?): Usage? {
        val x = fetch(ip, net, "/DevMgmt/ProductUsageDyn.xml") ?: return null
        val start = x.indexOf("PrinterSubunit")
        val s = if (start >= 0) x.substring(start) else x
        val total = num(s, "TotalImpressions")
        if (total < 0) return null
        return Usage(total, num(s, "MonochromeImpressions"), num(s, "ColorImpressions"))
    }

    fun ink(ip: String, net: Network?): List<Marker> {
        val x = fetch(ip, net, "/DevMgmt/ConsumableConfigDyn.xml") ?: return emptyList()
        val out = ArrayList<Marker>()
        for (part in x.split(Regex("<[A-Za-z0-9]*:?ConsumableInfo>")).drop(1)) {
            val color = Regex("<[A-Za-z0-9]*:?MarkerColor[^>]*>([^<]+)<").find(part)?.groupValues?.get(1) ?: continue
            val lvl = num(part, "ConsumablePercentageLevelRemaining")
            out.add(Marker(color, Ipp.colorOf(color), lvl, "ink"))
        }
        return out
    }
}
