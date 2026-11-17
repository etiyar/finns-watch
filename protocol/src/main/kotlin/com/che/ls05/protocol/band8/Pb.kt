package com.che.ls05.protocol.band8

import java.io.ByteArrayOutputStream

/** Bozuk ya da beklenmeyen protobuf verisi. */
class PbException(message: String) : Exception(message)

/**
 * Küçük protobuf yazıcı. Yalnızca Band 8 için gereken türler: varint, zigzag,
 * uzunluklu alan (bytes, string, iç mesaj) ve 32 bitlik float.
 * Alanlar çağrıldığı sırayla yazılır.
 */
class PbWriter {
    private val out = ByteArrayOutputStream()

    private fun rawVarint(value: Long) {
        var v = value
        while (true) {
            if (v and 0x7FL.inv() == 0L) {
                out.write(v.toInt())
                return
            }
            out.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
    }

    private fun tag(field: Int, wire: Int) {
        require(field in 1..0x1FFFFFFF) { "alan numarası geçersiz: $field" }
        rawVarint(((field.toLong()) shl 3) or wire.toLong())
    }

    /** uint32 / uint64 / int (negatif değer 10 baytlık varint olur). */
    fun uint(field: Int, value: Long): PbWriter {
        tag(field, WIRE_VARINT); rawVarint(value); return this
    }

    fun uint(field: Int, value: Int): PbWriter = uint(field, value.toLong() and 0xFFFFFFFFL)

    fun bool(field: Int, value: Boolean): PbWriter = uint(field, if (value) 1L else 0L)

    /** sint32: zigzag kodlu. */
    fun sint(field: Int, value: Int): PbWriter {
        val z = (value shl 1) xor (value shr 31)
        return uint(field, z.toLong() and 0xFFFFFFFFL)
    }

    fun bytes(field: Int, value: ByteArray): PbWriter {
        tag(field, WIRE_LEN); rawVarint(value.size.toLong()); out.write(value); return this
    }

    fun string(field: Int, value: String): PbWriter = bytes(field, value.toByteArray(Charsets.UTF_8))

    fun message(field: Int, value: PbWriter): PbWriter = bytes(field, value.toByteArray())

    fun float(field: Int, value: Float): PbWriter {
        tag(field, WIRE_I32)
        val b = java.lang.Float.floatToIntBits(value)
        for (i in 0 until 4) out.write((b ushr (8 * i)) and 0xFF)
        return this
    }

    fun toByteArray(): ByteArray = out.toByteArray()

    companion object {
        const val WIRE_VARINT = 0
        const val WIRE_I64 = 1
        const val WIRE_LEN = 2
        const val WIRE_I32 = 5
    }
}

/** Ayrıştırılmış tek alan. Varint ve sabit uzunluklu alanlarda [value], uzunluklu alanlarda [data] dolu. */
class PbField(val number: Int, val wire: Int, val value: Long, val data: ByteArray?)

/**
 * Ayrıştırılmış protobuf mesajı. Alan sırası korunur, tekrarlı alanlar [all] ile okunur.
 * Bilinmeyen alanlar sessizce taşınır.
 */
class PbMessage private constructor(val fields: List<PbField>) {

    fun all(field: Int): List<PbField> = fields.filter { it.number == field }
    fun has(field: Int): Boolean = fields.any { it.number == field }
    private fun last(field: Int): PbField? = fields.lastOrNull { it.number == field }

    fun uint(field: Int): Long? = last(field)?.takeIf { it.wire == PbWriter.WIRE_VARINT }?.value
    fun int(field: Int): Int? = uint(field)?.toInt()
    fun bool(field: Int): Boolean? = uint(field)?.let { it != 0L }

    fun sint(field: Int): Int? = uint(field)?.let {
        val v = it.toInt()
        (v ushr 1) xor -(v and 1)
    }

    fun bytes(field: Int): ByteArray? = last(field)?.takeIf { it.wire == PbWriter.WIRE_LEN }?.data
    fun string(field: Int): String? = bytes(field)?.toString(Charsets.UTF_8)
    fun message(field: Int): PbMessage? = bytes(field)?.let { parse(it) }
    fun messages(field: Int): List<PbMessage> =
        all(field).filter { it.wire == PbWriter.WIRE_LEN }.map { parse(it.data!!) }

    fun float(field: Int): Float? = last(field)?.takeIf { it.wire == PbWriter.WIRE_I32 }?.let {
        java.lang.Float.intBitsToFloat(it.value.toInt())
    }

    companion object {
        /** @throws PbException veri bozuksa. */
        fun parse(b: ByteArray): PbMessage {
            val out = ArrayList<PbField>()
            var i = 0
            fun varint(): Long {
                var v = 0L
                var shift = 0
                while (true) {
                    if (i >= b.size) throw PbException("varint yarım kaldı")
                    if (shift >= 64) throw PbException("varint çok uzun")
                    val c = b[i++].toInt() and 0xFF
                    v = v or ((c and 0x7F).toLong() shl shift)
                    if (c < 0x80) return v
                    shift += 7
                }
            }
            while (i < b.size) {
                val tag = varint()
                val field = (tag ushr 3).toInt()
                val wire = (tag and 7).toInt()
                if (field <= 0) throw PbException("alan numarası 0")
                when (wire) {
                    PbWriter.WIRE_VARINT -> out.add(PbField(field, wire, varint(), null))
                    PbWriter.WIRE_I64 -> {
                        if (i + 8 > b.size) throw PbException("fixed64 yarım")
                        var v = 0L
                        for (k in 0 until 8) v = v or ((b[i + k].toLong() and 0xFF) shl (8 * k))
                        i += 8
                        out.add(PbField(field, wire, v, null))
                    }
                    PbWriter.WIRE_I32 -> {
                        if (i + 4 > b.size) throw PbException("fixed32 yarım")
                        var v = 0L
                        for (k in 0 until 4) v = v or ((b[i + k].toLong() and 0xFF) shl (8 * k))
                        i += 4
                        out.add(PbField(field, wire, v, null))
                    }
                    PbWriter.WIRE_LEN -> {
                        val len = varint()
                        if (len < 0 || len > b.size - i) throw PbException("uzunluk taşıyor")
                        out.add(PbField(field, wire, len, b.copyOfRange(i, i + len.toInt())))
                        i += len.toInt()
                    }
                    else -> throw PbException("desteklenmeyen tel türü $wire")
                }
            }
            return PbMessage(out)
        }

        /** Bozuk veride null döner. */
        fun parseOrNull(b: ByteArray): PbMessage? = try { parse(b) } catch (e: PbException) { null }
    }
}
