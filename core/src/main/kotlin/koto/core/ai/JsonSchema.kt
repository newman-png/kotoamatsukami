package koto.core.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * The small part of JSON Schema the planner uses: object, array, string, integer, number,
 * properties, required, items, enum, and no extra properties. The same schema goes to the model
 * and is checked here, so "matches the schema" means one thing.
 */
object JsonSchema {

    fun obj(vararg props: Pair<String, JsonObject>): JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", JsonObject(props.toMap()))
        put("required", buildJsonArray { props.forEach { add(JsonPrimitive(it.first)) } })
        put("additionalProperties", false)
    }

    fun arr(items: JsonObject): JsonObject = buildJsonObject {
        put("type", "array")
        put("items", items)
    }

    fun str(enum: List<String>? = null): JsonObject = buildJsonObject {
        put("type", "string")
        if (!enum.isNullOrEmpty()) put("enum", buildJsonArray { enum.forEach { add(JsonPrimitive(it)) } })
    }

    fun int(): JsonObject = buildJsonObject { put("type", "integer") }

    fun num(): JsonObject = buildJsonObject { put("type", "number") }

    /** Every way [value] fails [schema], with JSON paths, worded for the model. */
    fun validate(schema: JsonObject, value: JsonElement, path: String = "$"): List<String> {
        val out = ArrayList<String>()
        check(schema, value, path, out)
        return out
    }

    private fun check(schema: JsonObject, value: JsonElement, path: String, out: MutableList<String>) {
        when (schema["type"]?.jsonPrimitive?.contentOrNull) {
            "object" -> {
                if (value !is JsonObject) {
                    out += "$path must be an object"
                    return
                }
                val props = schema["properties"]?.jsonObject ?: JsonObject(emptyMap())
                for (name in schema["required"]?.jsonArray.orEmpty().map { it.jsonPrimitive.content }) {
                    if (name !in value) out += "$path.$name is missing"
                }
                val closed = schema["additionalProperties"]?.jsonPrimitive?.booleanOrNull == false
                for ((k, v) in value) {
                    val sub = props[k]?.jsonObject
                    when {
                        sub != null -> check(sub, v, "$path.$k", out)
                        closed -> out += "$path.$k is not allowed"
                    }
                }
            }
            "array" -> {
                if (value !is JsonArray) {
                    out += "$path must be an array"
                    return
                }
                val items = schema["items"]?.jsonObject ?: return
                value.forEachIndexed { i, v -> check(items, v, "$path[$i]", out) }
            }
            "string" -> {
                if (value !is JsonPrimitive || !value.isString) {
                    out += "$path must be a string"
                    return
                }
                val allowed = schema["enum"]?.jsonArray?.map { it.jsonPrimitive.content }
                if (allowed != null && value.content !in allowed) out += "$path '${value.content}' must be one of ${allowed.joinToString()}"
            }
            "integer" -> if (value !is JsonPrimitive || value.isString || value is JsonNull || value.longOrNull == null) out += "$path must be a whole number"
            "number" -> if (value !is JsonPrimitive || value.isString || value is JsonNull || value.doubleOrNull == null) out += "$path must be a number"
        }
    }

    /**
     * The JSON object in a model's answer: thinking blocks and code fences removed, then the
     * outermost braces. Null if there is none.
     */
    fun extractObject(text: String): String? {
        val cleaned = text.replace(Regex("(?s)<think>.*?</think>"), "").replace("```json", "").replace("```", "")
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return cleaned.substring(start, end + 1)
    }

    /** Parses and checks an answer. Either the object or the errors to send back. */
    fun parse(text: String, schema: JsonObject): Parsed {
        val raw = extractObject(text) ?: return Parsed.Bad(listOf("the answer contains no JSON object"))
        val element = try {
            Json.parseToJsonElement(raw)
        } catch (e: IllegalArgumentException) {
            return Parsed.Bad(listOf("the answer is not valid JSON: ${e.message.orEmpty().lineSequence().first().take(200)}"))
        }
        val errors = validate(schema, element)
        return if (errors.isEmpty()) Parsed.Ok(element.jsonObject) else Parsed.Bad(errors)
    }

    sealed interface Parsed {
        data class Ok(val value: JsonObject) : Parsed
        data class Bad(val errors: List<String>) : Parsed
    }
}
