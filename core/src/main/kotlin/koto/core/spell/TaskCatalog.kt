package koto.core.spell

import kotlin.random.Random

/**
 * Layer 1's hardcoded tasks. All are trivially easy and 10-20 seconds long, so the cue gets
 * paired with quick wins first. Layer 3 replaces this with generated tasks.
 */
object TaskCatalog {

    private fun pulse(
        id: String,
        domain: Domain,
        normal: TaskVersion,
        floor: TaskVersion,
        reveal: String = "",
    ) = SpellTask(id, TaskKind.PULSE, domain, normal, floor, reveal)

    val PULSES: List<SpellTask> = listOf(
        pulse(
            "water", Domain.NUTRITION,
            TaskVersion("Drink water.", "Three sips.", seconds = 15, bpm = 60),
            TaskVersion("Drink water.", "One sip.", seconds = 10, bpm = 60),
        ),
        pulse(
            "stand", Domain.BODY,
            TaskVersion("Stand up.", "Stay standing until the beat stops.", seconds = 15, bpm = 60),
            TaskVersion("Stand up.", "", seconds = 10, bpm = 60),
        ),
        pulse(
            "scroll", Domain.FOCUS,
            TaskVersion("Stop scrolling.", "Phone face down.", seconds = 20, bpm = 60),
            TaskVersion("Stop scrolling.", "Phone face down.", seconds = 10, bpm = 60),
        ),
        pulse(
            "pushups", Domain.EXERCISE,
            TaskVersion("Five push-ups.", "One on each beat.", seconds = 12, bpm = 25),
            TaskVersion("Two push-ups.", "One on each beat.", seconds = 6, bpm = 25),
        ),
        pulse(
            "breathe", Domain.BODY,
            TaskVersion("Breathe.", "In for four beats. Out for four.", seconds = 16, bpm = 60),
            TaskVersion("Breathe.", "In for four beats. Out for four.", seconds = 8, bpm = 60),
        ),
        pulse(
            "shoulders", Domain.BODY,
            TaskVersion("Roll your shoulders.", "Back, on the beat.", seconds = 10, bpm = 50),
            TaskVersion("Roll your shoulders.", "", seconds = 6, bpm = 50),
        ),
        pulse(
            "far", Domain.FOCUS,
            TaskVersion("Look away.", "Find the farthest point. Hold.", seconds = 20, bpm = 60),
            TaskVersion("Look away.", "", seconds = 10, bpm = 60),
        ),
        pulse(
            "surface", Domain.CHORES,
            TaskVersion("Clear one surface.", "The nearest one.", seconds = 20, bpm = 60),
            TaskVersion("Put one thing away.", "", seconds = 10, bpm = 60),
        ),
        pulse(
            "stanco", Domain.ITALIAN,
            TaskVersion("Translate: stanco.", "Say it out loud.", seconds = 10, bpm = 60),
            TaskVersion("Translate: stanco.", "", seconds = 10, bpm = 60),
            reveal = "stanco: tired.",
        ),
        pulse(
            "andare", Domain.ITALIAN,
            TaskVersion("Conjugate andare.", "Present tense. All six.", seconds = 20, bpm = 60),
            TaskVersion("Conjugate andare.", "Io and tu only.", seconds = 10, bpm = 60),
            reveal = "vado, vai, va, andiamo, andate, vanno.",
        ),
    )

    private fun siege(id: String, domain: Domain, normal: TaskVersion, floor: TaskVersion) =
        SpellTask(id, TaskKind.SIEGE, domain, normal, floor)

    private const val SIEGE_DETAIL = "Phone face down. Distractions are locked until the beat stops."

    /** Long blocks. The takeover starts them; the app lock sustains them. */
    val SIEGES: List<SpellTask> = listOf(
        siege(
            "focus25", Domain.FOCUS,
            TaskVersion("Focus block. 25 minutes. Go.", SIEGE_DETAIL, seconds = 25 * 60, bpm = SIEGE_BPM),
            TaskVersion("Focus block. 10 minutes. Go.", SIEGE_DETAIL, seconds = 10 * 60, bpm = SIEGE_BPM),
        ),
        siege(
            "deep50", Domain.STUDY,
            TaskVersion("Deep work. 50 minutes. Go.", SIEGE_DETAIL, seconds = 50 * 60, bpm = SIEGE_BPM),
            TaskVersion("Deep work. 15 minutes. Go.", SIEGE_DETAIL, seconds = 15 * 60, bpm = SIEGE_BPM),
        ),
    )

    /** The main screen's siege test: short enough to sit through. */
    val TEST_SIEGE: SpellTask = siege(
        "testsiege", Domain.FOCUS,
        TaskVersion("Test siege. 3 minutes. Go.", SIEGE_DETAIL, seconds = 3 * 60, bpm = SIEGE_BPM),
        TaskVersion("Test siege. 1 minute. Go.", SIEGE_DETAIL, seconds = 60, bpm = SIEGE_BPM),
    )

    /** What a reactive spell commands. */
    val STOP_SCROLLING: SpellTask get() = byId("scroll")!!

    /** Picks a pulse at random, avoiding the [recentIds] when possible. */
    fun pick(random: Random, recentIds: Collection<String>): SpellTask {
        val fresh = PULSES.filter { it.id !in recentIds }
        return (fresh.ifEmpty { PULSES }).random(random)
    }

    /** Picks a siege no longer than [maxMinutes], or null if none fits. */
    fun pickSiege(random: Random, maxMinutes: Int): SpellTask? =
        SIEGES.filter { it.normal.seconds <= maxMinutes * 60 }.randomOrNull(random)

    fun byId(id: String): SpellTask? = (PULSES + SIEGES + TEST_SIEGE).firstOrNull { it.id == id }

    /** Background tempo at the start of a siege; it speeds up near the end. */
    const val SIEGE_BPM = 12
}
