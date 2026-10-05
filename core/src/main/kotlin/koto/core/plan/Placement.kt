package koto.core.plan

import koto.core.time.DayPlanner
import koto.core.time.Windows
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import kotlin.random.Random

/**
 * Picks the hidden moments for a day's tasks. Sieges go first, each into a stretch of allowed
 * time long enough to hold it; pulses are scattered over what is left, apart from each other and
 * from the sieges. Whatever doesn't fit is dropped, pulses from the end of the list first.
 */
object Placement {
    const val SIEGE_GAP_MINUTES = 10
    const val PULSE_GAP_MINUTES = 25
    const val PULSE_SIEGE_GAP_MINUTES = 5

    /** Minutes of allowed time a siege needs beyond its length: the call phase and the release. */
    const val SIEGE_MARGIN_MINUTES = 2

    /**
     * [siegeMinutes] has one entry per task: its length for a siege, null for a pulse. Returns a
     * time for each task, or null where it didn't fit. Nothing lands in the first
     * [DayPlanner.WAKE_BUFFER_MINUTES] after waking or before [notBefore].
     */
    fun place(
        date: LocalDate,
        windows: Windows,
        siegeMinutes: List<Int?>,
        random: Random,
        notBefore: LocalDateTime? = null,
    ): List<LocalDateTime?> {
        val out = MutableList<LocalDateTime?>(siegeMinutes.size) { null }
        val wakeStart = windows.wakingPeriod(date).first
        val earliest = maxOf(wakeStart.plusMinutes(DayPlanner.WAKE_BUFFER_MINUTES.toLong()), notBefore ?: wakeStart)
        val allowed = windows.allowedMinutes(date).filter { !it.isBefore(earliest) }
        if (allowed.isEmpty()) return out
        val origin = allowed.first()
        fun index(t: LocalDateTime) = ChronoUnit.MINUTES.between(origin, t).toInt()
        val size = index(allowed.last()) + 1
        val free = BooleanArray(size).also { f -> allowed.forEach { f[index(it)] = true } }
        val pulseFree = free.copyOf()

        val siegeOrder = siegeMinutes.indices.filter { siegeMinutes[it] != null }.sortedByDescending { siegeMinutes[it] }
        for (i in siegeOrder) {
            val length = siegeMinutes[i]!! + SIEGE_MARGIN_MINUTES
            val run = IntArray(size + 1)
            for (m in size - 1 downTo 0) run[m] = if (free[m]) run[m + 1] + 1 else 0
            val starts = (0 until size).filter { run[it] >= length }
            if (starts.isEmpty()) continue
            val s = starts.random(random)
            out[i] = origin.plusMinutes(s.toLong())
            for (m in s - SIEGE_GAP_MINUTES until s + length + SIEGE_GAP_MINUTES) if (m in 0 until size) free[m] = false
            for (m in s - PULSE_SIEGE_GAP_MINUTES until s + length + PULSE_SIEGE_GAP_MINUTES) if (m in 0 until size) pulseFree[m] = false
        }

        val pulses = siegeMinutes.indices.filter { siegeMinutes[it] == null }
        val minutes = (0 until size).filter { pulseFree[it] }
        val times = spread(minutes, pulses.size, PULSE_GAP_MINUTES, random)
        // Earlier pulses in the list matter more: they get places first, in random order of time.
        val chosen = pulses.take(times.size).shuffled(random)
        chosen.forEachIndexed { k, i -> out[i] = origin.plusMinutes(times[k].toLong()).plusSeconds(random.nextLong(60)) }
        return out
    }

    /**
     * Up to [count] of [minutes] (sorted, distinct), at least [gap] apart. Index distance in a
     * sorted list of distinct minutes never exceeds real distance, so a gap kept on indices holds.
     */
    private fun spread(minutes: List<Int>, count: Int, gap: Int, random: Random): List<Int> {
        if (count <= 0 || minutes.isEmpty()) return emptyList()
        var k = count
        while (k > 1 && (k - 1) * gap >= minutes.size) k--
        val span = minutes.size - 1 - (k - 1) * gap
        val picks = List(k) { random.nextInt(span + 1) }.sorted()
        return picks.mapIndexed { i, p -> minutes[p + i * gap] }
    }
}
