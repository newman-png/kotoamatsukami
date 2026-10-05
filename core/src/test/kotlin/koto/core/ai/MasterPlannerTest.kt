package koto.core.ai

import koto.core.plan.CompactState
import koto.core.plan.Fixtures
import koto.core.plan.PlanIntent
import koto.core.plan.PlanValidator
import koto.core.plan.Signals
import koto.core.spell.Domain
import koto.core.time.ConfigParse
import koto.core.time.SpellConfigParser
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MasterPlannerTest {
    private val json = Json { encodeDefaults = true }
    private val good = json.encodeToString(PlanIntent.serializer(), Fixtures.intent)
    private val ctx = Fixtures.ctx()
    private val state = CompactState.render(Fixtures.profile, null, Fixtures.today, emptyList())

    private fun critic(score: Int, vararg problems: String) =
        """{"problems": [${problems.joinToString { "\"$it\"" }}], "score": $score}"""

    private fun planner(settings: MasterPlanner.Settings = MasterPlanner.Settings(), answer: (LlmRequest, Int) -> String): Pair<MasterPlanner, FakeLlm> {
        val llm = FakeLlm(answer)
        return MasterPlanner(llm, settings) to llm
    }

    @Test
    fun `a good draft becomes a plan that passes every validator`() {
        val (p, llm) = planner { r, _ -> if (r.purpose == Purpose.PLAN) good else critic(8) }
        val result = p.plan(Fixtures.profile, ctx, state)
        assertIs<MasterPlanner.Result.Ok>(result)
        assertEquals(emptyList(), PlanValidator.validate(result.plan, ctx))
        assertEquals(4, llm.count(Purpose.PLAN))
        assertEquals(4, llm.count(Purpose.CRITIC))
        // The critic works in a fresh context: no planner prompt, no drafting history.
        val c = llm.requests.first { it.purpose == Purpose.CRITIC }
        assertEquals(2, c.messages.size)
        assertTrue(c.messages.none { "planner inside Kotoamatsukami" in it.content })
        assertTrue("week 5 (" in c.messages.last().content)
    }

    @Test
    fun `malformed answers are retried with the parse error`() {
        val (p, llm) = planner(MasterPlanner.Settings(drafts = 1)) { r, n ->
            when {
                r.purpose == Purpose.CRITIC -> critic(7)
                n == 0 -> "Sure. Here is the plan you asked for."
                n == 1 -> "{\"goals\": [ {\"domain\": \"STUDY\", } ]}"
                else -> "<think>the user wants exams first</think>\n```json\n$good\n```"
            }
        }
        assertIs<MasterPlanner.Result.Ok>(p.plan(Fixtures.profile, ctx, state))
        assertTrue("no JSON object" in llm.lastUser(Purpose.PLAN, 1))
        assertTrue("not valid JSON" in llm.lastUser(Purpose.PLAN, 2))
    }

    @Test
    fun `schema breaks go back as exact errors`() {
        val bad = good.replace("\"CAREER\"", "\"BUSINESS\"").replace(",\"scouting\":", ",\"notes\":\"x\",\"scouting\":")
        val (p, llm) = planner(MasterPlanner.Settings(drafts = 1)) { r, n -> if (r.purpose == Purpose.CRITIC) critic(7) else if (n == 0) bad else good }
        assertIs<MasterPlanner.Result.Ok>(p.plan(Fixtures.profile, ctx, state))
        val repair = llm.lastUser(Purpose.PLAN, 1)
        assertTrue("'BUSINESS' must be one of" in repair, repair)
        assertTrue("\$.notes is not allowed" in repair, repair)
    }

    @Test
    fun `a deadline id that does not exist is caught by the schema`() {
        val (p, llm) = planner(MasterPlanner.Settings(drafts = 1)) { r, n -> if (r.purpose == Purpose.CRITIC) critic(7) else if (n == 0) good.replace("\"d2\"", "\"d9\"") else good }
        assertIs<MasterPlanner.Result.Ok>(p.plan(Fixtures.profile, ctx, state))
        assertTrue("'d9' must be one of d1, d2" in llm.lastUser(Purpose.PLAN, 1))
    }

    @Test
    fun `a missing field is an error, not a silent default`() {
        val missing = good.substringBefore(",\"scouting\":") + "}"
        val (p, llm) = planner(MasterPlanner.Settings(drafts = 1)) { r, n -> if (r.purpose == Purpose.CRITIC) critic(7) else if (n == 0) missing else good }
        assertIs<MasterPlanner.Result.Ok>(p.plan(Fixtures.profile, ctx, state))
        assertTrue("\$.scouting is missing" in llm.lastUser(Purpose.PLAN, 1))
    }

    @Test
    fun `an invented quote and an unknown deadline are sent back and fixed`() {
        val invented = good.replace("pass all exams in January", "pass the bar exam in March").replace("[\"d1\",\"d2\"]", "[\"d1\"]")
        val (p, llm) = planner(MasterPlanner.Settings(drafts = 1)) { r, n -> if (r.purpose == Purpose.CRITIC) critic(7) else if (n == 0) invented else good }
        val result = p.plan(Fixtures.profile, ctx, state)
        assertIs<MasterPlanner.Result.Ok>(result)
        val repair = llm.lastUser(Purpose.PLAN, 1)
        assertTrue("does not appear in the user's own words" in repair, repair)
        assertTrue("deadline d2 'Analysis exam' on 2027-01-25 is not covered by any goal" in repair, repair)
    }

    @Test
    fun `a model that keeps pushing too fast is rejected`() {
        val fast = json.encodeToString(PlanIntent.serializer(), Fixtures.intent.copy(pace = listOf(koto.core.plan.DomainPace(Domain.STUDY, 3.0))))
        val (p, llm) = planner(MasterPlanner.Settings(drafts = 2, repairs = 2)) { r, _ -> if (r.purpose == Purpose.CRITIC) critic(9) else fast }
        val result = p.plan(Fixtures.profile, ctx, state)
        assertIs<MasterPlanner.Result.Failed>(result)
        assertEquals(6, llm.count(Purpose.PLAN), "two drafts, each with two repairs")
        assertEquals(0, llm.count(Purpose.CRITIC), "nothing failing a validator reaches the critic")
        assertTrue(result.report.any { "rejected" in it && "pace 3.0" in it }, result.report.toString())
    }

    @Test
    fun `an impossible schedule is rejected whatever the model says`() {
        // Waking 06:00-23:30 leaves 6.5 hours for sleep: no intent can fix that.
        val windows = (SpellConfigParser.parse("waking 06:00-23:30") as ConfigParse.Ok).config.windows
        val shortNights = koto.core.plan.PlanContexts.initial(Fixtures.profile, windows, Fixtures.today)
        val (p, llm) = planner(MasterPlanner.Settings(drafts = 1, repairs = 1)) { r, _ -> if (r.purpose == Purpose.CRITIC) critic(10) else good }
        val result = p.plan(Fixtures.profile, shortNights, state)
        assertIs<MasterPlanner.Result.Failed>(result)
        assertTrue("[SLEEP]" in llm.lastUser(Purpose.PLAN, 1))
    }

    @Test
    fun `the critic picks the best of the drafts that pass`() {
        val sunday = good
        val wednesday = json.encodeToString(PlanIntent.serializer(), Fixtures.intent.copy(lightDay = 3, workoutDays = listOf(1, 4, 6)))
        val (p, _) = planner(MasterPlanner.Settings(drafts = 3)) { r, n ->
            if (r.purpose == Purpose.PLAN) {
                if (n == 1) wednesday else sunday
            } else {
                // Critic calls arrive in draft order: drafts 1, 2, 3.
                if (n == 1) critic(9) else critic(6, "study ramps late")
            }
        }
        val result = p.plan(Fixtures.profile, ctx, state)
        assertIs<MasterPlanner.Result.Ok>(result)
        assertEquals(2, result.chosen.draft)
        assertEquals(3, result.plan.intent.lightDay)
    }

    @Test
    fun `an unreachable model throws, so planning waits`() {
        val (p, _) = planner { _, _ -> throw LlmUnavailable("connection refused") }
        assertFailsWith<LlmUnavailable> { p.plan(Fixtures.profile, ctx, state) }
    }

    @Test
    fun `losing the model after a good draft still yields that draft`() {
        val (p, _) = planner { r, n -> if (r.purpose == Purpose.PLAN && n == 0) good else throw LlmUnavailable("laptop asleep") }
        val result = p.plan(Fixtures.profile, ctx, state)
        assertIs<MasterPlanner.Result.Ok>(result)
        assertEquals(1, result.chosen.draft)
    }

    @Test
    fun `a re-plan shows the model the current intent and the signals`() {
        val (p, llm) = planner(MasterPlanner.Settings(drafts = 1)) { r, _ -> if (r.purpose == Purpose.CRITIC) critic(8) else good }
        val replanCtx = ctx.copy(firstWeek = 4, today = "2026-11-02")
        val signals = Signals(dread = true, sleepSlipping = false, loadFactor = 0.85, extraMercy = 2, reasons = listOf("3 'too much' in a row"))
        assertIs<MasterPlanner.Result.Ok>(p.plan(Fixtures.profile, replanCtx, state, Fixtures.intent, signals))
        val user = llm.lastUser(Purpose.PLAN, 0)
        assertTrue("weekly re-plan" in user && "3 'too much' in a row" in user && "\"lightDay\":7" in user, user)
        assertTrue("weeks planned: 4 to" in user)
    }

    @Test
    fun `without the model the re-plan rebuilds from the same intent`() {
        val re = MasterPlanner.codeReplan(ctx.copy(firstWeek = 6, today = "2026-11-16", loadFactor = 0.9, sleepGuard = true), Fixtures.intent)
        assertTrue(re != null && re.weeks.first().index == 6 && re.weeks.first().sleepGuard)
    }
}
