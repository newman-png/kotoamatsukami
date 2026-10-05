package koto.core.plan

import koto.core.spell.Domain
import java.time.LocalDate

/**
 * The app's voice: short, flat, certain commands. No exclamation marks, no emoji, no praise.
 * Every text the AI writes passes through here.
 */
object Voice {
    const val MAX_COMMAND = 48
    const val MAX_DETAIL = 90

    private val BANNED = listOf(
        "great job", "good job", "well done", "you got this", "you can do it", "awesome", "amazing",
        "proud of you", "keep it up", "let's go", "lets go", "crush it", "you're doing great", "nice work",
    )

    /** Letters (including Italian accents), digits, spaces and plain punctuation only. */
    private fun plain(c: Char): Boolean =
        c.isLetterOrDigit() && c.code < 0x250 || c == ' ' || c in ".,:;'’-()/&%+°"

    /** Null when the text is acceptable as a command, else the reason. */
    fun commandProblem(text: String): String? = when {
        text.isBlank() -> "command is empty"
        text.length > MAX_COMMAND -> "command '$text' is longer than $MAX_COMMAND characters"
        !text.endsWith('.') -> "command '$text' must end with a period"
        else -> textProblem(text, "command")
    }

    fun detailProblem(text: String, what: String = "detail"): String? = when {
        text.isEmpty() -> null
        text.length > MAX_DETAIL -> "$what '$text' is longer than $MAX_DETAIL characters"
        else -> textProblem(text, what)
    }

    private fun textProblem(text: String, what: String): String? {
        if ('!' in text) return "$what '$text' uses an exclamation mark"
        if (text.any { !plain(it) }) return "$what '$text' contains characters other than plain text"
        val lower = text.lowercase()
        BANNED.firstOrNull { it in lower }?.let { return "$what '$text' uses praise ('$it'); the voice is flat" }
        return null
    }
}

/**
 * Checks the AI's plan intent against the user's own data before any plan is built from it.
 * Every goal must quote the user verbatim, reference only real deadlines and cover every future
 * one; the steering values must be in range; all text must be in the app's voice.
 */
object IntentValidator {

    fun validate(intent: PlanIntent, profile: Profile, today: LocalDate): List<String> {
        val errors = ArrayList<String>()
        val source = normalise(profile.goalsText + " " + profile.deadlines.joinToString(" ") { it.label })
        val known = profile.deadlines.associateBy { it.id }

        if (intent.goals.isEmpty()) errors += "at least one goal is required"
        if (intent.goals.size > 8) errors += "at most 8 goals"
        for (g in intent.goals) {
            if (g.domain !in PLANNABLE) errors += "goal '${g.summary}' has domain ${g.domain}; use one of ${PLANNABLE.joinToString()}"
            if (g.summary.length !in 3..80) errors += "goal summary '${g.summary}' must be 3-80 characters"
            groundingProblem(g.quote, source)?.let { errors += "goal '${g.summary}': $it" }
            for (id in g.deadlineIds) if (id !in known) errors += "goal '${g.summary}' references unknown deadline '$id'"
            if (g.topics.size > 8) errors += "goal '${g.summary}' has more than 8 topics"
            for (t in g.topics) {
                if (t.length !in 2..40) errors += "topic '$t' must be 2-40 characters"
                Voice.detailProblem(t, "topic")?.let { errors += it }
            }
        }

        val covered = intent.goals.flatMap { it.deadlineIds }.toSet()
        for (d in profile.deadlines) {
            if (!LocalDate.parse(d.date).isBefore(today) && d.id !in covered) {
                errors += "deadline ${d.id} '${d.label}' on ${d.date} is not covered by any goal"
            }
        }

        for (p in intent.pace) {
            if (p.domain !in LOAD_DOMAINS) errors += "pace given for ${p.domain}; pace applies only to ${LOAD_DOMAINS.joinToString()}"
            if (p.pace < PlanRules.MIN_PACE || p.pace > MAX_PACE_SAFE) {
                errors += "pace ${p.pace} for ${p.domain} is out of range; use ${PlanRules.MIN_PACE}-$MAX_PACE_SAFE"
            }
        }
        if (intent.goalStartWeek !in PlanRules.CONDITIONING_WEEKS + 1..PlanRules.PREP_CAP_WEEK) {
            errors += "goalStartWeek ${intent.goalStartWeek} must be ${PlanRules.CONDITIONING_WEEKS + 1}-${PlanRules.PREP_CAP_WEEK}"
        }
        if (intent.lightDay !in 1..7) errors += "lightDay ${intent.lightDay} must be 1 (Monday) to 7 (Sunday)"
        if (intent.workoutDays.size !in 2..5 || intent.workoutDays.any { it !in 1..7 } || intent.workoutDays.toSet().size != intent.workoutDays.size) {
            errors += "workoutDays must be 2-5 different days, each 1 (Monday) to 7 (Sunday)"
        }

        val goalDomains = intent.goals.map { it.domain }.toSet()
        if (intent.scouting.size > 6) errors += "at most 6 scouting tasks"
        for (s in intent.scouting) {
            if (s.domain !in goalDomains) errors += "scouting task '${s.command}' is for ${s.domain}, which has no goal"
            Voice.commandProblem(s.command)?.let { errors += it }
            Voice.detailProblem(s.detail)?.let { errors += it }
            groundingProblem(s.quote, source)?.let { errors += "scouting task '${s.command}': $it" }
        }
        return errors
    }

    /** Domains a goal can belong to. */
    val PLANNABLE: List<Domain> = LOAD_DOMAINS + listOf(Domain.SLEEP, Domain.NUTRITION)

    /** Highest pace that still keeps every ramp inside the validator's limit. */
    const val MAX_PACE_SAFE = 1.33

    /** Null if [quote] appears word for word in the user's text. */
    fun groundingProblem(quote: String, normalisedSource: String): String? {
        val q = normalise(quote)
        if (q.split(' ').none { it.length >= 3 }) return "quote '$quote' is too short to check"
        if (" $q " !in " $normalisedSource ") return "quote '$quote' does not appear in the user's own words"
        return null
    }

    /** Lowercase, punctuation to spaces, single spaces: so quoting is checked on words, not formatting. */
    fun normalise(text: String): String =
        text.lowercase().map { if (it.isLetterOrDigit()) it else ' ' }.joinToString("").split(' ').filter { it.isNotEmpty() }.joinToString(" ")
}
