package koto.core.plan

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The compact state document: profile, goals, where the plan stands, last week in some detail,
 * older weeks in a line each. The only thing about the user that is ever sent to the AI.
 */
object CompactState {
    const val OLDER_WEEKS = 8

    fun render(profile: Profile, plan: MasterPlan?, today: LocalDate, summaries: List<WeekSummary>): String = buildString {
        appendLine("PROFILE")
        append(profileText(profile))
        if (plan != null) {
            appendLine()
            appendLine("PLAN")
            val w = plan.weekFor(today)
            if (w != null) {
                appendLine("today: $today (${dayName(today.dayOfWeek.value)}), week ${w.index} of ${plan.weeks.last().index}, phase ${w.phase}" + if (w.light) ", light week" else "")
                appendLine("light day: ${dayName(w.lightDay)}. workout days: ${plan.intent.workoutDays.joinToString { dayName(it) }}.")
                appendLine("this week, minutes per day: " + LOAD_DOMAINS.joinToString { "${it.name.lowercase()} ${w.load(it)}" })
                if (w.dreadGuard) appendLine("load was cut this week: dread signals.")
                if (w.sleepGuard) appendLine("load was cut this week to protect sleep.")
            }
            for (g in plan.intent.goals) {
                val dl = g.deadlineIds.joinToString { id -> profile.deadlines.firstOrNull { it.id == id }?.let { "${it.label} ${it.date}" } ?: id }
                appendLine("goal ${g.domain}: ${g.summary}" + (if (dl.isNotEmpty()) " (by $dl)" else "") + (if (g.topics.isNotEmpty()) ". topics: ${g.topics.joinToString()}" else ""))
            }
        }
        val sorted = summaries.sortedByDescending { it.week }
        sorted.firstOrNull()?.let {
            appendLine()
            appendLine("LAST WEEK")
            append(lastWeekText(it, sorted.getOrNull(1)))
        }
        val older = sorted.drop(1).take(OLDER_WEEKS)
        if (older.isNotEmpty()) {
            appendLine()
            appendLine("OLDER WEEKS")
            older.forEach { appendLine(olderLine(it)) }
        }
    }.trimEnd() + "\n"

    fun profileText(p: Profile): String = buildString {
        val b = p.baseline
        appendLine("goals, in the user's own words: \"${p.goalsText.trim()}\"")
        if (p.deadlines.isEmpty()) {
            appendLine("deadlines: none.")
        } else {
            appendLine("deadlines: " + p.deadlines.joinToString("; ") { "${it.id} ${it.date} ${it.label}" })
        }
        appendLine(
            "at setup: sleep ${num(b.sleepHours)} h (bed ${b.bedtime}, up ${b.wakeTime}), workouts ${b.workoutsPerWeek}/week, " +
                "steps ${b.dailySteps}/day, study ${num(b.studyHoursPerDay)} h/day, screen ${num(b.screenHoursPerDay)} h/day, " +
                "diet ${b.dietQuality}/5, chores ${b.choresMinutesPerDay} min/day, energy ${b.energy}/10, " +
                "italian ${b.italianMinutesPerDay} min/day, career ${num(b.careerHoursPerWeek)} h/week",
        )
        appendLine("priorities, highest first: " + p.priorities.joinToString(" > ") { it.name.lowercase() })
    }

    private fun lastWeekText(s: WeekSummary, previous: WeekSummary?): String = buildString {
        appendLine("week ${s.week} (${s.phase}${if (s.light) ", light" else ""}): ${s.takeovers} takeovers, ${s.done} done, ${s.skipped} skipped, ${s.unanswered} unanswered, ${s.stopped} stopped early, ${s.floors} at the floor.")
        val latency = s.medianLatencySeconds?.let { "${it.roundToInt()} s" } ?: "n/a"
        val before = previous?.medianLatencySeconds?.let { " (week before: ${it.roundToInt()} s)" }.orEmpty()
        appendLine("median response time: $latency$before.")
        appendLine("feedback: easy ${s.easy}, fine ${s.fine}, too much ${s.tooMuch} (longest run of too much: ${s.tooMuchRun}).")
        appendLine("late nights with phone use: ${s.lateNights} of 7.")
        val worked = s.domains.filter { it.plannedPerDay > 0 || it.doneMinutes > 0 }
        if (worked.isNotEmpty()) {
            appendLine("minutes per day planned / done: " + worked.joinToString { "${it.domain.name.lowercase()} ${it.plannedPerDay} / ${perDay(it.doneMinutes)}" })
        }
        for (t in s.topics) {
            appendLine("topic ${t.domain.name.lowercase()} '${t.topic}': ${t.offered} tasks, ${t.doneMinutes} min done, ${t.skipped} skipped, ${t.tooMuch} too much, ${t.easy} easy.")
        }
    }

    private fun olderLine(s: WeekSummary): String {
        val share = if (s.takeovers == 0) "none" else "${s.done * 100 / s.takeovers}% done"
        val latency = s.medianLatencySeconds?.let { ", median ${it.roundToInt()} s" }.orEmpty()
        val guards = listOfNotNull("dread cut".takeIf { s.dreadGuard }, "sleep cut".takeIf { s.sleepGuard }).joinToString(", ")
        return "week ${s.week} (${s.phase}${if (s.light) ", light" else ""}): ${s.takeovers} takeovers, $share$latency, too much ${s.tooMuch}" +
            (if (guards.isNotEmpty()) ", $guards" else "") + "."
    }

    /** The day's slots for the nightly writer, one line each, numbered. */
    fun slotLines(specs: List<SlotSpec>): String = buildString {
        specs.forEachIndexed { i, s ->
            if (!Books.aiWritten(s)) return@forEachIndexed
            val what = when (s.role) {
                SlotRole.WORK -> "siege, ${s.minutes} minutes, ${s.domain.name.lowercase()}"
                SlotRole.MICRO -> "micro pulse, ${s.domain.name.lowercase()}, up to ${s.maxSeconds} seconds"
                SlotRole.SCOUT -> "scouting pulse, ${s.domain.name.lowercase()}, up to 20 seconds"
                else -> s.role.name.lowercase()
            }
            val topics = if (s.topics.isEmpty()) "" else "; topics, weakest first: ${s.topics.joinToString { "'$it'" }}"
            appendLine("slot $i: $what$topics")
        }
    }

    private fun perDay(totalMinutes: Int): Int = (totalMinutes / 7.0).roundToInt()

    private fun num(d: Double): String = if (d == d.toLong().toDouble()) d.toLong().toString() else "%.1f".format(Locale.ROOT, d)

    private fun dayName(day: Int): String = DayOfWeek.of(day).getDisplayName(TextStyle.FULL, Locale.ENGLISH)
}
