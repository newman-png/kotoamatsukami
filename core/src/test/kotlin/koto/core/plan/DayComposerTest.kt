package koto.core.plan

import koto.core.spell.Domain
import koto.core.spell.TaskKind
import java.time.LocalDate
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DayComposerTest {
    private val plan = Fixtures.plan()
    private val start = LocalDate.parse(plan.start)

    private fun input(date: LocalDate, allowed: Int = 690, maxSiege: Int = 118, order: Map<Domain, List<String>> = emptyMap()) =
        DayInput(plan, date, allowed, maxSiege, Fixtures.profile.priorities, order)

    private fun day(week: Int, weekday: Int) = start.plusWeeks((week - 1).toLong()).plusDays((weekday - 1).toLong())

    private fun book(specs: List<SlotSpec>, date: LocalDate) = Books.fallback(date.toString(), 1, specs, Random(7))

    @Test
    fun `conditioning days are easy pulses only, with a scouting task`() {
        for (d in 0L until 14L) {
            val date = start.plusDays(d)
            val specs = DayComposer.compose(input(date))
            assertTrue(specs.none { it.kind == TaskKind.SIEGE }, "siege on $date")
            assertTrue(specs.size >= DayComposer.MIN_PULSES)
            assertTrue(specs.any { it.role == SlotRole.SCOUT })
            for (t in book(specs, date).tasks) {
                assertTrue(t.normal.seconds in 1..20, "${t.normal.command} runs ${t.normal.seconds} s in conditioning")
                assertTrue(t.floor.seconds <= t.normal.seconds)
            }
        }
    }

    @Test
    fun `foundation days build the level and leave the goals to scouting`() {
        val monday = day(3, 1) // a workout day
        val specs = DayComposer.compose(input(monday))
        val siegeDomains = specs.filter { it.kind == TaskKind.SIEGE }.map { it.domain }.toSet()
        assertTrue(Domain.STUDY !in siegeDomains && Domain.ITALIAN !in siegeDomains && Domain.CAREER !in siegeDomains)
        assertTrue(Domain.STEPS in siegeDomains, "walks start in foundation: $siegeDomains")
        assertTrue(specs.any { it.role == SlotRole.SCOUT })
    }

    @Test
    fun `goal weeks schedule study sieges that add up to the plan`() {
        val w = plan.weeks.first { it.phase == Phase.GOAL && !it.light && it.load(Domain.STUDY) >= 150 }
        var total = 0
        for (wd in 1..7) {
            val specs = DayComposer.compose(input(day(w.index, wd)))
            val study = specs.filter { it.kind == TaskKind.SIEGE && it.domain == Domain.STUDY }
            assertTrue(study.all { it.minutes <= w.siegeBlockMinutes }, "blocks over ${w.siegeBlockMinutes}: $study")
            total += study.sumOf { it.minutes }
        }
        val expected = w.load(Domain.STUDY) * 7
        assertTrue(total in (expected * 0.9).toInt()..(expected * 1.1).toInt(), "week ${w.index}: $total of $expected")
    }

    @Test
    fun `the light day carries less than the others`() {
        val w = plan.weeks.first { it.phase == Phase.GOAL && !it.light }
        fun studyOn(wd: Int) = DayComposer.compose(input(day(w.index, wd)))
            .filter { it.kind == TaskKind.SIEGE && it.domain == Domain.STUDY }.sumOf { it.minutes }
        val light = (1..7).first { day(w.index, it).dayOfWeek.value == w.lightDay }
        val normal = (1..7).first { it != light }
        assertTrue(studyOn(light) < studyOn(normal), "light ${studyOn(light)} vs ${studyOn(normal)}")
    }

    @Test
    fun `workouts land on the workout days only`() {
        val w = plan.weeks.first { it.phase == Phase.GOAL && !it.light && it.load(Domain.EXERCISE) > 0 }
        for (wd in 1..7) {
            val date = day(w.index, wd)
            val workouts = DayComposer.compose(input(date)).filter { it.kind == TaskKind.SIEGE && it.domain == Domain.EXERCISE }
            if (date.dayOfWeek.value in plan.intent.workoutDays) {
                assertEquals(1, workouts.size, "$date")
            } else {
                assertTrue(workouts.isEmpty(), "$date")
            }
        }
    }

    @Test
    fun `a short day drops the lowest priorities first`() {
        val w = plan.weeks.last { it.phase == Phase.GOAL && !it.light }
        val date = day(w.index, 2)
        val full = DayComposer.compose(input(date))
        val short = DayComposer.compose(input(date, allowed = 420))
        val cap = PlanRules.capacity(420)
        assertTrue(short.filter { it.kind == TaskKind.SIEGE }.sumOf { it.minutes } <= cap)
        val studyFull = full.count { it.domain == Domain.STUDY && it.kind == TaskKind.SIEGE }
        val studyShort = short.count { it.domain == Domain.STUDY && it.kind == TaskKind.SIEGE }
        assertTrue(short.none { it.domain == Domain.CHORES && it.kind == TaskKind.SIEGE } || studyShort == studyFull)
    }

    @Test
    fun `sieges never exceed the lock limit`() {
        val w = plan.weeks.first { it.phase == Phase.GOAL && it.siegeBlockMinutes >= 40 }
        val specs = DayComposer.compose(input(day(w.index, 2), maxSiege = 30))
        assertTrue(specs.filter { it.kind == TaskKind.SIEGE }.all { it.minutes <= 30 })
    }

    @Test
    fun `topics come weakest first when the history knows them`() {
        val w = plan.weeks.first { it.phase == Phase.GOAL }
        val order = mapOf(Domain.STUDY to listOf("analysis", "linear algebra"))
        val specs = DayComposer.compose(input(day(w.index, 2), order = order))
        val study = specs.filter { it.domain == Domain.STUDY }
        assertTrue(study.isNotEmpty())
        assertTrue(study.all { it.topics.first() == "analysis" })
    }

    @Test
    fun `nothing before the plan starts or on a day with no allowed time`() {
        assertTrue(DayComposer.compose(input(start.minusDays(1))).isEmpty())
        assertTrue(DayComposer.compose(input(start, allowed = 0)).isEmpty())
    }

    @Test
    fun `past the last week the last week holds`() {
        val after = LocalDate.parse(plan.weeks.last().start).plusWeeks(3)
        assertEquals(plan.weeks.last(), plan.weekFor(after))
    }

    @Test
    fun `blocks split evenly and never below the minimum`() {
        assertEquals(listOf(90, 90, 90, 90), DayComposer.blocks(360, 90))
        assertEquals(listOf(35, 35, 35), DayComposer.blocks(105, 35))
        assertEquals(emptyList(), DayComposer.blocks(4, 30))
        assertEquals(listOf(5), DayComposer.blocks(6, 30))
    }
}
