package com.subzero.teslawaze.aa

import java.io.ByteArrayOutputStream

/** Minimal protobuf writer/reader: just enough for the Android Auto messages we exchange. */
class Pb {
    private val out = ByteArrayOutputStream()

    fun varint(field: Int, value: Long): Pb {
        tag(field, 0)
        writeVarint(value)
        return this
    }

    fun varint(field: Int, value: Int): Pb = varint(field, value.toLong())

    fun bool(field: Int, value: Boolean): Pb = varint(field, if (value) 1L else 0L)

    fun bytes(field: Int, value: ByteArray): Pb {
        tag(field, 2)
        writeVarint(value.size.toLong())
        out.write(value)
        return this
    }

    fun string(field: Int, value: String): Pb = bytes(field, value.toByteArray(Charsets.UTF_8))

    fun msg(field: Int, value: Pb): Pb = bytes(field, value.toByteArray())

    fun toByteArray(): ByteArray = out.toByteArray()

    private fun tag(field: Int, wireType: Int) = writeVarint(((field shl 3) or wireType).toLong())

    private fun writeVarint(value: Long) {
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

    class Parsed(private val fields: Map<Int, List<Any>>) {
        fun long(field: Int): Long? = fields[field]?.firstOrNull() as? Long
        fun int(field: Int): Int? = long(field)?.toInt()
        fun bytes(field: Int): ByteArray? = fields[field]?.firstOrNull() as? ByteArray
        fun string(field: Int): String? = bytes(field)?.toString(Charsets.UTF_8)
        override fun toString(): String = fields.entries.joinToString(", ") { (k, v) ->
            "$k=" + v.joinToString("|") { if (it is ByteArray) "bytes[${it.size}]" else it.toString() }
        }
    }

    companion object {
        fun parse(data: ByteArray, offset: Int = 0): Parsed {
            val fields = HashMap<Int, MutableList<Any>>()
            var i = offset
            fun readVarint(): Long {
                var result = 0L
                var shift = 0
                while (i < data.size) {
                    val b = data[i++].toInt() and 0xFF
                    result = result or ((b and 0x7F).toLong() shl shift)
                    if (b and 0x80 == 0) break
                    shift += 7
                }
                return result
            }
            while (i < data.size) {
                val key = readVarint()
                val field = (key ushr 3).toInt()
                val value: Any = when ((key and 7).toInt()) {
                    0 -> readVarint()
                    1 -> data.copyOfRange(i, i + 8).also { i += 8 }
                    2 -> {
                        val len = readVarint().toInt()
                        data.copyOfRange(i, i + len).also { i += len }
                    }
                    5 -> data.copyOfRange(i, i + 4).also { i += 4 }
                    else -> return Parsed(fields) // unknown wire type: stop parsing
                }
                fields.getOrPut(field) { mutableListOf() }.add(value)
            }
            return Parsed(fields)
        }
    }
}
