package app.quickprint

import android.net.Network
import java.net.URL

/** Minimal eSCL (AirScan) client: scans one page from the flatbed and returns JPEG bytes. */
object Escl {
    private fun candidates(ip: String) = listOf("http://$ip/eSCL", "https://$ip/eSCL", "http://$ip:8080/eSCL")

    private fun findBase(ip: String, net: Network?): String {
        for (b in candidates(ip)) {
            try {
                val c = Http.open("$b/ScannerCapabilities", net)
                c.connectTimeout = 2500
                c.readTimeout = 4000
                val ok = c.responseCode == 200
                c.disconnect()
                if (ok) return b
            } catch (e: Exception) {
                // try next
            }
        }
        throw Exception("Scanner not found on $ip")
    }

    fun scan(ip: String, net: Network?, color: Boolean, dpi: Int): ByteArray {
        val base = findBase(ip, net)
        val mode = if (color) "RGB24" else "Grayscale8"
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
<scan:ScanSettings xmlns:pwg="http://www.pwg.org/schemas/2010/12/sm" xmlns:scan="http://schemas.hp.com/imaging/escl/2011/05/03">
<pwg:Version>2.1</pwg:Version>
<pwg:ScanRegions><pwg:ScanRegion>
<pwg:ContentRegionUnits>escl:ThreeHundredthsOfInches</pwg:ContentRegionUnits>
<pwg:XOffset>0</pwg:XOffset><pwg:YOffset>0</pwg:YOffset>
<pwg:Width>2480</pwg:Width><pwg:Height>3507</pwg:Height>
</pwg:ScanRegion></pwg:ScanRegions>
<pwg:InputSource>Platen</pwg:InputSource>
<scan:ColorMode>$mode</scan:ColorMode>
<scan:XResolution>$dpi</scan:XResolution>
<scan:YResolution>$dpi</scan:YResolution>
<pwg:DocumentFormat>image/jpeg</pwg:DocumentFormat>
<scan:DocumentFormatExt>image/jpeg</scan:DocumentFormatExt>
</scan:ScanSettings>"""

        val c = Http.open("$base/ScanJobs", net)
        c.requestMethod = "POST"
        c.doOutput = true
        c.connectTimeout = 5000
        c.readTimeout = 20000
        c.setRequestProperty("Content-Type", "text/xml")
        c.outputStream.use { it.write(xml.toByteArray(Charsets.UTF_8)) }
        val code = c.responseCode
        if (code != 201 && code != 200) {
            c.disconnect()
            throw Exception("Scanner refused the job (HTTP $code). Is it busy or is the lid open?")
        }
        var loc = c.getHeaderField("Location") ?: throw Exception("Scanner gave no job address")
        c.disconnect()
        if (loc.startsWith("/")) {
            val u = URL(base)
            loc = u.protocol + "://" + u.authority + loc
        }
        loc = loc.trimEnd('/')

        repeat(90) {
            val d = Http.open("$loc/NextDocument", net)
            d.connectTimeout = 5000
            d.readTimeout = 60000
            val rc = d.responseCode
            if (rc == 200) {
                val b = d.inputStream.use { s -> s.readBytes() }
                d.disconnect()
                return b
            }
            d.disconnect()
            if (rc == 503 || rc == 409 || rc == 404) Thread.sleep(1000)
            else throw Exception("Scan failed (HTTP $rc)")
        }
        throw Exception("Scan timed out")
    }
}
