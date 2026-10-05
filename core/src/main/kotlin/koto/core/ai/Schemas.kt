package koto.core.ai

import koto.core.plan.IntentValidator
import koto.core.plan.LOAD_DOMAINS
import koto.core.plan.Profile
import koto.core.ai.JsonSchema.arr
import koto.core.ai.JsonSchema.int
import koto.core.ai.JsonSchema.num
import koto.core.ai.JsonSchema.obj
import koto.core.ai.JsonSchema.str
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** The shapes the model must answer in. Defined once here; sent to the model and checked in code. */
object Schemas {

    /** The plan intent ([koto.core.plan.PlanIntent]). Domains and deadline ids are closed lists. */
    fun intent(profile: Profile): JsonObject {
        val ids = profile.deadlines.map { it.id }
        return obj(
            "goals" to arr(
                obj(
                    "domain" to str(IntentValidator.PLANNABLE.map { it.name }),
                    "summary" to str(),
                    "quote" to str(),
                    "deadlineIds" to arr(str(ids)),
                    "topics" to arr(str()),
                ),
            ),
            "pace" to arr(obj("domain" to str(LOAD_DOMAINS.map { it.name }), "pace" to num())),
            "goalStartWeek" to int(),
            "lightDay" to int(),
            "workoutDays" to arr(int()),
            "scouting" to arr(
                obj(
                    "domain" to str(IntentValidator.PLANNABLE.map { it.name }),
                    "command" to str(),
                    "detail" to str(),
                    "quote" to str(),
                ),
            ),
        )
    }

    /** The critic lists problems before it scores, so the score follows from them. */
    val CRITIC: JsonObject = obj("problems" to arr(str()), "score" to int())

    val WRITER: JsonObject = obj(
        "tasks" to arr(
            obj(
                "slot" to int(),
                "topic" to str(),
                "command" to str(),
                "detail" to str(),
                "reveal" to str(),
            ),
        ),
    )

    @Serializable
    data class Critique(val problems: List<String>, val score: Int)

    @Serializable
    data class Written(val tasks: List<koto.core.plan.WrittenTask>)

    /** Decoding after the schema check: unknown keys were already rejected there. */
    val json = Json { ignoreUnknownKeys = true }
}
