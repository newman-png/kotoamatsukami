package koto.core.plan

import koto.core.time.Windows
import java.time.Duration
import java.time.LocalDate

/** Builds [PlanContext]s from the profile and the windows config. */
object PlanContexts {

    /** Allowed takeover minutes for each weekday (Monday first), measured over the 7 days from [from]. */
    fun allowedMinutes(windows: Windows, from: LocalDate): List<Int> {
        val byDay = IntArray(7)
        for (i in 0L until 7L) {
            val date = from.plusDays(i)
            byDay[date.dayOfWeek.value - 1] = windows.allowedMinutes(date).size
        }
        return byDay.toList()
    }

    /** Hours from the end of waking hours to the start of the next waking period. */
    fun sleepOpportunityHours(windows: Windows): Double {
        val (start, end) = windows.wakingPeriod(LocalDate.of(2026, 1, 5))
        val awake = Duration.between(start, end).toMinutes()
        return (24 * 60 - awake) / 60.0
    }

    /** The first plan, ramping from the setup baseline. */
    fun initial(profile: Profile, windows: Windows, today: LocalDate): PlanContext = PlanContext(
        today = today.toString(),
        allowedMinutes = allowedMinutes(windows, today),
        sleepOpportunityHours = sleepOpportunityHours(windows),
        sleepTargetHours = Targets.SLEEP_HOURS,
        deadlines = profile.deadlines,
        priorities = profile.priorities,
        rampFrom = Targets.baselineMinutes(profile.baseline),
        targets = Targets.minutes(profile.baseline),
        baselineNutritionPulses = Targets.baselineNutritionPulses(profile.baseline),
    )
}

/** The weekly re-plan's context: the plan's own start, the new week, and what the logs say. */
fun PlanContexts.replan(
    profile: Profile,
    windows: Windows,
    plan: MasterPlan,
    week: Int,
    today: LocalDate,
    last: WeekSummary,
    signals: Signals,
): PlanContext = initial(profile, windows, today).copy(
    planStart = plan.start,
    firstWeek = week,
    rampFrom = History.rampFrom(plan, last),
    loadFactor = signals.loadFactor,
    extraMercy = signals.extraMercy,
    dreadGuard = signals.dread,
    sleepGuard = signals.sleepSlipping,
)

/** A re-plan's weeks replace the old plan's from its first week on. */
fun MasterPlan.merge(replan: MasterPlan): MasterPlan {
    val from = replan.weeks.firstOrNull()?.index ?: return this
    return copy(weeks = weeks.filter { it.index < from } + replan.weeks, intent = replan.intent, revision = revision + 1)
}
