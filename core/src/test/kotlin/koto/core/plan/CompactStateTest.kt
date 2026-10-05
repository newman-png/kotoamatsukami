package koto.core.plan

import koto.core.spell.Domain
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CompactStateTest {
    private val plan = Fixtures.plan()

    private fun summary(week: Int) = WeekSummary(
        week, LocalDate.parse(plan.start).plusWeeks((week - 1).toLong()).toString(), plan.week(week)!!.phase, plan.week(week)!!.light,
        40, 30, 5, 3, 1, 2, 9.0 + week, 10, 12, 3, 2,
        LOAD_DOMAINS.map { DomainWeek(it, plan.week(week)!!.load(it), 100, 70) },
        listOf(TopicStat(Domain.STUDY, "analysis", 5, 120, 1, 1, 0)), 1, false, false,
    )

    @Test
    fun `the document carries the profile, the plan position and the weeks, compressed`() {
        val summaries = (1..12).map { summary(it) }
        val text = CompactState.render(Fixtures.profile, plan, LocalDate.parse(plan.start).plusWeeks(12).plusDays(2), summaries)
        assertTrue(Fixtures.GOALS in text)
        assertTrue("d1 2027-01-18 Linear algebra exam" in text)
        assertTrue("priorities, highest first: study > sleep > exercise" in text)
        assertTrue("week 13 of" in text)
        assertTrue("LAST WEEK" in text && "week 12 (" in text)
        assertTrue("topic study 'analysis'" in text)
        // Older weeks: one line each, and only the most recent few.
        val older = text.substringAfter("OLDER WEEKS").lines().filter { it.startsWith("week ") }
        assertTrue(older.size == CompactState.OLDER_WEEKS, older.toString())
        assertFalse("week 1 (" in text)
        assertTrue(text.length < 4000, "compact state is ${text.length} characters")
    }

    @Test
    fun `before any plan or logs it is just the profile`() {
        val text = CompactState.render(Fixtures.profile, null, Fixtures.today, emptyList())
        assertTrue(text.startsWith("PROFILE"))
        assertFalse("PLAN" in text)
        assertFalse("LAST WEEK" in text)
    }

    @Test
    fun `slot lines list only what the writer words`() {
        val specs = DayComposer.compose(DayInput(plan, LocalDate.parse(plan.week(8)!!.start).plusDays(1), 690, 118, Fixtures.profile.priorities))
        val lines = CompactState.slotLines(specs).lines().filter { it.isNotBlank() }
        assertTrue(lines.isNotEmpty())
        assertTrue(lines.all { it.startsWith("slot ") })
        assertTrue(lines.size == specs.count { Books.aiWritten(it) })
    }
}
