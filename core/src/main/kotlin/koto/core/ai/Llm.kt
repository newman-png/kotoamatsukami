package koto.core.ai

import kotlinx.serialization.json.JsonObject

/** What a request is for. The app maps each to a model: a strong one for planning, maybe a cheaper one at night. */
enum class Purpose { PLAN, CRITIC, WRITE }

data class Message(val role: Role, val content: String) {
    enum class Role { SYSTEM, USER, ASSISTANT }
}

/**
 * One chat request whose answer must be a JSON object matching [schema]. Models that support
 * structured output get the schema; the answer is checked against it in code either way.
 */
data class LlmRequest(
    val purpose: Purpose,
    val messages: List<Message>,
    val schema: JsonObject,
    val temperature: Double,
    val seed: Int? = null,
)

/**
 * The only thing the planner knows about the AI. The local Ollama client implements it today;
 * any other model can replace it without touching the planner, the validators or the app.
 */
fun interface Llm {
    /** The model's raw answer text. Throws [LlmUnavailable] when the model can't be reached. */
    fun complete(request: LlmRequest): String
}

/** The model could not be reached or did not answer. Planning waits and tries again later. */
class LlmUnavailable(message: String, cause: Throwable? = null) : Exception(message, cause)
