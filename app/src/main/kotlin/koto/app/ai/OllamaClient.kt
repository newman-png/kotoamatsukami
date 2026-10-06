package koto.app.ai

import koto.core.ai.LanAddress
import koto.core.ai.Llm
import koto.core.ai.LlmRequest
import koto.core.ai.LlmUnavailable
import koto.core.ai.PlannerSettings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL

/**
 * Talks to Ollama on the laptop over the home network. Refuses any address outside it, so
 * nothing about the user can ever be sent to the internet by a typo. Blocking: call it from the
 * planner thread only.
 */
class OllamaClient(private val settings: PlannerSettings) : Llm {

    override fun complete(request: LlmRequest): String {
        val body = buildJsonObject {
            put("model", settings.modelFor(request.purpose))
            put("stream", false)
            put("format", request.schema)
            put("keep_alive", "15m")
            putJsonArray("messages") {
                for (m in request.messages) addJsonObject {
                    put("role", m.role.name.lowercase())
                    put("content", m.content)
                }
            }
            putJsonObject("options") {
                put("temperature", request.temperature)
                request.seed?.let { put("seed", it) }
                put("num_ctx", CONTEXT_TOKENS)
            }
        }
        val answer = parse(call("/api/chat", body.toString(), READ_TIMEOUT_MS))
        return answer["message"]?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull
            ?: throw LlmUnavailable("the laptop answered without a message")
    }

    /** Where requests go, for the planner log. */
    fun address(): String = "${settings.host}:${settings.port} (${settings.model})"

    /** One plain sentence on whether the laptop and the models are there. Blocking. */
    fun check(): String = try {
        val tags = parse(call("/api/tags", null, CHECK_TIMEOUT_MS))
        val names = tags["models"]?.jsonArray.orEmpty().mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull }.toSet()
        val wanted = listOf(settings.model, settings.nightModel).filter { it.isNotBlank() }.distinct()
        val missing = wanted.filter { full(it) !in names }
        if (missing.isEmpty()) {
            "Reached. ${wanted.joinToString(" and ")} ready."
        } else {
            "Reached, but ${missing.joinToString(" and ")} is not on the laptop. Run: ollama pull ${missing.first()}"
        }
    } catch (e: LlmUnavailable) {
        e.message ?: "Not reached."
    }

    private fun call(path: String, body: String?, readTimeoutMs: Int): String {
        val address = resolve()
        val url = URL("http", address.hostAddress, settings.port, path)
        val c = try {
            url.openConnection() as HttpURLConnection
        } catch (e: IOException) {
            throw LlmUnavailable("cannot open $url: ${e.javaClass.simpleName}: ${e.message}", e)
        }
        try {
            c.connectTimeout = CONNECT_TIMEOUT_MS
            c.readTimeout = readTimeoutMs
            c.useCaches = false
            if (body != null) {
                c.requestMethod = "POST"
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val error = runCatching { Json.parseToJsonElement(text).jsonObject["error"]?.jsonPrimitive?.contentOrNull }.getOrNull()
                throw LlmUnavailable("HTTP $code from $path: ${error ?: text.take(200).ifEmpty { c.responseMessage.orEmpty() }}")
            }
            return text
        } catch (e: IOException) {
            throw LlmUnavailable("$path to ${settings.host}:${settings.port} failed: ${e.javaClass.simpleName}: ${e.message}", e)
        } finally {
            c.disconnect()
        }
    }

    private fun resolve(): InetAddress {
        val address = try {
            InetAddress.getByName(settings.host.trim())
        } catch (e: IOException) {
            throw LlmUnavailable("cannot find ${settings.host}: ${e.javaClass.simpleName}: ${e.message}", e)
        }
        if (!LanAddress.isPrivate(address.address)) {
            throw LlmUnavailable("${settings.host} is not on the home network. Only private addresses are allowed.")
        }
        return address
    }

    private fun parse(text: String): JsonObject = try {
        Json.parseToJsonElement(text).jsonObject
    } catch (e: IllegalArgumentException) {
        throw LlmUnavailable("the laptop sent something that isn't JSON", e)
    }

    /** Ollama lists "qwen3:latest" for a model pulled as "qwen3". */
    private fun full(name: String) = if (':' in name) name else "$name:latest"

    private companion object {
        const val CONNECT_TIMEOUT_MS = 5_000
        const val CHECK_TIMEOUT_MS = 10_000

        /** A partly offloaded 14B model thinking at a few tokens a second needs a long read. */
        const val READ_TIMEOUT_MS = 30 * 60_000

        /** Room for the prompt, the model's thinking, the answer and two rounds of fixes. */
        const val CONTEXT_TOKENS = 16_384
    }
}
