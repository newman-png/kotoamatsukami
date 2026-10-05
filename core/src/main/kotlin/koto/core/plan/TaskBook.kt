package koto.core.plan

import koto.core.spell.Domain
import koto.core.spell.SpellTask
import koto.core.spell.TaskKind
import koto.core.spell.TaskVersion
import kotlinx.serialization.Serializable
import kotlin.random.Random

/** The text the AI writer returns for one slot. Every field is checked before use. */
@Serializable
data class WrittenTask(
    val slot: Int,
    val topic: String = "",
    val command: String = "",
    val detail: String = "",
    val reveal: String = "",
)

/**
 * One day's tasks, written the night before. Times are not in it: the phone picks them when the
 * day starts, inside the windows of that moment. [writer] is "ai" or "code".
 */
@Serializable
data class TaskBook(
    val date: String,
    val week: Int,
    val tasks: List<SpellTask>,
    val writer: String,
    /** The [MasterPlan.revision] the book was composed from. A re-plan makes older books stale. */
    val planRevision: Int = 0,
) {
    fun task(id: String): SpellTask? = tasks.firstOrNull { it.id == id }
}

/** Assembles a day's [TaskBook] from the composer's slots and whatever the writer produced. */
object Books {
    const val WRITER_AI = "ai"
    const val WRITER_CODE = "code"

    /** Slots the AI writer words. Everything else comes from [Templates]. */
    fun aiWritten(spec: SlotSpec): Boolean = when (spec.role) {
        SlotRole.MICRO -> true
        SlotRole.WORK -> spec.topics.size > 1
        SlotRole.SCOUT -> spec.scout == null
        else -> false
    }

    /**
     * Null if [w] is usable for [spec], else the reason, worded for the model. A siege takes only
     * its topic from the writer; its command is built in code so the minutes always match.
     */
    fun problem(spec: SlotSpec, w: WrittenTask): String? {
        val tag = "slot ${w.slot}"
        if (spec.topics.isNotEmpty() && w.topic !in spec.topics) {
            return "$tag: topic '${w.topic}' is not one of ${spec.topics.joinToString { "'$it'" }}"
        }
        if (spec.role == SlotRole.WORK) return null
        Voice.commandProblem(w.command)?.let { return "$tag: $it" }
        Voice.detailProblem(w.detail)?.let { return "$tag: $it" }
        Voice.detailProblem(w.reveal, "reveal")?.let { return "$tag: $it" }
        if (Regex("\\d+\\s*(minutes?|hours?)").containsMatchIn(w.command.lowercase())) {
            return "$tag: a pulse lasts seconds; don't give it minutes or hours"
        }
        return null
    }

    /**
     * Builds the book. [written] holds the AI's text by slot index; any slot without valid text
     * falls back to [Templates]. Ids are "date#index", unique per day.
     */
    fun assemble(date: String, week: Int, specs: List<SlotSpec>, written: Map<Int, WrittenTask>, random: Random): TaskBook {
        var usedAi = false
        val tasks = specs.mapIndexed { i, spec ->
            val w = written[i]?.takeIf { aiWritten(spec) && problem(spec, it) == null }
            if (w != null) usedAi = true
            val words = when {
                w == null -> Templates.words(spec, random)
                spec.role == SlotRole.WORK -> Templates.siege(spec, w.topic)
                else -> fromWriter(spec, w)
            }
            toTask("$date#$i", spec, words)
        }
        return TaskBook(date, week, tasks, if (usedAi) WRITER_AI else WRITER_CODE)
    }

    private fun fromWriter(spec: SlotSpec, w: WrittenTask): TaskWords {
        val seconds = if (spec.role == SlotRole.SCOUT || spec.domain == Domain.ITALIAN) 20 else spec.maxSeconds
        return TaskWords(
            w.command, w.detail, w.command, "", reveal = w.reveal, topic = w.topic.takeIf { it in spec.topics }.orEmpty(),
            seconds = seconds.coerceAtMost(spec.maxSeconds), floorSeconds = (seconds / 2).coerceIn(10, spec.maxSeconds),
        )
    }

    fun toTask(id: String, spec: SlotSpec, words: TaskWords): SpellTask = SpellTask(
        id = id,
        kind = spec.kind,
        domain = spec.domain,
        normal = TaskVersion(words.command, words.detail, words.seconds, words.bpm),
        floor = TaskVersion(words.floorCommand, words.floorDetail, words.floorSeconds, words.bpm),
        reveal = words.reveal,
        topic = words.topic,
    )

    /** Code-only book: what the phone uses when nothing better arrived overnight. */
    fun fallback(date: String, week: Int, specs: List<SlotSpec>, random: Random): TaskBook =
        assemble(date, week, specs, emptyMap(), random)

    /**
     * A siege cut to at most [minutes], for when less time is left before a protected block, or
     * the lock limit is lower, than when it was planned. Siege commands always say their minutes.
     */
    fun shortened(task: SpellTask, minutes: Int): SpellTask {
        fun cut(v: TaskVersion): TaskVersion {
            val m = v.seconds / 60
            if (m <= minutes) return v
            return v.copy(command = v.command.replace("$m minutes", "$minutes minutes"), seconds = minutes * 60)
        }
        return task.copy(normal = cut(task.normal), floor = cut(task.floor))
    }

    /** Siege lengths for [Placement]: minutes for a siege, null for a pulse. */
    fun siegeMinutes(book: TaskBook): List<Int?> =
        book.tasks.map { if (it.kind == TaskKind.SIEGE) it.normal.seconds / 60 else null }
}
