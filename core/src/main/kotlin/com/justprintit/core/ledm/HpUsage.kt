package com.justprintit.core.ledm

import com.justprintit.core.scan.Xml
import java.net.HttpURLConnection
import java.net.URL

/**
 * HP printers publish their own lifetime counters through the "LEDM" web interface. Optional: the app's own
 * counters work without it, this only adds the printer's view (which also counts copies made on the printer).
 */
data class HpUsage(
    val totalImpressions: Int?,
    val colorImpressions: Int?,
    val monoImpressions: Int?,
    val scans: Int?,
) {
    companion object {
        fun parse(xml: String): HpUsage {
            val printer = Xml.section(xml, "PrinterSubunit") ?: xml
            fun n(tag: String, src: String = printer) = Xml.first(src, tag)?.toIntOrNull()
            val scanner = Xml.section(xml, "ScannerEngineSubunit")
            return HpUsage(
                totalImpressions = n("TotalImpressions"),
                colorImpressions = n("ColorImpressions"),
                monoImpressions = n("MonochromeImpressions"),
                scans = scanner?.let { n("ScanImages", it) },
            )
        }

        fun fetch(host: String): HpUsage? = runCatching {
            val h = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
            val conn = URL("http://$h/DevMgmt/ProductUsageDyn.xml").openConnection() as HttpURLConnection
            conn.connectTimeout = 5_000; conn.readTimeout = 8_000
            try {
                if (conn.responseCode != 200) null
                else parse(conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) })
            } finally { conn.disconnect() }
        }.getOrNull()?.takeIf { it.totalImpressions != null }
    }
}
