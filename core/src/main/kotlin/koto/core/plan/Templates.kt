package koto.core.plan

import koto.core.spell.Domain
import koto.core.spell.TaskCatalog
import koto.core.spell.TaskVersion
import kotlin.random.Random

/** The words of one task, as the writer (AI or [Templates]) gives them. Seconds and tempo are code's. */
data class TaskWords(
    val command: String,
    val detail: String = "",
    val floorCommand: String = command,
    val floorDetail: String = "",
    val reveal: String = "",
    val topic: String = "",
    /** Metronome tempo; paced movement uses a slower beat. */
    val bpm: Int = 60,
    val seconds: Int = 0,
    val floorSeconds: Int = 0,
)

/**
 * The fallback writer: plain code that words every kind of slot. Used whenever the laptop can't
 * be reached or its text fails the checks, and always for body, nutrition and mercy pulses.
 */
object Templates {
    const val SIEGE_DETAIL = "Phone face down. Distractions are locked until the beat stops."

    fun words(spec: SlotSpec, random: Random): TaskWords = when (spec.role) {
        SlotRole.WORK -> siege(spec, spec.topics.firstOrNull().orEmpty())
        SlotRole.MICRO -> micro(spec, spec.topics.firstOrNull().orEmpty(), random)
        SlotRole.SCOUT -> scout(spec, random)
        SlotRole.NUTRITION -> pool(NUTRITION, spec, random)
        SlotRole.MERCY -> pool(MERCY, spec, random)
        SlotRole.BODY -> pool(
            when (spec.domain) {
                Domain.EXERCISE -> EXERCISE
                Domain.CHORES -> CHORES
                Domain.FOCUS -> FOCUS
                else -> BODY
            },
            spec, random,
        )
    }

    /** "Linear algebra. 90 minutes. Go." The floor is a third of it, never under five minutes. */
    fun siege(spec: SlotSpec, topic: String): TaskWords {
        val n = spec.minutes
        val f = floorMinutes(spec)
        fun line(name: String, minutes: Int) = "$name. $minutes minutes. Go."
        return when (spec.domain) {
            Domain.EXERCISE -> TaskWords(
                line("Workout", n), "Push-ups, squats, plank, lunges. Keep moving until the beat stops.",
                // The brief's floor for a workout: a short walk.
                line("Walk", 5), "Outside if you can.",
                seconds = n * 60, floorSeconds = 5 * 60,
            )
            Domain.STEPS -> TaskWords(
                line("Walk", n), "Outside if you can. Phone in your pocket.", line("Walk", f), "Outside if you can.",
                seconds = n * 60, floorSeconds = f * 60,
            )
            Domain.CHORES -> TaskWords(
                line("Clean", n), "Nearest mess first. Keep going until the beat stops.", line("Clean", f), "Nearest mess first.",
                seconds = n * 60, floorSeconds = f * 60,
            )
            else -> {
                val fallback = when (spec.domain) {
                    Domain.ITALIAN -> "Italian"
                    Domain.CAREER -> "Career"
                    else -> "Study"
                }
                val name = capitalised(topic).takeIf { it.isNotEmpty() && Voice.commandProblem(line(it, n)) == null } ?: fallback
                val detail = if (name == fallback && topic.isNotEmpty()) "${capitalised(topic)}. Distractions are locked." else SIEGE_DETAIL
                TaskWords(
                    line(name, n), detail.takeIf { Voice.detailProblem(it) == null } ?: SIEGE_DETAIL,
                    line(name, f), SIEGE_DETAIL,
                    topic = topic, seconds = n * 60, floorSeconds = f * 60,
                )
            }
        }.copy(bpm = TaskCatalog.SIEGE_BPM)
    }

    fun floorMinutes(spec: SlotSpec): Int = DayComposer.roundTo5(spec.minutes / 3.0).coerceAtMost(maxOf(DayComposer.MIN_SIEGE, spec.minutes))

    private fun micro(spec: SlotSpec, topic: String, random: Random): TaskWords {
        val seconds = minOf(spec.maxSeconds, PULSE_LONG)
        return when (spec.domain) {
            Domain.ITALIAN -> italian(spec, random)
            Domain.STUDY -> {
                val command = "One ${topic.lowercase()} problem.".takeIf { topic.isNotEmpty() && Voice.commandProblem(it) == null }
                    ?: "One problem. Now."
                TaskWords(command, "Notes open. Solve it, or start it.", command, "Read the problem only.", topic = topic, seconds = seconds, floorSeconds = seconds / 2)
            }
            else -> {
                val command = "${capitalised(topic)}. One step.".takeIf { topic.isNotEmpty() && Voice.commandProblem(it) == null }
                    ?: "One step for the job. Now."
                TaskWords(command, "The smallest next one. Do it now.", command, "Write down what it is.", topic = topic, seconds = seconds, floorSeconds = seconds / 2)
            }
        }.let { it.copy(seconds = it.seconds.coerceAtMost(spec.maxSeconds), floorSeconds = it.floorSeconds.coerceIn(MIN_PULSE, spec.maxSeconds)) }
    }

    private fun scout(spec: SlotSpec, random: Random): TaskWords {
        val s = spec.scout ?: return when (spec.domain) {
            Domain.STUDY -> {
                val topic = spec.topics.firstOrNull().orEmpty()
                val command = "Open the ${topic.lowercase()} notes.".takeIf { topic.isNotEmpty() && Voice.commandProblem(it) == null }
                    ?: "Open your notes."
                TaskWords(command, "Read one line.", command, "", topic = topic, seconds = SCOUT_SECONDS, floorSeconds = MIN_PULSE)
            }
            else -> micro(spec, spec.topics.firstOrNull().orEmpty(), random).copy(seconds = SCOUT_SECONDS, floorSeconds = MIN_PULSE)
        }
        return TaskWords(s.command, s.detail, s.command, "", seconds = SCOUT_SECONDS, floorSeconds = MIN_PULSE)
    }

    private fun italian(spec: SlotSpec, random: Random): TaskWords {
        val verb = spec.maxSeconds > DayComposer.CONDITIONING_SECONDS && random.nextInt(3) == 0
        // Credit the goal topic that fits the pulse, if the goal named one.
        fun topic(vararg hints: String) =
            spec.topics.firstOrNull { t -> hints.any { it in t.lowercase() } } ?: spec.topics.firstOrNull().orEmpty()
        if (verb) {
            val (infinitive, forms) = VERBS.random(random)
            return TaskWords(
                "Conjugate $infinitive.", "Present tense. All six.", "Conjugate $infinitive.", "Io and tu only.",
                reveal = "$forms.", topic = topic("verb", "tense", "conjug", "grammar"), seconds = 20, floorSeconds = 10,
            )
        }
        val (word, meaning) = WORDS.random(random)
        return TaskWords(
            "Translate: $word.", "Say it out loud.", "Translate: $word.", "",
            reveal = "$word: $meaning.", topic = topic("vocab", "word"), seconds = 10, floorSeconds = 10,
        )
    }

    private fun pool(items: List<Pair<TaskVersion, TaskVersion>>, spec: SlotSpec, random: Random): TaskWords {
        val (n, f) = items.random(random)
        return TaskWords(
            n.command, n.detail, f.command, f.detail, bpm = n.bpm,
            seconds = n.seconds.coerceAtMost(spec.maxSeconds), floorSeconds = f.seconds.coerceAtMost(spec.maxSeconds),
        )
    }

    private fun capitalised(s: String) = s.trim().replaceFirstChar { it.uppercaseChar() }

    private const val PULSE_LONG = 60
    private const val SCOUT_SECONDS = 20
    private const val MIN_PULSE = 10

    private fun v(command: String, detail: String, seconds: Int, bpm: Int = 60) = TaskVersion(command, detail, seconds, bpm)

    val NUTRITION: List<Pair<TaskVersion, TaskVersion>> = listOf(
        v("Drink water.", "Three sips.", 15) to v("Drink water.", "One sip.", 10),
        v("Fill your water bottle.", "Put it where you can see it.", 20) to v("Drink water.", "One sip.", 10),
        v("Plan your next meal.", "Something with vegetables in it.", 20) to v("Name your next meal.", "", 10),
        v("Put the snack away.", "Out of sight.", 15) to v("Put the snack away.", "", 10),
        v("Eat a piece of fruit.", "Or put one where you will see it.", 20) to v("Find a piece of fruit.", "", 10),
        v("No sugar in the next drink.", "Water, tea or coffee.", 10) to v("No sugar in the next drink.", "", 10),
    )

    val MERCY: List<Pair<TaskVersion, TaskVersion>> = listOf(
        v("Breathe. Done.", "", 10) to v("Breathe. Done.", "", 10),
        v("Rest. Ten minutes.", "No task. This one is yours.", 15) to v("Rest.", "", 10),
        v("Sit back.", "Nothing to do until the beat stops.", 15) to v("Sit back.", "", 10),
        v("Close your eyes.", "Breathe on the beat.", 15) to v("Close your eyes.", "", 10),
    )

    val BODY: List<Pair<TaskVersion, TaskVersion>> = listOf(
        v("Stand up.", "Stay standing until the beat stops.", 15) to v("Stand up.", "", 10),
        v("Breathe.", "In for four beats. Out for four.", 16) to v("Breathe.", "In for four beats. Out for four.", 8),
        v("Roll your shoulders.", "Back, on the beat.", 10, 50) to v("Roll your shoulders.", "", 6, 50),
        v("Stretch.", "Arms up. Hold until the beat stops.", 15) to v("Stretch.", "", 10),
        v("Sit up straight.", "Feet flat. Hold.", 10) to v("Sit up straight.", "", 10),
    )

    val FOCUS: List<Pair<TaskVersion, TaskVersion>> = listOf(
        v("Stop scrolling.", "Phone face down.", 20) to v("Stop scrolling.", "Phone face down.", 10),
        v("Look away.", "Find the farthest point. Hold.", 20) to v("Look away.", "", 10),
        v("Close what you aren't using.", "Tabs and apps.", 20) to v("Close one app.", "", 10),
    )

    val EXERCISE: List<Pair<TaskVersion, TaskVersion>> = listOf(
        v("Ten push-ups.", "One on each beat.", 24, 25) to v("Three push-ups.", "One on each beat.", 8, 25),
        v("Ten squats.", "One on each beat.", 24, 25) to v("Three squats.", "One on each beat.", 8, 25),
        v("Plank.", "Hold until the beat stops.", 20) to v("Plank.", "Hold until the beat stops.", 10),
        v("Five push-ups.", "One on each beat.", 12, 25) to v("Two push-ups.", "One on each beat.", 6, 25),
    )

    val CHORES: List<Pair<TaskVersion, TaskVersion>> = listOf(
        v("Clear one surface.", "The nearest one.", 20) to v("Put one thing away.", "", 10),
        v("Take one thing to the bin.", "", 15) to v("Take one thing to the bin.", "", 10),
        v("Put three things away.", "Where they belong.", 20) to v("Put one thing away.", "", 10),
    )

    /** Italian words for translation pulses, with their meaning for the reveal. */
    val WORDS: List<Pair<String, String>> = listOf(
        "stanco" to "tired", "acqua" to "water", "lavoro" to "work", "domani" to "tomorrow", "ieri" to "yesterday",
        "sempre" to "always", "mai" to "never", "ancora" to "still, again", "adesso" to "now", "subito" to "right away",
        "presto" to "early, soon", "tardi" to "late", "libro" to "book", "casa" to "house, home", "amico" to "friend",
        "giorno" to "day", "notte" to "night", "settimana" to "week", "mese" to "month", "anno" to "year",
        "tempo" to "time, weather", "cibo" to "food", "strada" to "road, street", "città" to "city",
        "lingua" to "language, tongue", "scuola" to "school", "esame" to "exam", "studiare" to "to study",
        "imparare" to "to learn", "capire" to "to understand", "finire" to "to finish", "cominciare" to "to begin",
        "aspettare" to "to wait", "cercare" to "to look for", "trovare" to "to find", "pensare" to "to think",
        "sentire" to "to hear, to feel", "vedere" to "to see", "forte" to "strong", "debole" to "weak",
        "facile" to "easy", "difficile" to "difficult", "pronto" to "ready", "grazie" to "thank you",
        "perché" to "why, because", "quando" to "when", "dove" to "where", "insieme" to "together",
        "abbastanza" to "enough", "sveglia" to "alarm clock",
    )

    /** Verbs for conjugation pulses: present tense, io to loro. */
    val VERBS: List<Pair<String, String>> = listOf(
        "essere" to "sono, sei, è, siamo, siete, sono",
        "avere" to "ho, hai, ha, abbiamo, avete, hanno",
        "andare" to "vado, vai, va, andiamo, andate, vanno",
        "fare" to "faccio, fai, fa, facciamo, fate, fanno",
        "venire" to "vengo, vieni, viene, veniamo, venite, vengono",
        "volere" to "voglio, vuoi, vuole, vogliamo, volete, vogliono",
        "potere" to "posso, puoi, può, possiamo, potete, possono",
        "dovere" to "devo, devi, deve, dobbiamo, dovete, devono",
        "dire" to "dico, dici, dice, diciamo, dite, dicono",
        "sapere" to "so, sai, sa, sappiamo, sapete, sanno",
        "stare" to "sto, stai, sta, stiamo, state, stanno",
        "parlare" to "parlo, parli, parla, parliamo, parlate, parlano",
        "mangiare" to "mangio, mangi, mangia, mangiamo, mangiate, mangiano",
        "dormire" to "dormo, dormi, dorme, dormiamo, dormite, dormono",
        "capire" to "capisco, capisci, capisce, capiamo, capite, capiscono",
        "prendere" to "prendo, prendi, prende, prendiamo, prendete, prendono",
    )
}
