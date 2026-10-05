package koto.app.ui

import android.content.Intent
import android.os.Bundle
import android.widget.EditText
import koto.app.ai.PlannerRunner
import koto.app.data.PlanStore
import koto.app.data.Store
import koto.core.ai.PlannerSettings
import koto.core.plan.Baseline
import koto.core.plan.DeadlineText
import koto.core.plan.PlanContexts
import koto.core.plan.Profile
import koto.core.plan.SetupChecks
import koto.core.spell.Domain
import java.time.LocalDate
import java.util.Locale

/**
 * The one-time setup, as a short linear conversation: what he wants, the hard dates, where he is
 * now (sliders), which goal wins, where the laptop is, and the windows. Answers become a typed
 * [Profile]; the planner may use nothing else. Consent comes last and arms.
 */
class SetupActivity : KotoActivity() {
    private var step = 0
    private var goals = ""
    private var deadlines = ""
    private val sliders = IntArray(SLIDERS.size) { SLIDERS[it].start }
    private val ranked = ArrayList<Domain>()
    private var laptop: PlannerSettings? = null

    private var goalsInput: EditText? = null
    private var deadlineInput: EditText? = null
    private var laptopForm: LaptopForm? = null
    private var error: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = PlanStore(this)
        if (savedInstanceState != null) {
            step = savedInstanceState.getInt("step")
            goals = savedInstanceState.getString("goals").orEmpty()
            deadlines = savedInstanceState.getString("deadlines").orEmpty()
            savedInstanceState.getIntArray("sliders")?.copyInto(sliders)
            savedInstanceState.getStringArray("ranked")?.forEach { ranked += Domain.valueOf(it) }
            laptop = savedInstanceState.getString("host")?.let {
                PlannerSettings(it, savedInstanceState.getInt("port"), savedInstanceState.getString("model").orEmpty(), savedInstanceState.getString("night").orEmpty())
            }
        } else {
            // A repeated setup starts from the last answers.
            store.profile()?.let { p ->
                goals = p.goalsText
                deadlines = DeadlineText.render(p.deadlines)
                SLIDERS.forEachIndexed { i, s -> sliders[i] = s.positionOf(p.baseline) }
                ranked += p.priorities.filter { it in RANKED }
            }
            laptop = store.settings()
        }
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        collect()
        outState.putInt("step", step)
        outState.putString("goals", goals)
        outState.putString("deadlines", deadlines)
        outState.putIntArray("sliders", sliders)
        outState.putStringArray("ranked", ranked.map { it.name }.toTypedArray())
        laptop?.let {
            outState.putString("host", it.host)
            outState.putInt("port", it.port)
            outState.putString("model", it.model)
            outState.putString("night", it.nightModel)
        }
    }

    override fun onResume() {
        super.onResume()
        // Back from the windows editor.
        if (step == STEP_WINDOWS) render()
    }

    private fun collect() {
        goalsInput?.let { goals = it.text.toString() }
        deadlineInput?.let { deadlines = it.text.toString() }
        laptopForm?.read()?.let { laptop = it }
    }

    private fun render() {
        goalsInput = null
        deadlineInput = null
        laptopForm = null
        val term = Term(this)
        term.title("setup ${step + 1}/${STEPS}")
        term.gap(0.5f)
        when (step) {
            0 -> {
                term.line("What do you want?", Pixel.WHITE, term.medium)
                term.line("In your own words. The plan may use only what you write here and what you do.", Pixel.GREY)
                goalsInput = term.input(goals).apply {
                    hint = "Pass all exams in January. Learn Italian."
                    setHintTextColor(Pixel.GREY)
                    minLines = 5
                }
            }
            1 -> {
                term.line("Deadlines.", Pixel.WHITE, term.medium)
                term.line("Hard dates, like exams. One per line: the date, then what it is. Leave empty if there are none.", Pixel.GREY)
                deadlineInput = term.input(deadlines).apply {
                    hint = "2027-01-18 Linear algebra exam"
                    setHintTextColor(Pixel.GREY)
                    minLines = 4
                }
            }
            2 -> {
                term.line("Where you are now.", Pixel.WHITE, term.medium)
                term.line("Honest numbers. The plan starts from these, not from where you want to be.", Pixel.GREY)
                SLIDERS.forEachIndexed { i, s ->
                    term.gap(0.3f)
                    term.slider(s.positions, sliders[i], { p -> "${s.label}: ${s.format(p)}" }) { sliders[i] = it }
                }
            }
            3 -> {
                term.line("When goals compete for time, which wins?", Pixel.WHITE, term.medium)
                term.line("Tap them in order, most important first.", Pixel.GREY)
                term.gap(0.5f)
                ranked.forEachIndexed { i, d -> term.line("${i + 1}. ${NAMES.getValue(d)}") }
                for (d in RANKED.filter { it !in ranked }) {
                    term.command(NAMES.getValue(d), Pixel.GREY, term.small) {
                        ranked += d
                        render()
                    }
                }
                if (ranked.isNotEmpty()) term.command("start over", Pixel.GREY, term.small) {
                    ranked.clear()
                    render()
                }
            }
            4 -> {
                term.line("The laptop.", Pixel.WHITE, term.medium)
                term.line("Plans are made by Ollama on your laptop. Nothing leaves your home network.", Pixel.GREY)
                term.gap(0.5f)
                laptopForm = LaptopForm(this, term, laptop)
            }
            STEP_WINDOWS -> {
                term.line("When takeovers may happen.", Pixel.WHITE, term.medium)
                val config = Store(this).config()
                if (config == null) {
                    term.line("Not set yet.", Pixel.GREY)
                } else {
                    term.line("waking ${config.windows.waking}", Pixel.GREY)
                    config.windows.quiet.forEach { term.line("quiet $it", Pixel.GREY) }
                    config.windows.protectedBlocks.forEach { term.line("protected ${it.range} ${it.label}", Pixel.GREY) }
                    profileOrNull()?.let { p ->
                        SetupChecks.problems(PlanContexts.initial(p, config.windows, LocalDate.now())).forEach { term.line(it) }
                    }
                }
                term.command("edit windows", size = term.small) { startActivity(Intent(this, WindowsActivity::class.java)) }
            }
        }
        error?.let { term.line(it) }
        error = null
        term.gap()
        val last = step == STEPS - 1
        term.command(if (last) "done" else "next") { next() }
        if (step > 0) term.command("back", Pixel.GREY) {
            collect()
            step--
            render()
        } else {
            term.command("not now", Pixel.GREY) { finish() }
        }
        term.show()
    }

    private fun next() {
        collect()
        error = when (step) {
            0 -> if (goals.trim().split(Regex("\\s+")).count { it.length >= 3 } < 2) "Write a little more." else null
            1 -> (DeadlineText.parse(deadlines, LocalDate.now()) as? DeadlineText.Parsed.Invalid)?.errors?.joinToString("\n")
            3 -> if (ranked.size < RANKED.size) "Rank all of them." else null
            4 -> if (laptopForm?.read() == null) "Fix the laptop details first." else null
            STEP_WINDOWS -> {
                val config = Store(this).config()
                val p = profileOrNull()
                when {
                    config == null -> "Set the windows first."
                    p != null && SetupChecks.problems(PlanContexts.initial(p, config.windows, LocalDate.now())).isNotEmpty() -> "Fix the windows first."
                    else -> null
                }
            }
            else -> null
        }
        if (error != null) {
            render()
            return
        }
        if (step < STEPS - 1) {
            step++
            render()
            return
        }
        finishSetup()
    }

    private fun profileOrNull(): Profile? {
        val parsed = DeadlineText.parse(deadlines, LocalDate.now()) as? DeadlineText.Parsed.Ok ?: return null
        val values = SLIDERS.mapIndexed { i, s -> s.value(sliders[i]) }
        return Profile(
            goalsText = goals.trim(),
            deadlines = parsed.deadlines,
            baseline = Baseline(
                sleepHours = values[0],
                bedtime = clock(values[1]),
                wakeTime = clock(values[2]),
                workoutsPerWeek = values[3].toInt(),
                dailySteps = values[4].toInt(),
                studyHoursPerDay = values[5],
                screenHoursPerDay = values[6],
                dietQuality = values[7].toInt(),
                choresMinutesPerDay = values[8].toInt(),
                energy = values[9].toInt(),
                italianMinutesPerDay = values[10].toInt(),
                careerHoursPerWeek = values[11],
            ),
            priorities = ranked.toList(),
            createdOn = LocalDate.now().toString(),
        )
    }

    private fun finishSetup() {
        val profile = profileOrNull() ?: return
        val settings = laptop ?: return
        val store = PlanStore(this)
        val old = store.profile()
        val changed = old == null || old.copy(createdOn = "") != profile.copy(createdOn = "")
        if (changed) {
            // New answers, new plan. Until it is ready, takeovers stay easy pulses.
            store.clearPlan()
            store.saveProfile(profile)
        }
        store.saveSettings(settings)
        PlannerRunner.schedule(this)
        PlannerRunner.kick(this)
        if (!Store(this).consented) startActivity(Intent(this, ConsentActivity::class.java))
        finish()
    }

    /** One slider: positions 0..[positions], each worth [step] above [min]. */
    private class Slider(
        val label: String,
        val min: Double,
        val step: Double,
        val positions: Int,
        val start: Int,
        val format: (Int) -> String,
        val read: (Baseline) -> Double,
    ) {
        fun value(p: Int): Double = min + p * step
        fun positionOf(b: Baseline): Int = Math.round((read(b) - min) / step).toInt().coerceIn(0, positions)
    }

    private companion object {
        const val STEPS = 6
        const val STEP_WINDOWS = 5

        fun num(v: Double): String = if (v == Math.floor(v)) v.toInt().toString() else String.format(Locale.ROOT, "%.1f", v)

        /** Minutes after 18:00 for bedtime, wrapped to a clock. */
        fun clock(minutes: Double): String {
            val m = minutes.toInt().mod(24 * 60)
            return "%02d:%02d".format(Locale.ROOT, m / 60, m % 60)
        }

        fun minutesOf(clock: String): Double {
            val (h, m) = clock.split(':').map { it.toInt() }
            return (h * 60 + m).toDouble()
        }

        fun slider(label: String, min: Double, step: Double, max: Double, start: Double, unit: String, read: (Baseline) -> Double): Slider {
            val positions = Math.round((max - min) / step).toInt()
            return Slider(label, min, step, positions, Math.round((start - min) / step).toInt(), { p -> num(min + p * step) + unit }, read)
        }

        fun time(label: String, from: Int, to: Int, start: Int, read: (Baseline) -> String): Slider {
            // Positions in 15-minute steps from [from] (minutes after midnight), wrapping past midnight.
            val span = (to - from).mod(24 * 60)
            return Slider(
                label, from.toDouble(), 15.0, span / 15, (start - from).mod(24 * 60) / 15,
                { p -> clock(from + p * 15.0) },
                { b -> from + (minutesOf(read(b)) - from).mod(24.0 * 60) },
            )
        }

        val SLIDERS = listOf(
            slider("sleep a night", 3.0, 0.5, 12.0, 7.0, " h") { it.sleepHours },
            time("asleep by", 20 * 60, 5 * 60, 0) { it.bedtime },
            time("up at", 4 * 60, 13 * 60, 8 * 60) { it.wakeTime },
            slider("workouts a week", 0.0, 1.0, 7.0, 0.0, "") { it.workoutsPerWeek.toDouble() },
            slider("steps a day", 0.0, 500.0, 20_000.0, 4_000.0, "") { it.dailySteps.toDouble() },
            slider("study a day", 0.0, 0.5, 12.0, 1.0, " h") { it.studyHoursPerDay },
            slider("screen time a day", 0.0, 0.5, 16.0, 5.0, " h") { it.screenHoursPerDay },
            slider("how well you eat, 1-5", 1.0, 1.0, 5.0, 3.0, "") { it.dietQuality.toDouble() },
            slider("cleaning a day", 0.0, 5.0, 120.0, 10.0, " min") { it.choresMinutesPerDay.toDouble() },
            slider("energy, 1-10", 1.0, 1.0, 10.0, 5.0, "") { it.energy.toDouble() },
            slider("Italian a day", 0.0, 5.0, 120.0, 0.0, " min") { it.italianMinutesPerDay.toDouble() },
            slider("business or job search a week", 0.0, 1.0, 60.0, 0.0, " h") { it.careerHoursPerWeek },
        )

        /** What he ranks. Sleep is always protected by the validators, whatever its rank. */
        val RANKED = listOf(Domain.STUDY, Domain.SLEEP, Domain.EXERCISE, Domain.STEPS, Domain.ITALIAN, Domain.CAREER, Domain.CHORES, Domain.NUTRITION)

        val NAMES = mapOf(
            Domain.STUDY to "study and exams",
            Domain.SLEEP to "sleep",
            Domain.EXERCISE to "workouts",
            Domain.STEPS to "walking",
            Domain.ITALIAN to "Italian",
            Domain.CAREER to "business or job",
            Domain.CHORES to "cleaning",
            Domain.NUTRITION to "eating well",
        )
    }
}
