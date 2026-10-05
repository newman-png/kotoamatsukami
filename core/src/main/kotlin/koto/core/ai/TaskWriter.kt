package koto.core.ai

import koto.core.plan.Books
import koto.core.plan.CompactState
import koto.core.plan.SlotSpec
import koto.core.plan.TaskBook
import koto.core.plan.WrittenTask
import kotlinx.serialization.SerializationException
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import kotlin.random.Random

/**
 * The nightly writer: words tomorrow's goal slots (topics, micro-learning questions, scouting
 * steps). Each slot's text is checked; slots that fail get one more try with the exact errors,
 * then fall back to [koto.core.plan.Templates]. One bad slot never costs the rest.
 */
class TaskWriter(private val llm: Llm) {

    /**
     * Throws [LlmUnavailable] when the model can't be reached, so the caller can retry later
     * and keep any book it already has.
     */
    fun write(
        date: LocalDate,
        week: Int,
        specs: List<SlotSpec>,
        state: String,
        yesterday: List<String>,
        random: Random,
        report: MutableList<String> = ArrayList(),
    ): TaskBook {
        val wanted = specs.indices.filter { Books.aiWritten(specs[it]) }.toSet()
        if (wanted.isEmpty()) return Books.fallback(date.toString(), week, specs, random)

        val messages = mutableListOf(
            Message(Message.Role.SYSTEM, Prompts.text(Prompts.WRITER_SYSTEM)),
            Message(
                Message.Role.USER,
                Prompts.fill(
                    Prompts.WRITER_USER,
                    mapOf(
                        "state" to state,
                        "yesterday" to yesterday.joinToString("\n").ifEmpty { "nothing logged." },
                        "date" to "$date, ${date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)}",
                        "slots" to CompactState.slotLines(specs).trimEnd(),
                    ),
                ),
            ),
        )
        val accepted = HashMap<Int, WrittenTask>()
        var malformed = 0
        var rounds = 0
        var seed = date.toEpochDay().toInt()
        while (rounds < ROUNDS) {
            val answer = llm.complete(LlmRequest(Purpose.WRITE, messages.toList(), Schemas.WRITER, temperature = 0.7, seed = seed++))
            val written = when (val parsed = JsonSchema.parse(answer, Schemas.WRITER)) {
                is JsonSchema.Parsed.Bad -> null to parsed.errors
                is JsonSchema.Parsed.Ok -> try {
                    Schemas.json.decodeFromJsonElement(Schemas.Written.serializer(), parsed.value) to emptyList()
                } catch (e: SerializationException) {
                    null to listOf("the JSON does not fit the schema: ${e.message.orEmpty().take(200)}")
                }
            }
            val tasks = written.first
            if (tasks == null) {
                if (malformed++ >= MALFORMED_RETRIES) break
                messages += Message(Message.Role.ASSISTANT, answer)
                messages += Message(Message.Role.USER, MasterPlanner.repairText(written.second))
                continue
            }
            rounds++
            val errors = ArrayList<String>()
            for (w in tasks.tasks) {
                if (w.slot !in wanted || w.slot in accepted) continue
                val problem = Books.problem(specs[w.slot], w) ?: repeated(w, specs, accepted)
                if (problem == null) accepted[w.slot] = w else errors += problem
            }
            val missing = wanted - accepted.keys - tasks.tasks.map { it.slot }.toSet()
            missing.forEach { errors += "slot $it is missing" }
            if (accepted.keys.containsAll(wanted)) break
            messages += Message(Message.Role.ASSISTANT, answer)
            messages += Message(Message.Role.USER, MasterPlanner.repairText(errors + "return entries only for these slots: ${(wanted - accepted.keys).sorted().joinToString()}"))
        }
        report += "writer $date: ${accepted.size} of ${wanted.size} slots written by the model, the rest by templates"
        return Books.assemble(date.toString(), week, specs, accepted, random)
    }

    /** A command used by another slot today, unless it's a siege (its command is code's). */
    private fun repeated(w: WrittenTask, specs: List<SlotSpec>, accepted: Map<Int, WrittenTask>): String? {
        if (specs[w.slot].kind == koto.core.spell.TaskKind.SIEGE) return null
        val dup = accepted.values.firstOrNull { it.command.equals(w.command, ignoreCase = true) && specs[it.slot].kind != koto.core.spell.TaskKind.SIEGE }
        return dup?.let { "slot ${w.slot}: repeats the command of slot ${it.slot}; write a different task" }
    }

    companion object {
        /** The first answer plus one round of fixes. */
        const val ROUNDS = 2
        const val MALFORMED_RETRIES = 1
    }
}
