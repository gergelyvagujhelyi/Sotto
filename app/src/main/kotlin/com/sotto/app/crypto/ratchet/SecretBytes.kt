package com.sotto.app.crypto.ratchet

/**
 * Writes secrets into a byte buffer that leaves no copies: when it grows, the old buffer is
 * wiped (ByteArrayOutputStream leaves it behind as it is), and [toByteArray] wipes it.
 * Integers are big-endian; byte arrays and strings are prefixed with their length, -1 for null.
 */
internal class SecretBytesWriter(initialSize: Int = 1024) {
    private var buf = ByteArray(initialSize)
    private var size = 0

    private fun room(n: Int) {
        if (size + n <= buf.size) return
        val bigger = buf.copyOf(maxOf(buf.size * 2, size + n))
        buf.fill(0)
        buf = bigger
    }

    fun byte(v: Int) = apply { room(1); buf[size++] = v.toByte() }
    fun bool(v: Boolean) = byte(if (v) 1 else 0)
    fun int(v: Int) = apply { room(4); for (s in 24 downTo 0 step 8) buf[size++] = (v ushr s).toByte() }
    fun long(v: Long) = apply { room(8); for (s in 56 downTo 0 step 8) buf[size++] = (v ushr s).toByte() }

    fun bytes(v: ByteArray?) = apply {
        if (v == null) { int(-1); return@apply }
        int(v.size)
        room(v.size)
        v.copyInto(buf, size)
        size += v.size
    }

    /** For what isn't secret (ids, public keys in hex): strings can't be wiped. */
    fun string(v: String?) = bytes(v?.toByteArray(Charsets.UTF_8))

    fun strings(v: List<String>) = apply { int(v.size); v.forEach { string(it) } }

    /** The bytes written, exactly; the buffer is wiped. */
    fun toByteArray(): ByteArray = buf.copyOf(size).also { buf.fill(0); size = 0 }
}

/** Reads what [SecretBytesWriter] wrote. Byte arrays are fresh copies; the caller wipes [buf] after. */
internal class SecretBytesReader(private val buf: ByteArray) {
    private var pos = 0

    private fun need(n: Int) {
        if (n < 0 || pos + n > buf.size) throw IllegalArgumentException("Truncated session state")
    }

    fun byte(): Int { need(1); return buf[pos++].toInt() and 0xff }
    fun bool(): Boolean = when (byte()) {
        0 -> false
        1 -> true
        else -> throw IllegalArgumentException("Not a boolean")
    }
    fun int(): Int { need(4); var v = 0; repeat(4) { v = (v shl 8) or (buf[pos++].toInt() and 0xff) }; return v }
    fun long(): Long { need(8); var v = 0L; repeat(8) { v = (v shl 8) or (buf[pos++].toLong() and 0xff) }; return v }

    fun bytesOrNull(): ByteArray? {
        val n = int()
        if (n == -1) return null
        need(n)
        return buf.copyOfRange(pos, pos + n).also { pos += n }
    }

    fun bytes(): ByteArray = bytesOrNull() ?: throw IllegalArgumentException("Missing bytes")
    fun stringOrNull(): String? = bytesOrNull()?.let { String(it, Charsets.UTF_8) }
    fun string(): String = stringOrNull() ?: throw IllegalArgumentException("Missing string")

    fun strings(): MutableList<String> {
        val n = int()
        if (n < 0 || n > buf.size) throw IllegalArgumentException("Bad list length")
        return MutableList(n) { string() }
    }

    fun end() {
        if (pos != buf.size) throw IllegalArgumentException("Trailing bytes in session state")
    }
}
