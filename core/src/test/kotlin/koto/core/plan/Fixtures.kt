package koto.core.plan

import koto.core.spell.Domain
import koto.core.time.ConfigParse
import koto.core.time.SpellConfigParser
import koto.core.time.Windows
import java.time.LocalDate

/** Subject 0 as the brief describes him, for plan tests. */
object Fixtures {
    val today: LocalDate = LocalDate.of(2026, 10, 12) // a Monday

    const val GOALS = "Get my life in order. Pass all exams in January. Learn Italian. Start a business or get a job."

    val deadlines = listOf(
        Deadline("d1", "2027-01-18", "Linear algebra exam"),
        Deadline("d2", "2027-01-25", "Analysis exam"),
    )

    val baseline = Baseline(
        sleepHours = 6.5, bedtime = "01:00", wakeTime = "08:00",
        workoutsPerWeek = 0, dailySteps = 4000, studyHoursPerDay = 1.5,
        screenHoursPerDay = 6.0, dietQuality = 2, choresMinutesPerDay = 10,
        energy = 5, italianMinutesPerDay = 0, careerHoursPerWeek = 1.0,
    )

    val profile = Profile(
        goalsText = GOALS,
        deadlines = deadlines,
        baseline = baseline,
        priorities = listOf(Domain.STUDY, Domain.SLEEP, Domain.EXERCISE, Domain.ITALIAN, Domain.CAREER, Domain.STEPS, Domain.CHORES, Domain.NUTRITION),
        createdOn = today.toString(),
    )

    val windows: Windows = (
        SpellConfigParser.parse("waking 07:30-23:00\nprotect mon-fri 09:00-13:00 classes") as ConfigParse.Ok
        ).config.windows

    val intent = PlanIntent(
        goals = listOf(
            GoalIntent(Domain.STUDY, "Pass the January exams", "pass all exams in January", listOf("d1", "d2"), listOf("linear algebra", "analysis")),
            GoalIntent(Domain.ITALIAN, "Learn Italian", "learn Italian", topics = listOf("everyday vocabulary", "present tense")),
            GoalIntent(Domain.CAREER, "Start a business or get a job", "start a business or get a job", topics = listOf("CV", "applications")),
        ),
        pace = LOAD_DOMAINS.map { DomainPace(it, 1.0) },
        goalStartWeek = 5,
        lightDay = 7,
        workoutDays = listOf(1, 3, 5),
        scouting = listOf(ScoutTask(Domain.STUDY, "Open the linear algebra notes.", "Read one page.", "pass all exams")),
    )

    fun ctx(): PlanContext = PlanContexts.initial(profile, windows, today)

    fun plan(intent: PlanIntent = this.intent, ctx: PlanContext = ctx()): MasterPlan = PlanBuilder.build(ctx, intent)
}
