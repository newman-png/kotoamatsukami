package koto.core.time

import koto.core.safety.LockKind
import koto.core.safety.LockLimits
import koto.core.spell.Distractions
import koto.core.spell.ReactiveRule
import java.time.DayOfWeek
import java.time.LocalTime

/**
 * Everything the scheduler needs before the setup flow exists. Layer 3's planner takes over
 * [spellsPerDay] and [siegesPerDay].
 */
data class SpellConfig(
    val windows: Windows,
    val limits: LockLimits = LockLimits(),
    val spellsPerDay: Int = DEFAULT_SPELLS,
    /** How many of the day's takeovers are sieges. */
    val siegesPerDay: Int = 0,
    /** Package names locked during sieges and watched for reactive spells. */
    val distractions: Set<String> = Distractions.DEFAULT_PACKAGES,
    /** Null when reactive spells are off. */
    val reactive: ReactiveRule? = ReactiveRule(),
) {
    companion object {
        const val DEFAULT_SPELLS = 6
        const val MAX_SPELLS = 20
        const val MAX_SIEGES = 4

        val TEMPLATE = """
            # one rule per line. times are 24h.
            waking 07:30-23:00
            quiet 22:00-23:00
            # protect <days> <from-to> <label>
            # days: mon-fri | sat,sun | daily
            protect mon-fri 09:00-13:00 classes
            # hard limits in minutes
            limit pulse ${LockLimits.DEFAULT_PULSE_MINUTES}
            limit siege ${LockLimits.DEFAULT_SIEGE_MINUTES}
            # takeovers per day, and how many of them are sieges
            spells $DEFAULT_SPELLS
            sieges 1
            # apps locked during sieges and watched for scrolling
            distract ${Distractions.DEFAULT_NAMES.joinToString(" ")}
            # the 3rd open of those apps within 60 minutes triggers a takeover (or: reactive off)
            reactive ${ReactiveRule.DEFAULT_OPENS} ${ReactiveRule.DEFAULT_WINDOW_MINUTES}
        """.trimIndent()
    }
}

data class ConfigError(val line: Int, val message: String) {
    override fun toString(): String = "line $line: $message"
}

sealed interface ConfigParse {
    data class Ok(val config: SpellConfig) : ConfigParse
    data class Invalid(val errors: List<ConfigError>) : ConfigParse
}

/**
 * Parses the terminal-style config text. Strict: any line it does not understand is an error,
 * so a typo can never silently drop a protected block.
 */
object SpellConfigParser {

    fun parse(text: String): ConfigParse {
        val b = Builder()
        val errors = ArrayList<ConfigError>()
        text.lines().forEachIndexed { index, raw ->
            val line = raw.substringBefore('#').trim()
            if (line.isNotEmpty()) b.apply(line.split(Regex("\\s+")))?.let { errors += ConfigError(index + 1, it) }
        }
        val waking = b.waking
        if (waking == null) errors += ConfigError(0, "waking hours are required")
        if (b.sieges > b.spells) errors += ConfigError(0, "sieges (${b.sieges}) can't exceed spells (${b.spells})")
        if (errors.isNotEmpty() || waking == null) return ConfigParse.Invalid(errors)
        return ConfigParse.Ok(
            SpellConfig(
                windows = Windows(waking, b.quiet, b.blocks),
                limits = LockLimits(b.pulse, b.siege),
                spellsPerDay = b.spells,
                siegesPerDay = b.sieges,
                distractions = b.distract?.toSet() ?: Distractions.DEFAULT_PACKAGES,
                reactive = b.reactive,
            ),
        )
    }

    private class Builder {
        var waking: ClockRange? = null
        val quiet = ArrayList<ClockRange>()
        val blocks = ArrayList<ProtectedBlock>()
        var pulse = LockLimits.DEFAULT_PULSE_MINUTES
        var siege = LockLimits.DEFAULT_SIEGE_MINUTES
        var spells = SpellConfig.DEFAULT_SPELLS
        var sieges = 0
        var distract: MutableList<String>? = null
        var reactive: ReactiveRule? = ReactiveRule()

        /** Applies one rule; returns an error message or null. */
        fun apply(words: List<String>): String? = when (words[0].lowercase()) {
            "waking" -> when {
                words.size != 2 -> "expected: waking 07:30-23:00"
                waking != null -> "waking given twice"
                else -> {
                    waking = parseRange(words[1])
                    if (waking == null) "bad time range '${words[1]}'" else null
                }
            }
            "quiet" -> when {
                words.size != 2 -> "expected: quiet 22:00-23:00"
                else -> {
                    val range = parseRange(words[1])
                    if (range != null) quiet += range
                    if (range == null) "bad time range '${words[1]}'" else null
                }
            }
            "protect" -> protect(words)
            "limit" -> limit(words)
            "spells" -> {
                val count = words.getOrNull(1)?.toIntOrNull()
                if (words.size != 2 || count == null || count !in 0..SpellConfig.MAX_SPELLS) {
                    "spells must be 0-${SpellConfig.MAX_SPELLS}"
                } else {
                    spells = count
                    null
                }
            }
            "sieges" -> {
                val count = words.getOrNull(1)?.toIntOrNull()
                if (words.size != 2 || count == null || count !in 0..SpellConfig.MAX_SIEGES) {
                    "sieges must be 0-${SpellConfig.MAX_SIEGES}"
                } else {
                    sieges = count
                    null
                }
            }
            "distract" -> distract(words)
            "reactive" -> reactive(words)
            else -> "unknown rule '${words[0]}'"
        }

        private fun distract(words: List<String>): String? {
            if (words.size < 2) return "expected: distract instagram tiktok youtube"
            val list = distract ?: ArrayList<String>().also { distract = it }
            for (w in words.drop(1)) {
                list += Distractions.resolve(w) ?: return "unknown app '$w': use a name like instagram, or a package like com.example.app"
            }
            return null
        }

        private fun reactive(words: List<String>): String? {
            if (words.size == 2 && words[1].lowercase() == "off") {
                reactive = null
                return null
            }
            val opens = words.getOrNull(1)?.toIntOrNull()
            val minutes = words.getOrNull(2)?.toIntOrNull()
            if (words.size != 3 || opens == null || minutes == null || opens !in 2..20 || minutes !in 5..240) {
                return "expected: reactive 3 60 (opens 2-20, minutes 5-240) or reactive off"
            }
            reactive = ReactiveRule(opens, minutes)
            return null
        }

        private fun protect(words: List<String>): String? {
            if (words.size < 3) return "expected: protect mon-fri 09:00-13:00 label"
            val days = parseDays(words[1]) ?: return "bad days '${words[1]}'"
            val range = parseRange(words[2]) ?: return "bad time range '${words[2]}'"
            blocks += ProtectedBlock(days, range, words.drop(3).joinToString(" ").ifEmpty { "protected" })
            return null
        }

        private fun limit(words: List<String>): String? {
            if (words.size != 3) return "expected: limit pulse 4"
            val kind = when (words[1].lowercase()) {
                "pulse" -> LockKind.PULSE
                "siege" -> LockKind.SIEGE
                else -> return "limit must be pulse or siege"
            }
            val minutes = words[2].toIntOrNull()
            if (minutes == null || minutes !in 1..kind.ceilingMinutes) {
                return "limit ${words[1]} must be 1-${kind.ceilingMinutes} minutes"
            }
            if (kind == LockKind.PULSE) pulse = minutes else siege = minutes
            return null
        }
    }

    /** Canonical text for a config, parseable by [parse]. */
    fun render(config: SpellConfig): String = buildString {
        appendLine("waking ${config.windows.waking}")
        config.windows.quiet.forEach { appendLine("quiet $it") }
        config.windows.protectedBlocks.forEach { appendLine("protect ${renderDays(it.days)} ${it.range} ${it.label}") }
        appendLine("limit pulse ${config.limits.pulseMaxMinutes}")
        appendLine("limit siege ${config.limits.siegeMaxMinutes}")
        appendLine("spells ${config.spellsPerDay}")
        appendLine("sieges ${config.siegesPerDay}")
        appendLine("distract ${config.distractions.sorted().joinToString(" ")}")
        append(config.reactive?.let { "reactive ${it.opens} ${it.windowMinutes}" } ?: "reactive off")
    }

    fun parseTime(s: String): LocalTime? {
        val m = Regex("^(\\d{1,2}):(\\d{2})$").matchEntire(s) ?: return null
        val h = m.groupValues[1].toInt()
        val min = m.groupValues[2].toInt()
        if (h !in 0..23 || min !in 0..59) return null
        return LocalTime.of(h, min)
    }

    fun parseRange(s: String): ClockRange? {
        val parts = s.split('-')
        if (parts.size != 2) return null
        val a = parseTime(parts[0]) ?: return null
        val b = parseTime(parts[1]) ?: return null
        if (a == b) return null // ambiguous: empty or 24h
        return ClockRange(a, b)
    }

    fun parseDays(s: String): Set<DayOfWeek>? {
        val out = LinkedHashSet<DayOfWeek>()
        for (token in s.lowercase().split(',')) {
            when (token) {
                "daily", "every" -> out += DayOfWeek.entries
                "weekdays" -> out += DayOfWeek.entries.take(5)
                "weekends" -> out += listOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
                else -> {
                    val ends = token.split('-')
                    when (ends.size) {
                        1 -> out += day(ends[0]) ?: return null
                        2 -> {
                            val from = day(ends[0]) ?: return null
                            val to = day(ends[1]) ?: return null
                            var d = from
                            while (true) {
                                out += d
                                if (d == to) break
                                d = d.plus(1)
                            }
                        }
                        else -> return null
                    }
                }
            }
        }
        return out.takeIf { it.isNotEmpty() }
    }

    private val DAY_NAMES = mapOf(
        "mon" to DayOfWeek.MONDAY, "tue" to DayOfWeek.TUESDAY, "wed" to DayOfWeek.WEDNESDAY,
        "thu" to DayOfWeek.THURSDAY, "fri" to DayOfWeek.FRIDAY, "sat" to DayOfWeek.SATURDAY,
        "sun" to DayOfWeek.SUNDAY,
    )

    private fun day(s: String): DayOfWeek? = DAY_NAMES[s.take(3)]?.takeIf { s.length == 3 || fullName(it) == s }

    private fun fullName(d: DayOfWeek): String = d.name.lowercase()

    private fun renderDays(days: Set<DayOfWeek>): String {
        if (days.size == 7) return "daily"
        return DayOfWeek.entries.filter { it in days }.joinToString(",") { it.name.lowercase().take(3) }
    }
}
