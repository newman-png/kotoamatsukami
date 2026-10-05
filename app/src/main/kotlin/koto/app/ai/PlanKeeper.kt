package koto.app.ai

import android.content.Context
import android.util.Log
import koto.app.data.PlanStore
import koto.app.data.SpellLog
import koto.core.ai.MasterPlanner
import koto.core.plan.Books
import koto.core.plan.DayComposer
import koto.core.plan.DayInput
import koto.core.plan.History
import koto.core.plan.MasterPlan
import koto.core.plan.PlanContext
import koto.core.plan.PlanContexts
import koto.core.plan.PlanRules
import koto.core.plan.Profile
import koto.core.plan.Signals
import koto.core.plan.SlotSpec
import koto.core.plan.TaskBook
import koto.core.plan.WeekSummary
import koto.core.plan.merge
import koto.core.plan.replan
import koto.core.safety.LockKind
import koto.core.spell.SpellTask
import koto.core.time.SpellConfig
import java.time.LocalDate
import java.time.ZoneId
import kotlin.random.Random

/**
 * Everything about the plan that never needs the laptop: the day's book (from the night, or
 * composed in code on the spot), task lookup, and the weekly re-plan in code, so the plan follows
 * the logs even when the laptop stays off.
 */
object PlanKeeper {
    private const val TAG = "koto.plan"

    /** The tasks for [date], or null when there is no plan for it yet. */
    fun bookFor(context: Context, date: LocalDate, config: SpellConfig): TaskBook? {
        val store = PlanStore(context)
        var plan = store.plan() ?: return null
        plan = ensureWeek(context, store, plan, date, config)
        if (plan.weekFor(date) == null) return null
        // A book from before the last re-plan (a spare written two nights ago) is stale.
        store.book(date)?.takeIf { it.planRevision == plan.revision }?.let { return it }
        val book = Books.fallback(date.toString(), plan.weekIndexOf(date), compose(store, plan, date, config), Random.Default)
            .copy(planRevision = plan.revision)
        store.saveBook(book)
        Log.i(TAG, "composed a code book for $date: ${book.tasks.size} tasks")
        return book
    }

    /** A task by its book id ("date#index"), or null. */
    fun task(context: Context, id: String): SpellTask? {
        val date = runCatching { LocalDate.parse(id.substringBefore('#')) }.getOrNull() ?: return null
        return PlanStore(context).book(date)?.task(id)
    }

    fun compose(store: PlanStore, plan: MasterPlan, date: LocalDate, config: SpellConfig): List<SlotSpec> {
        val profile = store.profile()
        val stats = store.summaries().takeLast(2).flatMap { it.topics }
        return DayComposer.compose(
            DayInput(
                plan = plan,
                date = date,
                allowedMinutes = config.windows.allowedMinutes(date).size,
                maxSiegeMinutes = maxSiegeMinutes(config),
                priorities = profile?.priorities.orEmpty(),
                topicOrder = History.topicOrder(plan.intent, stats),
            ),
        )
    }

    /** The lock limit, less the siege's own release margin. */
    fun maxSiegeMinutes(config: SpellConfig): Int = (config.limits.maxMs(LockKind.SIEGE) / 60_000L).toInt() - 2

    /**
     * Once per week, at the first look at a new week: summarise the week that ended, read the
     * signals, and rebuild the plan from where the user really is. Plain code; the laptop may
     * improve on it later the same night.
     */
    @Synchronized
    fun ensureWeek(context: Context, store: PlanStore, plan: MasterPlan, date: LocalDate, config: SpellConfig): MasterPlan {
        val week = plan.weekIndexOf(date)
        val status = store.status()
        if (week < 2 || week > PlanRules.MAX_WEEKS || maxOf(status.codeReplanWeek, status.aiReplanWeek) >= week) return plan
        val profile = store.profile() ?: return plan
        val prepared = prepareReplan(context, store, profile, plan, week, date, config)
        val rebuilt = MasterPlanner.codeReplan(prepared.ctx, plan.intent)
        val merged = rebuilt?.let { plan.merge(it) } ?: plan
        if (rebuilt != null) store.savePlan(merged) else Log.w(TAG, "code re-plan for week $week broke a rule; keeping the plan")
        store.updateStatus { it.copy(codeReplanWeek = week) }
        Log.i(TAG, "week $week re-planned in code; signals: ${prepared.signals.reasons}")
        return merged
    }

    class Prepared(val ctx: PlanContext, val signals: Signals, val last: WeekSummary)

    /** Summarises the week before [week], saves the summary, and builds the re-plan context. */
    fun prepareReplan(context: Context, store: PlanStore, profile: Profile, plan: MasterPlan, week: Int, date: LocalDate, config: SpellConfig): Prepared {
        val zone = ZoneId.systemDefault()
        val start = LocalDate.parse(plan.start).plusWeeks((week - 2).toLong())
        val from = start.atStartOfDay(zone).toInstant().toEpochMilli()
        val to = start.plusDays(7).atStartOfDay(zone).toInstant().toEpochMilli()
        val records = SpellLog.records(context, from, to)
        // Late nights run into the morning after the week: read opens a day further.
        val opens = SpellLog.opens(context, from, to + 24 * 3_600_000L)
        val late = History.lateNights(opens, config.windows, start, zone)
        val last = History.summarise(records, plan, week - 1, zone, late)
        store.saveSummary(last)
        val previous = store.summaries().firstOrNull { it.week == week - 2 }
        val signals = History.signals(last, previous)
        val ctx = PlanContexts.replan(profile, config.windows, plan, week, date, last, signals)
        return Prepared(ctx, signals, last)
    }
}
