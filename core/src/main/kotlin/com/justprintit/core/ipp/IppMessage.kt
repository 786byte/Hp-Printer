package com.justprintit.core.ipp

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream

/** IPP/2.0 binary encoding (RFC 8010). Only what a home printer app needs, but complete enough to be robust. */
object IppTag {
    // Delimiters
    const val OPERATION = 0x01
    const val JOB = 0x02
    const val END = 0x03
    const val PRINTER = 0x04
    const val UNSUPPORTED_GROUP = 0x05

    // Out-of-band
    const val UNSUPPORTED = 0x10
    const val UNKNOWN = 0x12
    const val NO_VALUE = 0x13

    // Values
    const val INTEGER = 0x21
    const val BOOLEAN = 0x22
    const val ENUM = 0x23
    const val OCTET_STRING = 0x30
    const val DATE_TIME = 0x31
    const val RESOLUTION = 0x32
    const val RANGE = 0x33
    const val BEGIN_COLLECTION = 0x34
    const val TEXT_WITH_LANG = 0x35
    const val NAME_WITH_LANG = 0x36
    const val END_COLLECTION = 0x37
    const val TEXT = 0x41
    const val NAME = 0x42
    const val KEYWORD = 0x44
    const val URI = 0x45
    const val URI_SCHEME = 0x46
    const val CHARSET = 0x47
    const val LANGUAGE = 0x48
    const val MIME_TYPE = 0x49
    const val MEMBER_NAME = 0x4A
}

object IppOp {
    const val PRINT_JOB = 0x0002
    const val VALIDATE_JOB = 0x0004
    const val CANCEL_JOB = 0x0008
    const val GET_JOB_ATTRIBUTES = 0x0009
    const val GET_PRINTER_ATTRIBUTES = 0x000B
}

sealed interface IppValue {
    data class Int(val value: kotlin.Int) : IppValue
    data class Bool(val value: Boolean) : IppValue
    data class Text(val value: String) : IppValue
    data class Resolution(val x: kotlin.Int, val y: kotlin.Int, val units: kotlin.Int) : IppValue
    data class Range(val low: kotlin.Int, val high: kotlin.Int) : IppValue
    data class Collection(val members: List<IppAttribute>) : IppValue
    class Bytes(val value: ByteArray) : IppValue
    data object NoValue : IppValue
}

data class IppAttribute(val name: String, val tag: Int, val values: List<IppValue>) {
    val ints: List<Int> get() = values.mapNotNull { (it as? IppValue.Int)?.value }
    val strings: List<String> get() = values.mapNotNull { (it as? IppValue.Text)?.value }
    val int: Int? get() = ints.firstOrNull()
    val string: String? get() = strings.firstOrNull()
}

class IppGroup(val tag: Int, val attributes: MutableList<IppAttribute> = mutableListOf()) {
    operator fun get(name: String): IppAttribute? = attributes.firstOrNull { it.name == name }

    fun add(name: String, tag: Int, vararg values: IppValue) = apply { attributes += IppAttribute(name, tag, values.toList()) }
    fun keyword(name: String, vararg v: String) = add(name, IppTag.KEYWORD, *v.map { IppValue.Text(it) }.toTypedArray())
    fun text(name: String, v: String, tag: Int = IppTag.TEXT) = add(name, tag, IppValue.Text(v))
    fun integer(name: String, v: Int, tag: Int = IppTag.INTEGER) = add(name, tag, IppValue.Int(v))
    fun bool(name: String, v: Boolean) = add(name, IppTag.BOOLEAN, IppValue.Bool(v))
}

class IppMessage(
    /** Operation id for requests, status code for responses. */
    val code: Int,
    val requestId: Int,
    val groups: MutableList<IppGroup> = mutableListOf(),
    val versionMajor: Int = 2,
    val versionMinor: Int = 0,
) {
    val isSuccess: Boolean get() = code in 0x0000..0x00FF

    fun group(tag: Int): IppGroup? = groups.firstOrNull { it.tag == tag }
    fun groupsOf(tag: Int): List<IppGroup> = groups.filter { it.tag == tag }
    /** First attribute with [name] in any group. */
    operator fun get(name: String): IppAttribute? = groups.firstNotNullOfOrNull { it[name] }

    fun encode(): ByteArray {
        val bytes = ByteArrayOutputStream()
        val out = DataOutputStream(bytes)
        out.writeByte(versionMajor); out.writeByte(versionMinor)
        out.writeShort(code); out.writeInt(requestId)
        for (g in groups) {
            out.writeByte(g.tag)
            for (a in g.attributes) writeAttribute(out, a.name, a.tag, a.values)
        }
        out.writeByte(IppTag.END)
        return bytes.toByteArray()
    }

    companion object {
        /** A request pre-filled with the mandatory charset / language operation attributes. */
        fun request(operation: Int, requestId: Int, printerUri: String): IppMessage {
            val op = IppGroup(IppTag.OPERATION)
                .text("attributes-charset", "utf-8", IppTag.CHARSET)
                .text("attributes-natural-language", "en", IppTag.LANGUAGE)
                .text("printer-uri", printerUri, IppTag.URI)
            return IppMessage(operation, requestId, mutableListOf(op))
        }

        private fun writeAttribute(out: DataOutputStream, name: String, tag: Int, values: List<IppValue>) {
            values.ifEmpty { listOf(IppValue.NoValue) }.forEachIndexed { i, v ->
                writeValue(out, if (i == 0) name else "", if (v is IppValue.NoValue) IppTag.NO_VALUE else tag, v)
            }
        }

        private fun writeValue(out: DataOutputStream, name: String, tag: Int, v: IppValue) {
            val nameBytes = name.toByteArray(Charsets.UTF_8)
            out.writeByte(if (v is IppValue.Collection) IppTag.BEGIN_COLLECTION else tag)
            out.writeShort(nameBytes.size); out.write(nameBytes)
            when (v) {
                is IppValue.Int -> { out.writeShort(4); out.writeInt(v.value) }
                is IppValue.Bool -> { out.writeShort(1); out.writeByte(if (v.value) 1 else 0) }
                is IppValue.Text -> { val b = v.value.toByteArray(Charsets.UTF_8); out.writeShort(b.size); out.write(b) }
                is IppValue.Resolution -> { out.writeShort(9); out.writeInt(v.x); out.writeInt(v.y); out.writeByte(v.units) }
                is IppValue.Range -> { out.writeShort(8); out.writeInt(v.low); out.writeInt(v.high) }
                is IppValue.Bytes -> { out.writeShort(v.value.size); out.write(v.value) }
                is IppValue.NoValue -> out.writeShort(0)
                is IppValue.Collection -> {
                    out.writeShort(0)
                    for (m in v.members) {
                        val mn = m.name.toByteArray(Charsets.UTF_8)
                        out.writeByte(IppTag.MEMBER_NAME); out.writeShort(0); out.writeShort(mn.size); out.write(mn)
                        for (mv in m.values) writeValue(out, "", m.tag, mv)
                    }
                    out.writeByte(IppTag.END_COLLECTION); out.writeShort(0); out.writeShort(0)
                }
            }
        }

        fun decode(input: InputStream): IppMessage {
            val inp = DataInputStream(input.buffered())
            val major = inp.readUnsignedByte()
            val minor = inp.readUnsignedByte()
            val msg = IppMessage(inp.readUnsignedShort(), inp.readInt(), versionMajor = major, versionMinor = minor)
            var group: IppGroup? = null
            while (true) {
                val tag = try { inp.readUnsignedByte() } catch (e: EOFException) { break }
                if (tag == IppTag.END) break
                if (tag < 0x10) { group = IppGroup(tag).also { msg.groups += it }; continue }
                val g = group ?: throw IppException("Value before attribute group")
                val name = readString(inp)
                val value = readValue(inp, tag)
                if (name.isEmpty()) {
                    val last = g.attributes.removeLastOrNull() ?: throw IppException("Additional value without attribute")
                    g.attributes += last.copy(values = last.values + value)
                } else {
                    g.attributes += IppAttribute(name, tag, listOf(value))
                }
            }
            return msg
        }

        private fun readString(inp: DataInputStream): String {
            val n = inp.readUnsignedShort()
            return String(ByteArray(n).also { inp.readFully(it) }, Charsets.UTF_8)
        }

        private fun readValue(inp: DataInputStream, tag: Int): IppValue {
            if (tag == IppTag.BEGIN_COLLECTION) {
                inp.skipBytes(inp.readUnsignedShort())
                return readCollection(inp)
            }
            val len = inp.readUnsignedShort()
            val raw = ByteArray(len).also { inp.readFully(it) }
            return when {
                tag in 0x10..0x1F -> IppValue.NoValue
                (tag == IppTag.INTEGER || tag == IppTag.ENUM) && len == 4 -> IppValue.Int(raw.int(0))
                tag == IppTag.BOOLEAN && len == 1 -> IppValue.Bool(raw[0].toInt() != 0)
                tag == IppTag.RESOLUTION && len == 9 -> IppValue.Resolution(raw.int(0), raw.int(4), raw[8].toInt())
                tag == IppTag.RANGE && len == 8 -> IppValue.Range(raw.int(0), raw.int(4))
                tag == IppTag.TEXT_WITH_LANG || tag == IppTag.NAME_WITH_LANG -> {
                    val langLen = ((raw[0].toInt() and 0xFF) shl 8) or (raw[1].toInt() and 0xFF)
                    val p = 2 + langLen
                    val textLen = ((raw[p].toInt() and 0xFF) shl 8) or (raw[p + 1].toInt() and 0xFF)
                    IppValue.Text(String(raw, p + 2, textLen, Charsets.UTF_8))
                }
                tag in 0x40..0x5F || tag == IppTag.OCTET_STRING -> IppValue.Text(String(raw, Charsets.UTF_8))
                else -> IppValue.Bytes(raw)
            }
        }

        private fun readCollection(inp: DataInputStream): IppValue.Collection {
            val members = mutableListOf<IppAttribute>()
            var current: String? = null
            while (true) {
                val tag = inp.readUnsignedByte()
                inp.skipBytes(inp.readUnsignedShort()) // name is always empty inside a collection
                when (tag) {
                    IppTag.END_COLLECTION -> { inp.skipBytes(inp.readUnsignedShort()); return IppValue.Collection(members) }
                    IppTag.MEMBER_NAME -> current = readString(inp)
                    else -> {
                        val v = readValue(inp, tag)
                        val idx = members.indexOfLast { it.name == current }
                        if (idx >= 0 && idx == members.lastIndex) members[idx] = members[idx].copy(values = members[idx].values + v)
                        else members += IppAttribute(current ?: "", tag, listOf(v))
                    }
                }
            }
        }

        private fun ByteArray.int(o: Int): Int =
            ((this[o].toInt() and 0xFF) shl 24) or ((this[o + 1].toInt() and 0xFF) shl 16) or
                ((this[o + 2].toInt() and 0xFF) shl 8) or (this[o + 3].toInt() and 0xFF)
    }
}

class IppException(message: String, cause: Throwable? = null) : Exception(message, cause)
