package koto.core.time

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** A daily clock range, end-exclusive. If [end] is not after [start] the range crosses midnight. */
data class ClockRange(val start: LocalTime, val end: LocalTime) {
    val crossesMidnight: Boolean get() = !end.isAfter(start)

    fun contains(t: LocalTime): Boolean =
        if (crossesMidnight) !t.isBefore(start) || t.isBefore(end) else !t.isBefore(start) && t.isBefore(end)

    override fun toString(): String = "${fmt(start)}-${fmt(end)}"

    companion object {
        fun fmt(t: LocalTime): String = "%02d:%02d".format(t.hour, t.minute)
    }
}

/**
 * A recurring block in which no takeover may happen (class, meeting, commute).
 * For a block that crosses midnight, [days] names the day it starts on.
 */
data class ProtectedBlock(val days: Set<DayOfWeek>, val range: ClockRange, val label: String) {
    fun contains(at: LocalDateTime): Boolean {
        val t = at.toLocalTime()
        val day = at.dayOfWeek
        if (!range.crossesMidnight) return day in days && range.contains(t)
        return (day in days && !t.isBefore(range.start)) || (day.minus(1) in days && t.isBefore(range.end))
    }
}

/**
 * When takeovers are allowed: inside waking hours, outside every quiet range and protected block.
 * A waking range may cross midnight (e.g. 09:00-01:00); the waking period then belongs to the
 * date it starts on.
 */
data class Windows(
    val waking: ClockRange,
    val quiet: List<ClockRange> = emptyList(),
    val protectedBlocks: List<ProtectedBlock> = emptyList(),
) {
    fun allows(at: LocalDateTime): Boolean {
        val t = at.toLocalTime()
        if (!waking.contains(t)) return false
        if (quiet.any { it.contains(t) }) return false
        if (protectedBlocks.any { it.contains(at) }) return false
        return true
    }

    fun protectedBy(at: LocalDateTime): ProtectedBlock? = protectedBlocks.firstOrNull { it.contains(at) }

    /** Start (inclusive) and end (exclusive) of the waking period that starts on [date]. */
    fun wakingPeriod(date: LocalDate): Pair<LocalDateTime, LocalDateTime> {
        val start = date.atTime(waking.start)
        val end = if (waking.crossesMidnight) date.plusDays(1).atTime(waking.end) else date.atTime(waking.end)
        return start to end
    }

    /** The date whose waking period contains [at], or null if [at] is outside waking hours. */
    fun wakingDateOf(at: LocalDateTime): LocalDate? {
        val today = at.toLocalDate()
        for (date in listOf(today, today.minusDays(1))) {
            val (start, end) = wakingPeriod(date)
            if (!at.isBefore(start) && at.isBefore(end)) return date
        }
        return null
    }

    /** True if every minute of [minutes] starting at [start] is allowed (a siege fits). */
    fun allowsSpan(start: LocalDateTime, minutes: Int): Boolean =
        (0 until minutes).all { allows(start.plusMinutes(it.toLong())) }

    /** Every whole minute in the waking period of [date] that [allows] a takeover, in order. */
    fun allowedMinutes(date: LocalDate): List<LocalDateTime> {
        val (start, end) = wakingPeriod(date)
        val out = ArrayList<LocalDateTime>()
        var m = start
        while (m.isBefore(end)) {
            if (allows(m)) out.add(m)
            m = m.plusMinutes(1)
        }
        return out
    }
}
