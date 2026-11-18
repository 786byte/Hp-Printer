package com.justprintit.core.scan

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** eSCL ("AirScan") — the driverless scan protocol HP printers speak on port 80 at /eSCL. */
class EsclClient(
    private val host: String,
    private val port: Int = 80,
    private val root: String = "/eSCL",
) {
    data class Capabilities(
        val maxWidth: Int,   // 1/300 inch
        val maxHeight: Int,
        val resolutions: List<Int>,
        val colorModes: List<String>,
        val formats: List<String>,
    )

    enum class ColorMode(val escl: String, val label: String) {
        COLOR("RGB24", "Colour"),
        GRAY("Grayscale8", "Grey"),
        BLACK_WHITE("BlackAndWhite1", "Black & white"),
    }

    data class Region(val widthPx300: Int, val heightPx300: Int, val label: String) {
        companion object {
            val A4 = Region(2480, 3508, "A4")
            val LETTER = Region(2550, 3300, "Letter")
            val PHOTO_4X6 = Region(1200, 1800, "4×6 photo")
        }
    }

    class ScanException(message: String, cause: Throwable? = null) : Exception(message, cause)

    private fun url(path: String): URL {
        val h = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
        return if (path.startsWith("http")) URL(path) else URL("http", h, port, path)
    }

    private fun open(path: String, method: String): HttpURLConnection =
        (url(path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 8_000
            readTimeout = 120_000 // the scan head takes a while on the first page
            instanceFollowRedirects = true
        }

    fun status(): String {
        val conn = open("$root/ScannerStatus", "GET")
        try {
            val xml = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            return Xml.first(xml, "State") ?: "Unknown"
        } catch (e: IOException) {
            throw ScanException("Scanner not reachable: ${e.message}", e)
        } finally { conn.disconnect() }
    }

    fun capabilities(): Capabilities {
        val conn = open("$root/ScannerCapabilities", "GET")
        try {
            val xml = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            return parseCapabilities(xml)
        } catch (e: IOException) {
            throw ScanException("Scanner not reachable: ${e.message}", e)
        } finally { conn.disconnect() }
    }

    /** Starts a scan from the glass and returns the job URL. */
    fun startJob(settingsXml: String): String {
        val conn = open("$root/ScanJobs", "POST")
        try {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "text/xml")
            val body = settingsXml.toByteArray(Charsets.UTF_8)
            conn.setFixedLengthStreamingMode(body.size)
            conn.outputStream.use { it.write(body) }
            when (val code = conn.responseCode) {
                201 -> return conn.getHeaderField("Location") ?: throw ScanException("Scanner did not return a job")
                503 -> throw ScanException("Scanner is busy — try again in a moment")
                409 -> throw ScanException("Scanner rejected the settings (409)")
                else -> throw ScanException("Scanner answered HTTP $code")
            }
        } catch (e: IOException) {
            throw ScanException("Scan failed: ${e.message}", e)
        } finally { conn.disconnect() }
    }

    /**
     * Downloads the scanned image. Waiting on HTTP 503 while the scanner warms up is part of the
     * protocol (no new scan is started), so it is not a "retry" of anything.
     */
    fun nextDocument(jobUrl: String, timeoutMs: Long = 90_000): ByteArray {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val conn = open(jobUrl.trimEnd('/') + "/NextDocument", "GET")
            try {
                when (val code = conn.responseCode) {
                    200 -> return conn.inputStream.use { it.readBytes() }
                    503 -> if (System.currentTimeMillis() > deadline) throw ScanException("Scanner did not finish in time")
                    404 -> throw ScanException("Scan job disappeared — is the lid or a button in use?")
                    else -> throw ScanException("Scanner answered HTTP $code")
                }
            } catch (e: IOException) {
                throw ScanException("Scan download failed: ${e.message}", e)
            } finally { conn.disconnect() }
            Thread.sleep(1_000)
        }
    }

    fun cancel(jobUrl: String) {
        runCatching { open(jobUrl, "DELETE").apply { responseCode; disconnect() } }
    }

    companion object {
        fun settingsXml(region: Region, dpi: Int, mode: ColorMode, format: String = "image/jpeg"): String = """
            <?xml version="1.0" encoding="UTF-8"?>
            <scan:ScanSettings xmlns:scan="http://schemas.hp.com/imaging/escl/2011/05/03" xmlns:pwg="http://www.pwg.org/schemas/2010/12/sm">
              <pwg:Version>2.6</pwg:Version>
              <scan:Intent>Document</scan:Intent>
              <pwg:ScanRegions>
                <pwg:ScanRegion>
                  <pwg:Height>${region.heightPx300}</pwg:Height>
                  <pwg:ContentRegionUnits>escl:ThreeHundredthsOfInches</pwg:ContentRegionUnits>
                  <pwg:Width>${region.widthPx300}</pwg:Width>
                  <pwg:XOffset>0</pwg:XOffset>
                  <pwg:YOffset>0</pwg:YOffset>
                </pwg:ScanRegion>
              </pwg:ScanRegions>
              <pwg:InputSource>Platen</pwg:InputSource>
              <scan:ColorMode>${mode.escl}</scan:ColorMode>
              <scan:XResolution>$dpi</scan:XResolution>
              <scan:YResolution>$dpi</scan:YResolution>
              <pwg:DocumentFormat>$format</pwg:DocumentFormat>
              <scan:DocumentFormatExt>$format</scan:DocumentFormatExt>
            </scan:ScanSettings>
        """.trimIndent()

        fun parseCapabilities(xml: String): Capabilities {
            val platen = Xml.section(xml, "Platen") ?: xml
            return Capabilities(
                maxWidth = Xml.first(platen, "MaxWidth")?.toIntOrNull() ?: 2550,
                maxHeight = Xml.first(platen, "MaxHeight")?.toIntOrNull() ?: 3508,
                resolutions = Xml.all(platen, "XResolution").mapNotNull { it.toIntOrNull() }.distinct().sorted()
                    .ifEmpty { listOf(75, 150, 300, 600) },
                colorModes = Xml.all(platen, "ColorMode").distinct(),
                formats = (Xml.all(platen, "DocumentFormat") + Xml.all(platen, "DocumentFormatExt")).distinct(),
            )
        }
    }
}

/** Tiny namespace-agnostic XML helpers — scanner/printer XML is small and flat. */
object Xml {
    private fun regex(tag: String) = Regex("<(?:[\\w-]+:)?$tag(?:\\s[^>]*)?>([^<]*)</(?:[\\w-]+:)?$tag>")
    fun first(xml: String, tag: String): String? = regex(tag).find(xml)?.groupValues?.get(1)?.trim()
    fun all(xml: String, tag: String): List<String> = regex(tag).findAll(xml).map { it.groupValues[1].trim() }.toList()
    fun section(xml: String, tag: String): String? =
        Regex("<(?:[\\w-]+:)?$tag(?:\\s[^>]*)?>(.*?)</(?:[\\w-]+:)?$tag>", RegexOption.DOT_MATCHES_ALL)
            .find(xml)?.groupValues?.get(1)
}
