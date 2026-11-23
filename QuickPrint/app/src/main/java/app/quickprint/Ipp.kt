package app.quickprint

import android.graphics.Color
import android.net.Network
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream

data class Marker(val name: String, val color: Int, val level: Int, val type: String)

data class PrinterStatus(
    val name: String,
    val model: String,
    val state: String,
    val stateCode: Int,
    val reasons: List<String>,
    val markers: List<Marker>
)

data class Caps(
    val formats: List<String>,
    val pwgRes: List<Int>,
    val pwgTypes: List<String>,
    val urf: List<String>
)

/** state: 3 pending, 4 held, 5 printing, 6 stopped, 7 cancelled, 8 aborted, 9 completed, -1 job no longer listed */
data class JobState(val state: Int, val reasons: List<String>)

class IppException(msg: String) : Exception(msg)

/** Small IPP client: printer status, capabilities, sending a job and following it. */
object Ipp {
    private val PATHS = listOf("/ipp/print", "/ipp/printer", "/ipp", "/")
    private val pathCache = HashMap<String, String>()

    class V(val tag: Int, val b: ByteArray) {
        fun int(): Int =
            if (b.size >= 4)
                ((b[0].toInt() and 255) shl 24) or ((b[1].toInt() and 255) shl 16) or
                    ((b[2].toInt() and 255) shl 8) or (b[3].toInt() and 255)
            else 0

        fun str(): String = String(b, Charsets.UTF_8)
    }

    class Resp(val status: Int, val attrs: Map<String, List<V>>) {
        fun all(n: String): List<V> = attrs[n] ?: emptyList()
        fun first(n: String): V? = attrs[n]?.firstOrNull()
    }

    private class W {
        val bo = ByteArrayOutputStream()
        val o = DataOutputStream(bo)

        fun head(op: Int) {
            o.writeShort(0x0200)
            o.writeShort(op)
            o.writeInt(1)
            o.writeByte(0x01)
        }

        fun s(tag: Int, name: String, v: String) {
            o.writeByte(tag)
            val n = name.toByteArray(Charsets.UTF_8)
            o.writeShort(n.size)
            o.write(n)
            val b = v.toByteArray(Charsets.UTF_8)
            o.writeShort(b.size)
            o.write(b)
        }

        fun i(tag: Int, name: String, v: Int) {
            o.writeByte(tag)
            val n = name.toByteArray(Charsets.UTF_8)
            o.writeShort(n.size)
            o.write(n)
            o.writeShort(4)
            o.writeInt(v)
        }

        fun req(names: List<String>) {
            for ((k, nm) in names.withIndex()) s(0x44, if (k == 0) "requested-attributes" else "", nm)
        }

        fun group(t: Int) = o.writeByte(t)

        fun bytes(): ByteArray {
            o.writeByte(0x03)
            return bo.toByteArray()
        }
    }

    private fun base(w: W, ip: String, path: String) {
        w.s(0x47, "attributes-charset", "utf-8")
        w.s(0x48, "attributes-natural-language", "en")
        w.s(0x45, "printer-uri", "ipp://$ip:631$path")
    }

    private fun u16(d: ByteArray, p: Int): Int = ((d[p].toInt() and 255) shl 8) or (d[p + 1].toInt() and 255)

    private fun parse(d: ByteArray): Resp {
        val m = LinkedHashMap<String, MutableList<V>>()
        if (d.size < 9) throw IppException("Empty answer from printer")
        val status = u16(d, 2)
        var p = 8
        var last = ""
        while (p < d.size) {
            val tag = d[p].toInt() and 255
            p++
            if (tag == 0x03) break
            if (tag < 0x10) continue
            if (p + 2 > d.size) break
            val nl = u16(d, p); p += 2
            if (p + nl + 2 > d.size) break
            val name = String(d, p, nl, Charsets.UTF_8); p += nl
            val vl = u16(d, p); p += 2
            if (p + vl > d.size) break
            val v = d.copyOfRange(p, p + vl); p += vl
            if (nl > 0) last = name
            m.getOrPut(last) { ArrayList() }.add(V(tag, v))
        }
        return Resp(status, m)
    }

    private fun send(ip: String, net: Network?, path: String, body: ByteArray, doc: File?, readTimeout: Int): Resp {
        val c = Http.open("http://$ip:631$path", net)
        c.connectTimeout = 4000
        c.readTimeout = readTimeout
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/ipp")
        c.setFixedLengthStreamingMode(body.size.toLong() + (doc?.length() ?: 0L))
        try {
            c.outputStream.use { os ->
                os.write(body)
                if (doc != null) FileInputStream(doc).use { it.copyTo(os, 64 * 1024) }
            }
            val code = c.responseCode
            if (code != 200) throw IppException("Printer answered HTTP $code")
            val data = c.inputStream.use { it.readBytes() }
            return parse(data)
        } finally {
            c.disconnect()
        }
    }

    /** For read-only requests: tries the usual printer paths until one answers. */
    private fun query(ip: String, net: Network?, build: (W, String) -> Unit): Resp {
        val cached = pathCache[ip]
        val paths = if (cached != null) listOf(cached) + PATHS.filter { it != cached } else PATHS
        var last: Exception? = null
        for (p in paths) {
            try {
                val w = W()
                build(w, p)
                val r = send(ip, net, p, w.bytes(), null, 8000)
                if (r.status >= 0x0400) {
                    last = IppException(describe(r.status))
                    continue
                }
                pathCache[ip] = p
                return r
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IppException("No response from printer")
    }

    fun describe(s: Int): String = when (s) {
        0x0400 -> "The printer rejected the request"
        0x0401 -> "The printer refused access"
        0x0406 -> "Not found on the printer"
        0x040A -> "The printer doesn't accept this file type"
        0x040B -> "The printer doesn't support one of the options"
        0x0500 -> "Internal printer error"
        0x0506, 0x0507 -> "The printer is busy, try again in a moment"
        else -> String.format("Printer error (code 0x%04x)", s)
    }

    fun colorOf(s: String?): Int {
        if (s == null) return Color.GRAY
        return try {
            Color.parseColor(s.trim())
        } catch (e: Exception) {
            when {
                s.contains("cyan", true) && s.contains("magenta", true) -> Color.GRAY
                s.contains("cyan", true) -> Color.CYAN
                s.contains("magenta", true) -> Color.MAGENTA
                s.contains("yellow", true) -> Color.rgb(240, 200, 0)
                s.contains("black", true) -> Color.BLACK
                else -> Color.GRAY
            }
        }
    }

    fun status(ip: String, net: Network?): PrinterStatus {
        val r = query(ip, net) { w, p ->
            w.head(0x000B)
            base(w, ip, p)
            w.req(listOf(
                "printer-name", "printer-make-and-model", "printer-state", "printer-state-reasons",
                "marker-names", "marker-levels", "marker-colors", "marker-types"
            ))
        }
        val names = r.all("marker-names").map { it.str() }
        val levels = r.all("marker-levels").map { it.int() }
        val colors = r.all("marker-colors").map { it.str() }
        val types = r.all("marker-types").map { it.str() }
        var markers = names.indices.map { i ->
            Marker(names[i], colorOf(colors.getOrNull(i)), levels.getOrNull(i) ?: -1, types.getOrNull(i) ?: "")
        }
        if (markers.isEmpty()) markers = try { Ledm.ink(ip, net) } catch (e: Exception) { emptyList() }
        val code = r.first("printer-state")?.int() ?: 0
        val state = when (code) {
            3 -> "Ready"
            4 -> "Printing"
            5 -> "Stopped"
            else -> "Unknown"
        }
        val reasons = r.all("printer-state-reasons").map { it.str() }.filter { it != "none" }
        return PrinterStatus(
            r.first("printer-name")?.str() ?: "", r.first("printer-make-and-model")?.str() ?: "",
            state, code, reasons, markers
        )
    }

    fun caps(ip: String, net: Network?): Caps {
        val r = query(ip, net) { w, p ->
            w.head(0x000B)
            base(w, ip, p)
            w.req(listOf(
                "document-format-supported", "pwg-raster-document-resolution-supported",
                "pwg-raster-document-type-supported", "urf-supported"
            ))
        }
        return Caps(
            r.all("document-format-supported").map { it.str() },
            r.all("pwg-raster-document-resolution-supported").map { it.int() },
            r.all("pwg-raster-document-type-supported").map { it.str() },
            r.all("urf-supported").map { it.str() }
        )
    }

    private fun path(ip: String, net: Network?): String {
        pathCache[ip]?.let { return it }
        caps(ip, net)
        return pathCache[ip] ?: PATHS[0]
    }

    /** Sends one job. Returns the printer's job id (0 if the printer didn't give one). Never retries by itself. */
    fun printJob(
        ip: String, net: Network?, file: File, format: String, name: String,
        copies: Int, media: String, color: Boolean, quality: Int, raster: Boolean
    ): Int {
        val p = path(ip, net)
        val w = W()
        w.head(0x0002)
        base(w, ip, p)
        w.s(0x42, "requesting-user-name", "QuickPrint")
        w.s(0x42, "job-name", name)
        w.s(0x49, "document-format", format)
        w.group(0x02)
        w.i(0x21, "copies", copies)
        w.s(0x44, "media", media)
        w.s(0x44, "print-color-mode", if (color) "color" else "monochrome")
        w.s(0x44, "sides", "one-sided")
        w.i(0x23, "print-quality", quality)
        if (!raster) w.s(0x44, "print-scaling", "fit")
        val r = send(ip, net, p, w.bytes(), file, 300000)
        if (r.status >= 0x0400) throw IppException(describe(r.status))
        return r.first("job-id")?.int() ?: 0
    }

    fun jobState(ip: String, net: Network?, id: Int): JobState {
        val p = path(ip, net)
        val w = W()
        w.head(0x0009)
        base(w, ip, p)
        w.i(0x21, "job-id", id)
        w.s(0x42, "requesting-user-name", "QuickPrint")
        w.req(listOf("job-state", "job-state-reasons"))
        val r = send(ip, net, p, w.bytes(), null, 6000)
        if (r.status == 0x0406) return JobState(-1, emptyList())
        if (r.status >= 0x0400) throw IppException(describe(r.status))
        return JobState(r.first("job-state")?.int() ?: 0, r.all("job-state-reasons").map { it.str() })
    }
}
