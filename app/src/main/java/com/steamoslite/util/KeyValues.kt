package com.steamoslite.util

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Valve's KeyValues, the format of the Steam client's own files: the text form (.vdf, .acf) and the
 * binary form (appinfo.vdf, the UserGameStats*.bin caches). Both come back as nested maps: a value
 * is a String, a number, or another map. Keys keep their spelling; [get] looks them up ignoring case,
 * since the client is not consistent about it ("Software" / "software", "Apps" / "apps").
 */
object KeyValues {
    /** The value at [path] under [root], matching each key ignoring case; null when any step is missing. */
    fun get(root: Any?, vararg path: String): Any? {
        var node = root
        for (key in path) {
            val map = node as? Map<*, *> ?: return null
            node = map[key] ?: map.entries.firstOrNull { (it.key as? String).equals(key, ignoreCase = true) }?.value
        }
        return node
    }

    fun map(root: Any?, vararg path: String): Map<String, Any>? {
        @Suppress("UNCHECKED_CAST")
        return get(root, *path) as? Map<String, Any>
    }

    fun string(root: Any?, vararg path: String): String? = get(root, *path)?.let { if (it is Map<*, *>) null else it.toString() }

    fun long(root: Any?, vararg path: String): Long? = when (val v = get(root, *path)) {
        is Number -> v.toLong()
        is String -> v.trim().toLongOrNull()
        else -> null
    }

    // ---------------------------------------------------------------- text

    /** Parses text KeyValues: `"key" "value"` pairs and `"key" { ... }` blocks, // comments allowed. */
    fun parseText(text: String): Map<String, Any> {
        val tokens = tokenize(text)
        var i = 0
        fun block(): Map<String, Any> {
            val out = LinkedHashMap<String, Any>()
            while (i < tokens.size) {
                val key = tokens[i++]
                if (key == CLOSE) return out
                if (key == OPEN) continue // malformed; skip
                if (i >= tokens.size) break
                val next = tokens[i]
                // A platform conditional such as [$WIN32] after a key is skipped.
                if (next.startsWith("[$") && next.endsWith("]")) { i++; continue }
                i++
                out[key] = if (next == OPEN) block() else next
                if (i < tokens.size && tokens[i].startsWith("[$")) i++
            }
            return out
        }
        return block()
    }

    private const val OPEN = "\u0000{"
    private const val CLOSE = "\u0000}"

    private fun tokenize(text: String): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c.isWhitespace() -> i++
                c == '/' && i + 1 < text.length && text[i + 1] == '/' -> {
                    while (i < text.length && text[i] != '\n') i++
                }
                c == '{' -> { out += OPEN; i++ }
                c == '}' -> { out += CLOSE; i++ }
                c == '"' -> {
                    val sb = StringBuilder()
                    i++
                    while (i < text.length && text[i] != '"') {
                        if (text[i] == '\\' && i + 1 < text.length) {
                            when (val e = text[i + 1]) {
                                'n' -> sb.append('\n')
                                't' -> sb.append('\t')
                                '\\', '"' -> sb.append(e)
                                else -> sb.append('\\').append(e)
                            }
                            i += 2
                        } else {
                            sb.append(text[i++])
                        }
                    }
                    i++ // closing quote
                    out += sb.toString()
                }
                else -> {
                    val start = i
                    while (i < text.length && !text[i].isWhitespace() && text[i] != '{' && text[i] != '}' && text[i] != '"') i++
                    out += text.substring(start, i)
                }
            }
        }
        return out
    }

    // ---------------------------------------------------------------- binary

    private const val T_MAP = 0
    private const val T_STRING = 1
    private const val T_INT = 2
    private const val T_FLOAT = 3
    private const val T_PTR = 4
    private const val T_WSTRING = 5
    private const val T_COLOR = 6
    private const val T_UINT64 = 7
    private const val T_END = 8
    private const val T_INT64 = 10
    private const val T_END_ALT = 11

    /** Parses binary KeyValues from [bytes] (a whole .bin cache file). */
    fun parseBinary(bytes: ByteArray): Map<String, Any> =
        parseBinary(ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN), null)

    /**
     * Parses binary KeyValues at [buf]'s position until the block ends. [keys] is appinfo.vdf's
     * string table (format 29 and later), where a key is an index into it rather than a string.
     */
    fun parseBinary(buf: ByteBuffer, keys: List<String>?): Map<String, Any> {
        val out = LinkedHashMap<String, Any>()
        while (buf.hasRemaining()) {
            val type = buf.get().toInt() and 0xFF
            if (type == T_END || type == T_END_ALT) break
            val name = if (keys != null) keys.getOrElse(buf.int) { "?" } else cString(buf)
            out[name] = when (type) {
                T_MAP -> parseBinary(buf, keys)
                T_STRING -> cString(buf)
                T_INT, T_PTR, T_COLOR -> buf.int
                T_FLOAT -> buf.float
                T_UINT64, T_INT64 -> buf.long
                T_WSTRING -> wString(buf)
                else -> throw IllegalArgumentException("unknown KeyValues type $type at ${buf.position() - 1}")
            }
        }
        return out
    }

    fun cString(buf: ByteBuffer): String {
        val start = buf.position()
        var end = start
        while (buf.hasRemaining()) {
            if (buf.get() == 0.toByte()) break
            end++
        }
        val bytes = ByteArray(end - start)
        buf.duplicate().apply { position(start) }.get(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    private fun wString(buf: ByteBuffer): String {
        val sb = StringBuilder()
        while (buf.remaining() >= 2) {
            val ch = buf.short.toInt().toChar()
            if (ch == '\u0000') break
            sb.append(ch)
        }
        return sb.toString()
    }
}
