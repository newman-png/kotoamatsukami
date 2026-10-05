package koto.core.plan

import koto.core.spell.Domain
import koto.core.spell.Feedback
import koto.core.spell.Outcome
import koto.core.spell.Source
import koto.core.spell.TaskKind
import koto.core.time.Windows
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

/** One logged takeover, as the planner reads it back. */
data class SpellRecord(
    val startedAtMs: Long,
    val kind: TaskKind,
    val domain: Domain,
    val topic: String,
    val source: Source,
    val floor: Boolean,
    /** Length of the task as given (the version shown), in seconds; 0 when unknown. */
    val plannedSeconds: Int,
    val outcome: Outcome?,
    val activeMs: Long,
    val latencyMs: Long?,
    val feedback: Feedback?,
)

/** Planned against done for one load domain over one week. Minutes are totals for the week. */
@Serializable
data class DomainWeek(val domain: Domain, val plannedPerDay: Int, val offeredMinutes: Int, val doneMinutes: Int)

/** How one goal topic went: what the writer uses to find the weakest topic. */
@Serializable
data class TopicStat(
    val domain: Domain,
    val topic: String,
    val offered: Int,
    val doneMinutes: Int,
    val skipped: Int,
    val tooMuch: Int,
    val easy: Int,
)

/** A week of logs boiled down. This, never raw logs, is what reaches the AI. */
@Serializable
data class WeekSummary(
    val week: Int,
    val start: String,
    val phase: Phase,
    val light: Boolean,
    val takeovers: Int,
    val done: Int,
    val skipped: Int,
    val unanswered: Int,
    val stopped: Int,
    val floors: Int,
    val medianLatencySeconds: Double?,
    val easy: Int,
    val fine: Int,
    val tooMuch: Int,
    /** Longest run of "too much" taps in a row. */
    val tooMuchRun: Int,
    val domains: List<DomainWeek>,
    val topics: List<TopicStat>,
    /** Nights with distraction-app use well after waking hours ended. */
    val lateNights: Int,
    val dreadGuard: Boolean,
    val sleepGuard: Boolean,
) {
    fun domain(d: Domain): DomainWeek? = domains.firstOrNull { it.domain == d }
}

/** What the weekly re-plan reads from the logs. */
data class Signals(
    val dread: Boolean,
    val sleepSlipping: Boolean,
    val loadFactor: Double,
    val extraMercy: Int,
    val reasons: List<String>,
)

/** Plain code over the logs: summaries, dread and sleep signals, and the ramp's real starting point. */
object History {
    const val DREAD_RUN = 3
    const val DREAD_TOO_MUCH = 3
    const val DREAD_TOO_MUCH_SHARE = 0.3
    const val DREAD_SKIP_SHARE = 0.4
    const val DREAD_MIN_TAKEOVERS = 10
    const val LATE_NIGHTS = 3

    /** A night counts as late when a distraction app is opened this long after waking hours end. */
    const val LATE_AFTER_MINUTES = 30L

    const val DREAD_LOAD = 0.85
    const val SLEEP_LOAD = 0.9

    fun summarise(records: List<SpellRecord>, plan: MasterPlan, weekIndex: Int, zone: ZoneId, lateNights: Int): WeekSummary {
        val week = plan.week(weekIndex) ?: plan.weeks.last()
        val start = LocalDate.parse(plan.start).plusWeeks((weekIndex - 1).toLong())
        val end = start.plusDays(7)
        val rs = records
            .filter { it.source != Source.TEST }
            .filter { dateOf(it, zone).let { d -> !d.isBefore(start) && d.isBefore(end) } }
            .sortedBy { it.startedAtMs }
        val answered = rs.mapNotNull { it.feedback }
        val latencies = rs.mapNotNull { it.latencyMs }.sorted()

        val domains = LOAD_DOMAINS.map { d ->
            val sieges = rs.filter { it.domain == d && it.kind == TaskKind.SIEGE }
            DomainWeek(
                domain = d,
                plannedPerDay = week.load(d),
                offeredMinutes = sieges.filter { it.source == Source.SCHEDULE }.sumOf { it.plannedSeconds } / 60,
                doneMinutes = (rs.filter { it.domain == d }.sumOf { worked(it) } / 60_000L).toInt(),
            )
        }
        val topics = rs.filter { it.topic.isNotEmpty() }.groupBy { it.domain to it.topic }.map { (key, list) ->
            TopicStat(
                domain = key.first,
                topic = key.second,
                offered = list.size,
                doneMinutes = (list.sumOf { worked(it) } / 60_000L).toInt(),
                skipped = list.count { it.outcome == Outcome.SKIPPED || it.outcome == Outcome.ABANDONED },
                tooMuch = list.count { it.feedback == Feedback.TOO_MUCH },
                easy = list.count { it.feedback == Feedback.EASY },
            )
        }.sortedWith(compareBy({ it.domain }, { it.topic }))

        return WeekSummary(
            week = weekIndex,
            start = start.toString(),
            phase = week.phase,
            light = week.light,
            takeovers = rs.size,
            done = rs.count { it.outcome == Outcome.DONE },
            skipped = rs.count { it.outcome == Outcome.SKIPPED },
            unanswered = rs.count { it.outcome == Outcome.TIMEOUT },
            stopped = rs.count { it.outcome == Outcome.ABANDONED },
            floors = rs.count { it.floor },
            medianLatencySeconds = latencies.takeIf { it.isNotEmpty() }?.let { median(it) / 1000.0 },
            easy = answered.count { it == Feedback.EASY },
            fine = answered.count { it == Feedback.FINE },
            tooMuch = answered.count { it == Feedback.TOO_MUCH },
            tooMuchRun = longestRun(answered),
            domains = domains,
            topics = topics,
            lateNights = lateNights,
            dreadGuard = week.dreadGuard,
            sleepGuard = week.sleepGuard,
        )
    }

    /**
     * The dread watch and the sleep watch. Dread: a run of "too much", a high share of it, many
     * skips, or response times climbing. Sleep: phone use late into the night on several nights.
     */
    fun signals(last: WeekSummary, previous: WeekSummary?): Signals {
        val reasons = ArrayList<String>()
        val given = last.easy + last.fine + last.tooMuch
        if (last.tooMuchRun >= DREAD_RUN) reasons += "${last.tooMuchRun} 'too much' in a row"
        if (last.tooMuch >= DREAD_TOO_MUCH && last.tooMuch >= given * DREAD_TOO_MUCH_SHARE) reasons += "'too much' on ${last.tooMuch} of $given answers"
        val dodged = last.skipped + last.unanswered + last.stopped
        if (last.takeovers >= DREAD_MIN_TAKEOVERS && dodged >= last.takeovers * DREAD_SKIP_SHARE) reasons += "$dodged of ${last.takeovers} takeovers dodged"
        val now = last.medianLatencySeconds
        val before = previous?.medianLatencySeconds
        if (now != null && before != null && now >= before * 1.5 && now - before >= 10) {
            reasons += "response time rose from ${before.roundToInt()} s to ${now.roundToInt()} s"
        }
        val dread = reasons.isNotEmpty()
        val sleep = last.lateNights >= LATE_NIGHTS
        if (sleep) reasons += "late phone use on ${last.lateNights} nights"
        return Signals(
            dread = dread,
            sleepSlipping = sleep,
            loadFactor = when {
                dread -> DREAD_LOAD
                sleep -> SLEEP_LOAD
                else -> 1.0
            },
            extraMercy = when {
                dread -> 2
                last.tooMuch >= 2 -> 1
                else -> 0
            },
            reasons = reasons,
        )
    }

    /**
     * Where the next weeks ramp from: the plan's level, scaled down by how much of the offered
     * siege time was actually done (never below half, never above the plan). Domains that had no
     * sieges keep the plan's level.
     */
    fun rampFrom(plan: MasterPlan, last: WeekSummary): Map<Domain, Int> {
        val ref = reference(plan, last.week)
        return LOAD_DOMAINS.associateWith { d ->
            val r = ref.getValue(d)
            val dw = last.domain(d)
            if (dw == null || dw.offeredMinutes <= 0) {
                r
            } else {
                (r * (dw.doneMinutes.toDouble() / dw.offeredMinutes).coerceIn(0.5, 1.0)).roundToInt()
            }
        }
    }

    /** The level each domain stood at after [weekIndex]: light weeks don't move it. */
    fun reference(plan: MasterPlan, weekIndex: Int): Map<Domain, Int> {
        val w = plan.weeks.filter { it.index <= weekIndex && !it.light }.maxByOrNull { it.index }
        return LOAD_DOMAINS.associateWith { w?.load(it) ?: plan.rampFrom[it] ?: 0 }
    }

    /** Topics per goal domain, weakest first: most skipped or "too much" relative to use, then least practised. */
    fun topicOrder(intent: PlanIntent, stats: List<TopicStat>): Map<Domain, List<String>> =
        intent.activeGoalDomains().associateWith { d ->
            val byTopic = stats.filter { it.domain == d }.groupBy { it.topic }
            intent.topics(d).sortedWith(
                compareByDescending<String> { t ->
                    val s = byTopic[t].orEmpty()
                    val offered = s.sumOf { it.offered }
                    if (offered == 0) 0.0 else (s.sumOf { it.skipped + it.tooMuch } - s.sumOf { it.easy } * 0.5) / offered
                }.thenBy { t -> byTopic[t].orEmpty().sumOf { it.doneMinutes } },
            )
        }

    /** Nights in the 7 days from [weekStart] with a distraction-app open well into sleeping time. */
    fun lateNights(opensMs: List<Long>, windows: Windows, weekStart: LocalDate, zone: ZoneId): Int =
        (0L until 7L).count { i ->
            val date = weekStart.plusDays(i)
            val from = windows.wakingPeriod(date).second.plusMinutes(LATE_AFTER_MINUTES).atZone(zone).toInstant().toEpochMilli()
            val to = windows.wakingPeriod(date.plusDays(1)).first.atZone(zone).toInstant().toEpochMilli()
            opensMs.any { it in from until to }
        }

    /** A short account of one day for the nightly writer. */
    fun dayLines(records: List<SpellRecord>): List<String> {
        val rs = records.filter { it.source != Source.TEST }
        if (rs.isEmpty()) return listOf("no takeovers.")
        val out = ArrayList<String>()
        out += "${rs.size} takeovers: ${rs.count { it.outcome == Outcome.DONE }} done, " +
            "${rs.count { it.outcome == Outcome.SKIPPED }} skipped, ${rs.count { it.outcome == Outcome.TIMEOUT }} unanswered, " +
            "${rs.count { it.outcome == Outcome.ABANDONED }} stopped early."
        for (r in rs.filter { it.feedback == Feedback.TOO_MUCH || it.outcome == Outcome.SKIPPED || it.outcome == Outcome.ABANDONED }) {
            val what = listOfNotNull(r.domain.name.lowercase(), r.kind.name.lowercase(), r.topic.ifEmpty { null }).joinToString(" ")
            val how = when {
                r.feedback == Feedback.TOO_MUCH -> "too much"
                r.outcome == Outcome.ABANDONED -> "stopped after ${r.activeMs / 60_000} min"
                else -> "skipped"
            }
            out += "$what: $how."
        }
        return out
    }

    /** Time actually worked: a done task, or the part of a siege before it was stopped. */
    private fun worked(r: SpellRecord): Long = when (r.outcome) {
        Outcome.DONE -> r.activeMs
        Outcome.ABANDONED -> if (r.kind == TaskKind.SIEGE) r.activeMs else 0
        else -> 0
    }

    private fun dateOf(r: SpellRecord, zone: ZoneId): LocalDate = Instant.ofEpochMilli(r.startedAtMs).atZone(zone).toLocalDate()

    private fun median(sorted: List<Long>): Double =
        if (sorted.size % 2 == 1) sorted[sorted.size / 2].toDouble() else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0

    private fun longestRun(answers: List<Feedback>): Int {
        var best = 0
        var run = 0
        for (a in answers) {
            run = if (a == Feedback.TOO_MUCH) run + 1 else 0
            best = maxOf(best, run)
        }
        return best
    }
}
