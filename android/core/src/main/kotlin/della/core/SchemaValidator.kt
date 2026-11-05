// Minimal JSON Schema (draft 2020-12) validator covering exactly the keywords used by
// contract/schema/coverage-contract-1.0.0.schema.json: $ref (local), type, properties,
// required, additionalProperties, enum, const, items, prefixItems, minItems, maxItems,
// minLength, pattern, minimum, maximum, exclusiveMinimum, oneOf. The schema file is
// packaged from ../contract/schema as a classpath resource (single source of truth).
package della.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import java.math.BigDecimal

class SchemaValidator(private val root: JsonObject) {
    private val defs: JsonObject = root["\$defs"] as JsonObject
    private val patternCache = HashMap<String, Regex>()

    data class Result(val ok: Boolean, val errors: List<String>)

    fun validateDef(def: String, value: JsonElement): Result {
        val schema = defs[def] ?: throw IllegalArgumentException("schema def $def missing")
        val errors = ArrayList<String>()
        validate(schema, value, "", errors)
        return Result(errors.isEmpty(), errors.take(20))
    }

    private fun resolve(ref: String): JsonElement {
        require(ref.startsWith("#/\$defs/")) { "unsupported \$ref $ref" }
        return defs[ref.removePrefix("#/\$defs/")] ?: throw IllegalArgumentException("unresolved \$ref $ref")
    }

    private fun isNumber(v: JsonElement) = v is JsonPrimitive && !v.isString && v !is JsonNull && v.booleanOrNull == null && v.doubleOrNull != null

    private fun isInteger(v: JsonElement): Boolean {
        if (!isNumber(v)) return false
        return try {
            BigDecimal((v as JsonPrimitive).content).stripTrailingZeros().scale() <= 0
        } catch (e: NumberFormatException) {
            false
        }
    }

    private fun typeMatches(t: String, v: JsonElement): Boolean = when (t) {
        "null" -> v is JsonNull
        "boolean" -> v is JsonPrimitive && !v.isString && v !is JsonNull && v.booleanOrNull != null
        "string" -> v is JsonPrimitive && v.isString
        "number" -> isNumber(v)
        "integer" -> isInteger(v)
        "object" -> v is JsonObject
        "array" -> v is JsonArray
        else -> false
    }

    /** JSON equality with numeric value comparison (1 == 1.0). */
    private fun jsonEquals(a: JsonElement, b: JsonElement): Boolean {
        if (isNumber(a) && isNumber(b)) return BigDecimal((a as JsonPrimitive).content).compareTo(BigDecimal((b as JsonPrimitive).content)) == 0
        if (a is JsonNull || b is JsonNull) return a is JsonNull && b is JsonNull
        if (a is JsonPrimitive && b is JsonPrimitive) return a.isString == b.isString && a.content == b.content
        if (a is JsonArray && b is JsonArray) return a.size == b.size && a.indices.all { jsonEquals(a[it], b[it]) }
        if (a is JsonObject && b is JsonObject) return a.keys == b.keys && a.keys.all { jsonEquals(a.getValue(it), b.getValue(it)) }
        return false
    }

    private fun validate(schema: JsonElement, v: JsonElement, path: String, errors: MutableList<String>) {
        if (schema !is JsonObject) return
        val p = path.ifEmpty { "/" }
        schema["\$ref"]?.let { ref -> validate(resolve((ref as JsonPrimitive).content), v, path, errors) }
        schema["type"]?.let { t ->
            val types = if (t is JsonArray) t.map { (it as JsonPrimitive).content } else listOf((t as JsonPrimitive).content)
            if (types.none { typeMatches(it, v) }) {
                errors.add("$p must be ${types.joinToString(",")}")
                return
            }
        }
        schema["const"]?.let { c -> if (!jsonEquals(c, v)) errors.add("$p must be equal to constant") }
        schema["enum"]?.let { e -> if ((e as JsonArray).none { jsonEquals(it, v) }) errors.add("$p must be equal to one of the allowed values") }
        if (isNumber(v)) {
            val d = (v as JsonPrimitive).doubleOrNull!!
            schema["minimum"]?.let { m -> if (d < (m as JsonPrimitive).doubleOrNull!!) errors.add("$p must be >= ${m.content}") }
            schema["maximum"]?.let { m -> if (d > (m as JsonPrimitive).doubleOrNull!!) errors.add("$p must be <= ${m.content}") }
            schema["exclusiveMinimum"]?.let { m -> if (d <= (m as JsonPrimitive).doubleOrNull!!) errors.add("$p must be > ${m.content}") }
        }
        if (v is JsonPrimitive && v.isString) {
            schema["minLength"]?.let { m ->
                val cps = v.content.codePointCount(0, v.content.length)
                if (cps < (m as JsonPrimitive).doubleOrNull!!) errors.add("$p must NOT have fewer than ${m.content} characters")
            }
            schema["pattern"]?.let { pat ->
                val re = patternCache.getOrPut((pat as JsonPrimitive).content) { Regex(pat.content) }
                if (!re.containsMatchIn(v.content)) errors.add("$p must match pattern \"${pat.content}\"")
            }
        }
        if (v is JsonArray) {
            schema["minItems"]?.let { m -> if (v.size < (m as JsonPrimitive).doubleOrNull!!) errors.add("$p must NOT have fewer than ${m.content} items") }
            schema["maxItems"]?.let { m -> if (v.size > (m as JsonPrimitive).doubleOrNull!!) errors.add("$p must NOT have more than ${m.content} items") }
            var start = 0
            (schema["prefixItems"] as? JsonArray)?.let { pre ->
                for (k in pre.indices) if (k < v.size) validate(pre[k], v[k], "$path/$k", errors)
                start = pre.size
            }
            schema["items"]?.let { item -> for (k in start until v.size) validate(item, v[k], "$path/$k", errors) }
        }
        if (v is JsonObject) {
            (schema["required"] as? JsonArray)?.forEach { r ->
                val k = (r as JsonPrimitive).content
                if (!v.containsKey(k)) errors.add("$p must have required property '$k'")
            }
            val props = schema["properties"] as? JsonObject
            for ((k, value) in v) {
                val ps = props?.get(k)
                if (ps != null) {
                    validate(ps, value, "$path/$k", errors)
                } else {
                    when (val ap = schema["additionalProperties"]) {
                        null -> {}
                        is JsonPrimitive -> if (ap.booleanOrNull == false) errors.add("$p must NOT have additional properties ($k)")
                        else -> validate(ap, value, "$path/$k", errors)
                    }
                }
            }
        }
        (schema["oneOf"] as? JsonArray)?.let { alts ->
            var passing = 0
            for (alt in alts) {
                val sub = ArrayList<String>()
                validate(alt, v, path, sub)
                if (sub.isEmpty()) passing++
            }
            if (passing != 1) errors.add("$p must match exactly one schema in oneOf")
        }
    }

    companion object {
        const val RESOURCE = "/coverage-contract-1.0.0.schema.json"

        val contract: SchemaValidator by lazy {
            val text = SchemaValidator::class.java.getResourceAsStream(RESOURCE)?.use { it.readBytes().toString(Charsets.UTF_8) }
                ?: throw IllegalStateException("contract schema resource $RESOURCE missing")
            SchemaValidator(ContractJson.parse(text) as JsonObject)
        }
    }
}
