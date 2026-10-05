package koto.core.plan

import koto.core.spell.Domain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The model's reading of the goals must stay inside what the user actually said. */
class IntentValidatorTest {
    private val profile = Fixtures.profile
    private val today = Fixtures.today
    private val good = Fixtures.intent

    private fun errors(i: PlanIntent) = IntentValidator.validate(i, profile, today)

    @Test
    fun `the good intent passes`() {
        assertEquals(emptyList(), errors(good))
    }

    @Test
    fun `invented goals are caught by their quotes`() {
        val invented = good.copy(goals = good.goals + GoalIntent(Domain.EXERCISE, "Run a marathon", "run a marathon in spring"))
        assertTrue(errors(invented).any { "does not appear" in it })
        val partialWord = good.copy(goals = good.goals + GoalIntent(Domain.CHORES, "Tidy", "ass"))
        assertTrue(errors(partialWord).isNotEmpty())
        // Quoting is checked on words, not punctuation or case.
        val reformatted = good.copy(goals = listOf(good.goals[0].copy(quote = "PASS ALL EXAMS, in january")) + good.goals.drop(1))
        assertEquals(emptyList(), errors(reformatted))
    }

    @Test
    fun `unknown and uncovered deadlines are caught`() {
        val unknown = good.copy(goals = listOf(good.goals[0].copy(deadlineIds = listOf("d1", "d2", "d5"))) + good.goals.drop(1))
        assertTrue(errors(unknown).any { "unknown deadline 'd5'" in it })
        val uncovered = good.copy(goals = listOf(good.goals[0].copy(deadlineIds = listOf("d1"))) + good.goals.drop(1))
        assertTrue(errors(uncovered).any { "d2" in it && "not covered" in it })
    }

    @Test
    fun `steering values out of range are caught`() {
        assertTrue(errors(good.copy(pace = listOf(DomainPace(Domain.STUDY, 2.0)))).isNotEmpty())
        assertTrue(errors(good.copy(pace = listOf(DomainPace(Domain.MERCY, 1.0)))).isNotEmpty())
        assertTrue(errors(good.copy(goalStartWeek = 9)).isNotEmpty())
        assertTrue(errors(good.copy(goalStartWeek = 1)).isNotEmpty())
        assertTrue(errors(good.copy(lightDay = 0)).isNotEmpty())
        assertTrue(errors(good.copy(workoutDays = listOf(1, 1, 3))).isNotEmpty())
    }

    @Test
    fun `text out of voice is caught`() {
        fun scout(cmd: String, detail: String = "") =
            good.copy(scouting = listOf(ScoutTask(Domain.STUDY, cmd, detail, "pass all exams")))
        assertTrue(errors(scout("Open your notes!")).isNotEmpty())
        assertTrue(errors(scout("Open your notes")).isNotEmpty(), "must end with a period")
        assertTrue(errors(scout("Open your notes.", "Great job, you got this.")).isNotEmpty())
        assertTrue(errors(scout("Open your notes 🔥.")).isNotEmpty(), "emoji")
        assertTrue(errors(scout("Open the notes for every single subject you have this term.")).isNotEmpty(), "too long")
        assertEquals(emptyList(), errors(scout("Ripassa: andare, venire.")), "Italian accents and colons are fine")
    }

    @Test
    fun `scouting must belong to a real goal and quote the user`() {
        val orphan = good.copy(scouting = listOf(ScoutTask(Domain.EXERCISE, "Ten squats.", "", "get my life in order")))
        assertTrue(errors(orphan).any { "no goal" in it })
        val ungrounded = good.copy(scouting = listOf(ScoutTask(Domain.STUDY, "Open the notes.", "", "physics olympiad")))
        assertTrue(errors(ungrounded).any { "does not appear" in it })
    }
}
