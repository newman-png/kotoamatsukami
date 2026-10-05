package koto.core.ai

import koto.core.plan.CompactState
import koto.core.plan.IntentValidator
import koto.core.plan.LOAD_DOMAINS
import koto.core.plan.MasterPlan
import koto.core.plan.PlanBuilder
import koto.core.plan.PlanContext
import koto.core.plan.PlanIntent
import koto.core.plan.PlanValidator
import koto.core.plan.Profile
import koto.core.plan.Signals
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.time.LocalDate

/**
 * The master planner pipeline. The AI proposes, code verifies:
 *
 * 1. Several drafts, each asked for in a fresh conversation. Each answer must be a JSON object
 *    matching [Schemas.intent]; malformed answers are sent back with the parse errors.
 * 2. A draft's intent is checked ([IntentValidator]), turned into weekly numbers by
 *    [PlanBuilder] and checked again ([PlanValidator]). Every broken rule goes back to the model
 *    as the exact error, a limited number of times.
 * 3. Each draft that passes everything gets a critic pass in a fresh context with no memory of
 *    the drafting. The best-scored draft wins.
 *
 * Nothing the model says reaches the plan without passing every validator.
 */
class MasterPlanner(private val llm: Llm, private val settings: Settings = Settings()) {

    data class Settings(
        val drafts: Int = 4,
        /** Rounds of "fix exactly these problems" per draft. */
        val repairs: Int = 2,
        /** Re-asks per draft for answers that aren't the right JSON. */
        val malformedRetries: Int = 2,
    ) {
        init {
            require(drafts in 1..MAX_DRAFTS && repairs in 0..5 && malformedRetries in 0..5)
        }
    }

    data class Candidate(val draft: Int, val intent: PlanIntent, val plan: MasterPlan, val score: Int?, val problems: List<String>)

    sealed interface Result {
        val report: List<String>

        data class Ok(val plan: MasterPlan, val chosen: Candidate, override val report: List<String>) : Result

        data class Failed(override val report: List<String>) : Result
    }

    /**
     * A first plan (no [previous]) or a weekly re-plan. [state] is the compact state document.
     * Throws [LlmUnavailable] only when the model could not be reached before any draft passed.
     */
    fun plan(profile: Profile, ctx: PlanContext, state: String, previous: PlanIntent? = null, signals: Signals? = null): Result {
        val report = ArrayList<String>()
        val schema = Schemas.intent(profile)
        val user = Prompts.fill(
            Prompts.PLANNER_USER,
            mapOf(
                "state" to state,
                "start" to ctx.planStart,
                "firstWeek" to ctx.firstWeek.toString(),
                "lastWeek" to PlanBuilder.horizonWeeks(ctx).toString(),
                "deadlines" to ctx.deadlines.joinToString("; ") { "${it.id} ${it.date} ${it.label}" }.ifEmpty { "none" },
                "capacity" to ctx.capacity().toString(),
                "rampFrom" to perDomain(ctx.rampFrom),
                "targets" to perDomain(ctx.targets),
                "replan" to if (previous == null) "" else Prompts.fill(
                    Prompts.REPLAN,
                    mapOf(
                        "signals" to (signals?.reasons?.takeIf { it.isNotEmpty() }?.joinToString("; ") ?: "nothing unusual"),
                        "intent" to compact.encodeToString(PlanIntent.serializer(), previous),
                    ),
                ),
            ),
        )
        val candidates = ArrayList<Candidate>()
        var unreachable: LlmUnavailable? = null
        for (d in 1..settings.drafts) {
            try {
                draft(d, profile, ctx, schema, user, report)?.let { candidates += it }
            } catch (e: LlmUnavailable) {
                unreachable = e
                report += "draft $d: model unreachable (${e.message})"
                break
            }
        }
        if (candidates.isEmpty()) {
            unreachable?.let { throw it }
            return Result.Failed(report)
        }

        val reviewed = candidates.map { c ->
            if (unreachable != null) return@map c
            try {
                critique(profile, c, report)
            } catch (e: LlmUnavailable) {
                unreachable = e
                report += "critic: model unreachable"
                c
            }
        }
        val best = reviewed.sortedWith(
            compareByDescending<Candidate> { it.score ?: NEUTRAL_SCORE }.thenBy { it.problems.size }.thenBy { it.draft },
        ).first()
        report += "chose draft ${best.draft} (score ${best.score ?: "none"})"
        return Result.Ok(best.plan, best, report)
    }

    private fun draft(d: Int, profile: Profile, ctx: PlanContext, schema: kotlinx.serialization.json.JsonObject, user: String, report: MutableList<String>): Candidate? {
        val messages = mutableListOf(
            Message(Message.Role.SYSTEM, Prompts.text(Prompts.PLANNER_SYSTEM)),
            Message(Message.Role.USER, user),
        )
        var repairs = 0
        var malformed = 0
        var attempt = 0
        val today = LocalDate.parse(ctx.today)
        while (true) {
            val answer = llm.complete(
                LlmRequest(Purpose.PLAN, messages.toList(), schema, temperature = if (d == 1) 0.3 else 0.8, seed = d * 1009 + attempt++),
            )
            val problems: List<String>
            var intent: PlanIntent? = null
            var plan: MasterPlan? = null
            val parsed = JsonSchema.parse(answer, schema)
            if (parsed is JsonSchema.Parsed.Bad) {
                if (malformed++ >= settings.malformedRetries) {
                    report += "draft $d: no valid JSON after ${malformed} tries: ${parsed.errors.take(3)}"
                    return null
                }
                messages += Message(Message.Role.ASSISTANT, answer)
                messages += Message(Message.Role.USER, repairText(parsed.errors))
                continue
            }
            try {
                intent = Schemas.json.decodeFromJsonElement(PlanIntent.serializer(), (parsed as JsonSchema.Parsed.Ok).value)
            } catch (e: SerializationException) {
                if (malformed++ >= settings.malformedRetries) {
                    report += "draft $d: undecodable JSON"
                    return null
                }
                messages += Message(Message.Role.ASSISTANT, answer)
                messages += Message(Message.Role.USER, repairText(listOf("the JSON does not fit the schema: ${e.message.orEmpty().take(200)}")))
                continue
            }
            val intentProblems = IntentValidator.validate(intent, profile, today)
            problems = if (intentProblems.isNotEmpty()) {
                intentProblems
            } else {
                plan = PlanBuilder.build(ctx, intent)
                PlanValidator.validate(plan, ctx).map { it.toString() }
            }
            if (problems.isEmpty() && plan != null) {
                report += "draft $d: passed every check after $repairs repairs"
                return Candidate(d, intent, plan, null, emptyList())
            }
            if (repairs++ >= settings.repairs) {
                report += "draft $d: rejected: ${problems.take(4).joinToString(" | ")}"
                return null
            }
            messages += Message(Message.Role.ASSISTANT, answer)
            messages += Message(Message.Role.USER, repairText(problems))
        }
    }

    private fun critique(profile: Profile, c: Candidate, report: MutableList<String>): Candidate {
        val messages = listOf(
            Message(Message.Role.SYSTEM, Prompts.text(Prompts.CRITIC_SYSTEM)),
            Message(
                Message.Role.USER,
                Prompts.fill(
                    Prompts.CRITIC_USER,
                    mapOf(
                        "profile" to CompactState.profileText(profile).trimEnd(),
                        "intent" to compact.encodeToString(PlanIntent.serializer(), c.intent),
                        "weeks" to weekTable(c.plan),
                    ),
                ),
            ),
        )
        repeat(2) { attempt ->
            val answer = llm.complete(LlmRequest(Purpose.CRITIC, messages, Schemas.CRITIC, temperature = 0.2, seed = c.draft * 31 + attempt))
            val parsed = JsonSchema.parse(answer, Schemas.CRITIC) as? JsonSchema.Parsed.Ok ?: return@repeat
            val critique = runCatching { Schemas.json.decodeFromJsonElement(Schemas.Critique.serializer(), parsed.value) }.getOrNull() ?: return@repeat
            if (critique.score !in 1..10) return@repeat
            report += "critic on draft ${c.draft}: ${critique.score}/10, ${critique.problems.size} problems"
            return c.copy(score = critique.score, problems = critique.problems)
        }
        report += "critic on draft ${c.draft}: no usable answer"
        return c
    }

    companion object {
        const val MAX_DRAFTS = 8
        const val MAX_ERRORS_SENT = 12

        /** Score assumed for a draft the critic could not review. */
        const val NEUTRAL_SCORE = 5

        private val compact = Json { encodeDefaults = true }

        fun repairText(errors: List<String>): String {
            val shown = errors.take(MAX_ERRORS_SENT).joinToString("\n") { "- $it" } +
                if (errors.size > MAX_ERRORS_SENT) "\n- and ${errors.size - MAX_ERRORS_SENT} more like these" else ""
            return Prompts.fill(Prompts.REPAIR, mapOf("errors" to shown))
        }

        /** Week-by-week minutes, one line per week, for the critic. */
        fun weekTable(plan: MasterPlan): String = plan.weeks.joinToString("\n") { w ->
            "week ${w.index} (${w.start}, ${w.phase}${if (w.light) ", light" else ""}): " +
                LOAD_DOMAINS.joinToString { "${it.name.lowercase()} ${w.load(it)}" } +
                "; nutrition pulses ${w.nutritionPulses}" +
                (if (w.deadlineIds.isNotEmpty()) "; deadlines ${w.deadlineIds.joinToString()}" else "")
        }

        private fun perDomain(m: Map<koto.core.spell.Domain, Int>): String =
            LOAD_DOMAINS.joinToString { "${it.name.lowercase()} ${m[it] ?: 0}" }

        /**
         * The weekly re-plan without the model: the same intent rebuilt from the new starting
         * point. Null if even that breaks a rule (the old plan then stays).
         */
        fun codeReplan(ctx: PlanContext, intent: PlanIntent): MasterPlan? {
            val plan = PlanBuilder.build(ctx, intent)
            return plan.takeIf { PlanValidator.validate(it, ctx).isEmpty() }
        }
    }
}
