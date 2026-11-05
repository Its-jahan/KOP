// RFC 8785 JSON Canonicalization Scheme, restricted profile used by the contract
// (port of web/src/core/jcs.ts): every number MUST be a safe integer.
package della.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigDecimal
import java.math.BigInteger
import java.security.MessageDigest

class CanonicalizationException(message: String) : IllegalArgumentException(message)

private val MAX_SAFE = BigInteger.valueOf(9007199254740991L)

fun canonicalize(value: JsonElement): String {
    val sb = StringBuilder()
    writeCanonical(value, sb)
    return sb.toString()
}

private fun writeCanonical(value: JsonElement, sb: StringBuilder) {
    when (value) {
        is JsonNull -> sb.append("null")
        is JsonPrimitive -> {
            if (value.isString) {
                sb.append(quoteString(value.content))
                return
            }
            val c = value.content
            if (c == "true" || c == "false") {
                sb.append(c)
                return
            }
            val n = try {
                BigDecimal(c)
            } catch (e: NumberFormatException) {
                throw CanonicalizationException("canonicalize: unsupported number $c")
            }
            val i = try {
                n.toBigIntegerExact()
            } catch (e: ArithmeticException) {
                throw CanonicalizationException("canonicalize: non-integer or unsafe number $c")
            }
            if (i.abs() > MAX_SAFE) throw CanonicalizationException("canonicalize: non-integer or unsafe number $c")
            sb.append(i.toString())
        }
        is JsonArray -> {
            sb.append('[')
            value.forEachIndexed { k, v ->
                if (k > 0) sb.append(',')
                writeCanonical(v, sb)
            }
            sb.append(']')
        }
        is JsonObject -> {
            // Kotlin String.compareTo compares UTF-16 code units, as RFC 8785 requires.
            val keys = value.keys.sorted()
            sb.append('{')
            keys.forEachIndexed { k, key ->
                if (k > 0) sb.append(',')
                sb.append(quoteString(key)).append(':')
                writeCanonical(value.getValue(key), sb)
            }
            sb.append('}')
        }
    }
}

/** ECMAScript JSON.stringify string escaping (well-formed variant). */
fun quoteString(s: String): String {
    val out = StringBuilder(s.length + 2)
    out.append('"')
    var i = 0
    while (i < s.length) {
        val c = s[i].code
        when {
            c == 0x22 -> out.append("\\\"")
            c == 0x5c -> out.append("\\\\")
            c == 0x08 -> out.append("\\b")
            c == 0x0c -> out.append("\\f")
            c == 0x0a -> out.append("\\n")
            c == 0x0d -> out.append("\\r")
            c == 0x09 -> out.append("\\t")
            c < 0x20 -> out.append("\\u").append(c.toString(16).padStart(4, '0'))
            c in 0xd800..0xdbff -> {
                val n = if (i + 1 < s.length) s[i + 1].code else 0
                if (n in 0xdc00..0xdfff) {
                    out.append(s[i]).append(s[i + 1])
                    i++
                } else {
                    out.append("\\u").append(c.toString(16))
                }
            }
            c in 0xdc00..0xdfff -> out.append("\\u").append(c.toString(16))
            else -> out.append(s[i])
        }
        i++
    }
    out.append('"')
    return out.toString()
}

fun sha256Hex(text: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
    val sb = StringBuilder(64)
    for (b in digest) {
        val v = b.toInt() and 0xff
        sb.append("0123456789abcdef"[v shr 4]).append("0123456789abcdef"[v and 0xf])
    }
    return sb.toString()
}
