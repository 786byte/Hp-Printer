package com.justprintit.core

import com.justprintit.core.ipp.IppClient
import com.justprintit.core.ipp.IppGroup
import com.justprintit.core.ipp.IppMessage
import com.justprintit.core.ipp.IppOp
import com.justprintit.core.ipp.IppTag
import com.justprintit.core.ipp.IppValue
import com.justprintit.core.ipp.PrinterInfo
import com.justprintit.core.ipp.Severity
import com.justprintit.core.layout.ContentBounds
import com.justprintit.core.layout.Imposition
import com.justprintit.core.layout.PageRange
import com.justprintit.core.layout.Sheet
import com.justprintit.core.layout.SheetPlan
import com.justprintit.core.layout.SidesMode
import com.justprintit.core.ledm.HpUsage
import com.justprintit.core.pdf.JpegPdfWriter
import com.justprintit.core.raster.PwgRasterWriter
import com.justprintit.core.scan.EsclClient
import com.justprintit.core.stats.Counters
import com.justprintit.core.stats.PrintRecord
import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IppTest {
    @Test fun roundTripIncludingCollectionsAndMultiValues() {
        val msg = IppMessage.request(IppOp.PRINT_JOB, 7, "ipp://printer/ipp/print")
        msg.groups += IppGroup(IppTag.JOB)
            .integer("copies", 2)
            .keyword("print-color-mode", "monochrome")
            .add("marker-levels", IppTag.INTEGER, IppValue.Int(40), IppValue.Int(-3))
            .add("media-col", IppTag.BEGIN_COLLECTION, IppValue.Collection(listOf(
                com.justprintit.core.ipp.IppAttribute("media-type", IppTag.KEYWORD, listOf(IppValue.Text("stationery"))),
                com.justprintit.core.ipp.IppAttribute("media-size", IppTag.BEGIN_COLLECTION, listOf(IppValue.Collection(listOf(
                    com.justprintit.core.ipp.IppAttribute("x-dimension", IppTag.INTEGER, listOf(IppValue.Int(21000))),
                )))),
            )))
            .add("printer-resolution", IppTag.RESOLUTION, IppValue.Resolution(300, 300, 3))
        val decoded = IppMessage.decode(ByteArrayInputStream(msg.encode()))
        assertEquals(IppOp.PRINT_JOB, decoded.code)
        assertEquals(7, decoded.requestId)
        assertEquals("utf-8", decoded["attributes-charset"]?.string)
        assertEquals(listOf(40, -3), decoded["marker-levels"]?.ints)
        assertEquals("monochrome", decoded["print-color-mode"]?.string)
        val col = decoded["media-col"]!!.values.single() as IppValue.Collection
        assertEquals("stationery", col.members[0].string)
        val size = col.members[1].values.single() as IppValue.Collection
        assertEquals(21000, size.members.single().int)
        assertEquals(IppValue.Resolution(300, 300, 3), decoded["printer-resolution"]?.values?.single())
    }

    @Test fun printerInfoParsesInkAndReasons() {
        val resp = IppMessage(0, 1)
        resp.groups += IppGroup(IppTag.PRINTER)
            .text("printer-make-and-model", "HP Smart Tank 580-590 series")
            .integer("printer-state", 5, IppTag.ENUM)
            .keyword("printer-state-reasons", "media-empty-error", "marker-supply-low-warning")
            .add("marker-names", IppTag.NAME, IppValue.Text("Black ink"), IppValue.Text("Tri-color ink"))
            .add("marker-colors", IppTag.NAME, IppValue.Text("#000000"), IppValue.Text("#00FFFF#FF00FF#FFFF00"))
            .add("marker-levels", IppTag.INTEGER, IppValue.Int(70), IppValue.Int(8))
            .add("marker-low-levels", IppTag.INTEGER, IppValue.Int(10), IppValue.Int(10))
            .keyword("document-format-supported", "application/octet-stream", "image/pwg-raster", "image/jpeg")
            .add("media-left-margin-supported", IppTag.INTEGER, IppValue.Int(0), IppValue.Int(300))
            .add("media-right-margin-supported", IppTag.INTEGER, IppValue.Int(0), IppValue.Int(300))
            .add("media-top-margin-supported", IppTag.INTEGER, IppValue.Int(0), IppValue.Int(300))
            .add("media-bottom-margin-supported", IppTag.INTEGER, IppValue.Int(0), IppValue.Int(300))
        val info = PrinterInfo.parse(IppMessage.decode(ByteArrayInputStream(resp.encode())))
        assertEquals("Stopped", info.stateText)
        assertFalse(info.canPrint)
        assertEquals(Severity.ERROR, info.reasons[0].severity)
        assertTrue(info.reasons[0].text.contains("Out of paper"))
        assertEquals(listOf("#00FFFF", "#FF00FF", "#FFFF00"), info.markers[1].colors)
        assertTrue(info.markers[1].isLow)
        assertFalse(info.markers[0].isLow)
        assertTrue(info.supportsPwg)
        assertTrue(info.supportsBorderless)
        assertEquals(300, info.minMargin)
    }

    @Test fun clientSendsDocumentOnceAndReadsJobId() {
        val calls = AtomicInteger()
        var received = ByteArray(0)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/ipp/print") { ex ->
            calls.incrementAndGet()
            val body = ex.requestBody.readBytes()
            val req = IppMessage.decode(ByteArrayInputStream(body))
            received = body
            val resp = IppMessage(0, req.requestId)
            resp.groups += IppGroup(IppTag.OPERATION).text("attributes-charset", "utf-8", IppTag.CHARSET)
            resp.groups += IppGroup(IppTag.JOB).integer("job-id", 42)
            val out = resp.encode()
            ex.sendResponseHeaders(200, out.size.toLong()); ex.responseBody.use { it.write(out) }
        }
        server.start()
        try {
            val client = IppClient("127.0.0.1", server.address.port)
            val doc = ByteArray(300_000) { (it % 251).toByte() }
            val id = client.printJob("test", "image/pwg-raster", { integer("copies", 1) }) { it.write(doc) }
            assertEquals(42, id)
            assertEquals(1, calls.get())
            assertTrue(received.size > doc.size)
            assertContentEquals(doc, received.copyOfRange(received.size - doc.size, received.size))
        } finally { server.stop(0) }
    }

    @Test fun connectionFailureIsReportedNotRetried() {
        val client = IppClient("127.0.0.1", 9, connectTimeoutMs = 500)
        assertFailsWith<com.justprintit.core.ipp.IppException> { client.getPrinterAttributes() }
    }
}

class RasterTest {
    @Test fun encoderIsLossless() {
        val rnd = Random(1)
        repeat(200) {
            val bpp = if (rnd.nextBoolean()) 1 else 3
            val pixels = rnd.nextInt(1, 600)
            val row = ByteArray(pixels * bpp)
            var i = 0
            while (i < pixels) { // mix of runs and noise
                val run = rnd.nextInt(1, 300)
                val v = ByteArray(bpp).also { rnd.nextBytes(it) }
                val noisy = rnd.nextBoolean()
                for (k in 0 until run) {
                    if (i >= pixels) break
                    if (noisy) rnd.nextBytes(v)
                    System.arraycopy(v, 0, row, i * bpp, bpp); i++
                }
            }
            val enc = ByteArrayOutputStream()
            PwgRasterWriter.encodeRow(row, bpp, enc)
            val bytes = enc.toByteArray()
            val dest = ByteArray(row.size)
            val end = PwgRasterWriter.decodeRow(bytes, 0, pixels, bpp, dest)
            assertEquals(bytes.size, end)
            assertContentEquals(row, dest)
        }
    }

    @Test fun streamHasHeaderAndRepeatedLinesCollapse() {
        val out = ByteArrayOutputStream()
        val w = PwgRasterWriter(out)
        val setup = PwgRasterWriter.PageSetup(100, 300, 300, gray = false, mediaName = "iso_a4_210x297mm", totalPages = 1)
        w.beginPage(setup)
        val white = IntArray(100) { 0xFFFFFFFF.toInt() }
        repeat(300) { w.writeRow(white) }
        w.endPage()
        val b = out.toByteArray()
        assertEquals("RaS2", String(b, 0, 4))
        assertEquals("PwgRaster", String(b, 4, 9))
        val hdr = java.nio.ByteBuffer.wrap(b, 4, 1796).slice()
        assertEquals(100, hdr.getInt(372)); assertEquals(300, hdr.getInt(376)); assertEquals(19, hdr.getInt(400))
        assertEquals(300, hdr.getInt(392))
        // 300 identical rows -> 2 line records (256 + 44) of 1 repeat byte + 1 run byte + 3 pixel bytes
        assertEquals(4 + 1796 + 2 * 5, b.size)
    }

    @Test fun grayscaleUsesLuma() {
        assertEquals(255, PwgRasterWriter.luma(0x00000000)) // transparent -> white
        assertEquals(0, PwgRasterWriter.luma(0xFF000000.toInt()))
        assertEquals(76, PwgRasterWriter.luma(0xFFFF0000.toInt()))
    }
}

class LayoutTest {
    @Test fun pageRanges() {
        assertEquals((0..9).toList(), PageRange.parse("", 10))
        assertEquals(listOf(0, 1, 2, 4, 7, 8, 9), PageRange.parse("1-3, 5, 8-", 10))
        assertEquals(listOf(0, 1, 2, 3), PageRange.parse("-4", 10))
        assertEquals(listOf(4, 3, 2), PageRange.parse("5–3", 10))
        assertFailsWith<PageRange.Invalid> { PageRange.parse("11", 10) }
        assertFailsWith<PageRange.Invalid> { PageRange.parse("a-b", 10) }
        assertFailsWith<PageRange.Invalid> { PageRange.parse("0", 10) }
    }

    @Test fun twoUpOnPortraitRotates() {
        val p = Imposition.plan(2, 210.0, 297.0, 5.0, 4.0, 210.0 / 297.0)
        assertTrue(p.rotated)
        assertEquals(2, p.cells.size)
        assertTrue(p.cells[0].left < p.cells[1].left) // side by side in the reading frame
    }

    @Test fun fourUpStaysPortrait() {
        val p = Imposition.plan(4, 210.0, 297.0, 5.0, 4.0, 210.0 / 297.0)
        assertFalse(p.rotated)
        assertEquals(4, p.cells.size)
        val one = Imposition.plan(1, 210.0, 297.0, 5.0, 0.0, 210.0 / 297.0)
        assertFalse(one.rotated)
    }

    @Test fun fitAndZoom() {
        val cell = com.justprintit.core.layout.Box(0.0, 0.0, 100.0, 200.0)
        val f = Imposition.fit(50.0, 50.0, cell, 1.0)
        assertEquals(100.0, f.width, 1e-9); assertEquals(50.0, f.top, 1e-9)
        val z = Imposition.fit(50.0, 50.0, cell, 0.5)
        assertEquals(50.0, z.width, 1e-9); assertEquals(25.0, z.left, 1e-9)
    }

    @Test fun contentBoundsFindsSmallContent() {
        val w = 100; val h = 200
        val px = IntArray(w * h) { 0xFFFFFFFF.toInt() }
        for (y in 50 until 70) for (x in 20 until 40) px[y * w + x] = 0xFF000000.toInt()
        val b = assertNotNull(ContentBounds.find(px, w, h, padding = 0.0))
        assertEquals(0.20, b.left, 1e-9); assertEquals(0.40, b.right, 1e-9)
        assertEquals(0.25, b.top, 1e-9); assertEquals(0.35, b.bottom, 1e-9)
        assertNull(ContentBounds.find(IntArray(16) { -1 }, 4, 4))
        assertTrue(ContentBounds.hasColor(intArrayOf(0xFFFF0000.toInt()), 0, 1))
        assertFalse(ContentBounds.hasColor(intArrayOf(0xFF808080.toInt()), 0, 1))
    }

    @Test fun oddEvenSheets() {
        val sheets = SheetPlan.group((0 until 7).toList(), 1)
        assertEquals(listOf(0, 2, 4, 6), SheetPlan.select(sheets, SidesMode.ODD).map { it.pages.single() })
        assertEquals(listOf(1, 3, 5), SheetPlan.select(sheets, SidesMode.EVEN).map { it.pages.single() })
        val twoUp = SheetPlan.group((0 until 7).toList(), 2)
        assertEquals(listOf(listOf(0, 1), listOf(2, 3), listOf(4, 5), listOf(6)), twoUp.map { it.pages })
    }

    @Test fun guidedDuplexOddCount() {
        val d = SheetPlan.duplex(SheetPlan.group((0 until 5).toList(), 1))
        assertEquals(listOf(0, 2, 4), d.fronts.map { it.pages.single() })
        // Stack comes back with page 5 on top: blank for it, then 4 onto 3, 2 onto 1.
        assertEquals(listOf<List<Int>>(emptyList(), listOf(3), listOf(1)), d.backs.map { it.pages })
        assertTrue(d.backs.filterNot { it.isBlank }.all { it.rotate180 })
    }

    @Test fun guidedDuplexEvenCount() {
        val d = SheetPlan.duplex(SheetPlan.group((0 until 4).toList(), 1))
        assertEquals(listOf(listOf(3), listOf(1)), d.backs.map { it.pages })
    }

    @Test fun copiesAreCollated() {
        val s = listOf(Sheet(listOf(0)), Sheet(listOf(1)))
        assertEquals(listOf(0, 1, 0, 1), SheetPlan.withCopies(s, 2).map { it.pages.single() })
    }
}

class ScanAndUsageTest {
    @Test fun settingsXmlIsWellFormed() {
        val xml = EsclClient.settingsXml(EsclClient.Region.A4, 300, EsclClient.ColorMode.COLOR)
        assertTrue(xml.startsWith("<?xml"))
        javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml.byteInputStream())
        assertTrue("<scan:XResolution>300</scan:XResolution>" in xml)
    }

    @Test fun capabilitiesParse() {
        val xml = """<scan:ScannerCapabilities xmlns:scan="x" xmlns:pwg="y"><scan:Platen><scan:PlatenInputCaps>
            <scan:MaxWidth>2550</scan:MaxWidth><scan:MaxHeight>3508</scan:MaxHeight>
            <scan:SettingProfiles><scan:SettingProfile><scan:ColorModes><scan:ColorMode>RGB24</scan:ColorMode><scan:ColorMode>Grayscale8</scan:ColorMode></scan:ColorModes>
            <scan:DocumentFormats><pwg:DocumentFormat>image/jpeg</pwg:DocumentFormat><pwg:DocumentFormat>application/pdf</pwg:DocumentFormat></scan:DocumentFormats>
            <scan:SupportedResolutions><scan:DiscreteResolutions><scan:DiscreteResolution><scan:XResolution>300</scan:XResolution><scan:YResolution>300</scan:YResolution></scan:DiscreteResolution>
            <scan:DiscreteResolution><scan:XResolution>75</scan:XResolution><scan:YResolution>75</scan:YResolution></scan:DiscreteResolution></scan:DiscreteResolutions></scan:SupportedResolutions>
            </scan:SettingProfile></scan:SettingProfiles></scan:PlatenInputCaps></scan:Platen></scan:ScannerCapabilities>"""
        val c = EsclClient.parseCapabilities(xml)
        assertEquals(2550, c.maxWidth); assertEquals(listOf(75, 300), c.resolutions)
        assertEquals(listOf("RGB24", "Grayscale8"), c.colorModes)
        assertTrue("application/pdf" in c.formats)
    }

    @Test fun hpUsageParse() {
        val xml = """<pudyn:ProductUsageDyn><pudyn:PrinterSubunit><dd:TotalImpressions PEID="5082">1523</dd:TotalImpressions>
            <dd:MonochromeImpressions>1200</dd:MonochromeImpressions><dd:ColorImpressions>323</dd:ColorImpressions></pudyn:PrinterSubunit>
            <pudyn:ScannerEngineSubunit><dd:ScanImages>88</dd:ScanImages></pudyn:ScannerEngineSubunit></pudyn:ProductUsageDyn>"""
        assertEquals(HpUsage(1523, 323, 1200, 88), HpUsage.parse(xml))
    }

    @Test fun jpegPdf() {
        // Minimal JPEG header with a SOF0 segment (enough for size detection).
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 4, 0, 0,
            0xFF.toByte(), 0xC0.toByte(), 0, 11, 8, 0x0D, 0xB4.toByte(), 0x09, 0xB0.toByte(), 3, 0, 0, 0, 0, 0xFF.toByte(), 0xD9.toByte())
        val info = JpegPdfWriter.readInfo(jpeg)
        assertEquals(2480, info.width); assertEquals(3508, info.height); assertEquals(3, info.components)
        val out = ByteArrayOutputStream()
        JpegPdfWriter.write(listOf(jpeg, jpeg), 300, out)
        val text = String(out.toByteArray(), Charsets.ISO_8859_1)
        assertTrue(text.startsWith("%PDF-1.4"))
        assertTrue("/Count 2" in text)
        assertTrue("/MediaBox [0 0 595.20 841.92]" in text)
        // xref offsets must point at the objects
        val xrefPos = text.substringAfterLast("startxref\n").substringBefore("\n").toInt()
        val entries = text.substring(xrefPos).lines().drop(3).takeWhile { it.endsWith(" n ") }
        entries.forEachIndexed { i, e -> assertTrue(text.startsWith("${i + 1} 0 obj", e.take(10).toInt())) }
    }
}

class CountersTest {
    @Test fun summarizesByDayMonthAllTime() {
        val zone = ZoneId.of("Asia/Dhaka")
        val now = LocalDate.of(2026, 11, 18).atTime(12, 0).atZone(zone).toInstant()
        fun at(d: LocalDate) = d.atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
        val records = listOf(
            PrintRecord(at(LocalDate.of(2026, 11, 18)), 2, 5),
            PrintRecord(at(LocalDate.of(2026, 11, 18)), 0, 3),
            PrintRecord(at(LocalDate.of(2026, 11, 2)), 1, 1),
            PrintRecord(at(LocalDate.of(2026, 10, 30)), 4, 0),
        )
        val s = Counters.summarize(records, zone, now)
        assertEquals(10, s.today.total); assertEquals(2, s.today.color)
        assertEquals(12, s.thisMonth.total); assertEquals(3, s.thisMonth.color)
        assertEquals(16, s.allTime.total)
        assertEquals(30, s.daily.size); assertEquals(LocalDate.of(2026, 11, 18), s.daily.last().first)
        assertEquals(4, s.monthly[10].second.color)
        assertEquals(Instant.EPOCH.atZone(zone).year, 1970)
    }
}
