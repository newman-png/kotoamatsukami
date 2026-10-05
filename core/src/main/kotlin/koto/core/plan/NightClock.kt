package koto.core.plan

import koto.core.time.Windows
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * When the night's planning happens. The work for a day is done in the night before it: after the
 * previous waking period ends and before this one starts. The plan never runs ahead more than
 * [BOOK_DAYS] days.
 */
object NightClock {
    /** Days of tasks written ahead: tomorrow, and the day after as a spare. */
    const val BOOK_DAYS = 2

    /** Minutes after waking hours end before the night's work starts. */
    const val START_AFTER_MINUTES = 20L

    /** The night's work must be done this long before the day starts. */
    const val DONE_BEFORE_MINUTES = 30L

    const val RETRY_MINUTES = 30L

    /** The date of the next waking period that hasn't started yet. */
    fun nextWakingDate(windows: Windows, now: LocalDateTime): LocalDate {
        val today = now.toLocalDate()
        return if (windows.wakingPeriod(today).first.isAfter(now)) today else today.plusDays(1)
    }

    /** When the night before [date] opens for planning. */
    fun nightStart(windows: Windows, date: LocalDate): LocalDateTime =
        windows.wakingPeriod(date.minusDays(1)).second.plusMinutes(START_AFTER_MINUTES)

    /** When the night before [date] closes. */
    fun nightEnd(windows: Windows, date: LocalDate): LocalDateTime =
        windows.wakingPeriod(date).first.minusMinutes(DONE_BEFORE_MINUTES)

    /**
     * The next moment the runner should work, given that the night for [doneFor] (if any) is
     * finished and the last attempt was at [lastAttempt].
     */
    fun nextRun(windows: Windows, now: LocalDateTime, doneFor: LocalDate?, lastAttempt: LocalDateTime?): LocalDateTime {
        val date = nextWakingDate(windows, now)
        if (doneFor != null && !doneFor.isBefore(date)) return nightStart(windows, doneFor.plusDays(1))
        val start = nightStart(windows, date)
        val end = nightEnd(windows, date)
        val retry = lastAttempt?.plusMinutes(RETRY_MINUTES)
        return when {
            now.isBefore(start) -> start
            now.isBefore(end) -> if (retry != null && retry.isAfter(now)) retry else now
            else -> nightStart(windows, date.plusDays(1))
        }
    }
}

/** Checks on the setup itself, before any planning: problems no plan could fix. */
object SetupChecks {
    fun problems(ctx: PlanContext): List<String> {
        val out = ArrayList<String>()
        if (ctx.sleepOpportunityHours + 1e-9 < ctx.sleepTargetHours) {
            out += "Waking hours leave ${"%.1f".format(java.util.Locale.ROOT, ctx.sleepOpportunityHours)} hours for sleep. The target is ${ctx.sleepTargetHours.toInt()}. Shorten waking hours."
        }
        if (ctx.capacity() < MIN_CAPACITY) {
            out += "Windows leave about ${ctx.capacity()} minutes a day for planned work. Allow more time."
        }
        return out
    }

    const val MIN_CAPACITY = 60
}
