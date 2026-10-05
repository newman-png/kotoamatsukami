package koto.core.ai

import koto.core.plan.Books
import koto.core.plan.SlotRole
import koto.core.plan.SlotSpec
import koto.core.spell.Domain
import koto.core.spell.TaskKind
import java.time.LocalDate
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TaskWriterTest {
    private val date = LocalDate.of(2026, 12, 1)
    private val topics = listOf("analysis", "linear algebra")
    private val specs = listOf(
        SlotSpec(TaskKind.SIEGE, Domain.STUDY, SlotRole.WORK, minutes = 60, topics = topics),
        SlotSpec(TaskKind.PULSE, Domain.STUDY, SlotRole.MICRO, maxSeconds = 60, topics = topics),
        SlotSpec(TaskKind.PULSE, Domain.ITALIAN, SlotRole.MICRO, maxSeconds = 60),
        SlotSpec(TaskKind.PULSE, Domain.NUTRITION, SlotRole.NUTRITION, maxSeconds = 60),
    )

    private fun entry(slot: Int, topic: String = "", command: String = "", detail: String = "", reveal: String = "") =
        """{"slot": $slot, "topic": "$topic", "command": "$command", "detail": "$detail", "reveal": "$reveal"}"""

    private fun answer(vararg entries: String) = """{"tasks": [${entries.joinToString()}]}"""

    private val allGood = answer(
        entry(0, "linear algebra"),
        entry(1, "analysis", "State the mean value theorem.", "Out loud.", "f'(c) equals the slope between the endpoints."),
        entry(2, "", "Translate: finestra.", "Say it out loud.", "finestra: window."),
    )

    private fun write(llm: Llm) = TaskWriter(llm).write(date, 8, specs, "STATE", listOf("9 takeovers."), Random(1))

    @Test
    fun `good text is used and only goal slots go to the model`() {
        val llm = FakeLlm { _, _ -> allGood }
        val book = write(llm)
        assertEquals(1, llm.count(Purpose.WRITE))
        assertEquals(Books.WRITER_AI, book.writer)
        assertEquals("Linear algebra. 60 minutes. Go.", book.tasks[0].normal.command)
        assertEquals("State the mean value theorem.", book.tasks[1].normal.command)
        assertEquals("finestra: window.", book.tasks[2].reveal)
        val prompt = llm.lastUser(Purpose.WRITE, 0)
        assertTrue("slot 0: siege, 60 minutes, study" in prompt && "slot 3" !in prompt, prompt)
    }

    @Test
    fun `a bad slot is sent back alone, then fixed`() {
        val first = answer(entry(0, "analysis"), entry(1, "analysis", "Great job! One problem."), entry(2, "", "Translate: finestra.", "", "finestra: window."))
        val second = answer(entry(1, "analysis", "Define a Cauchy sequence.", "", ""))
        val llm = FakeLlm { _, n -> if (n == 0) first else second }
        val book = write(llm)
        assertEquals(2, llm.count(Purpose.WRITE))
        val repair = llm.lastUser(Purpose.WRITE, 1)
        assertTrue("slot 1" in repair && "exclamation" in repair && "only for these slots: 1" in repair, repair)
        assertEquals("Define a Cauchy sequence.", book.tasks[1].normal.command)
        assertEquals("Translate: finestra.", book.tasks[2].normal.command)
    }

    @Test
    fun `a slot that stays bad falls back to a template, the others keep the model's text`() {
        val bad = answer(entry(0, "topology"), entry(1, "analysis", "Study analysis for 20 minutes."), entry(2, "", "Translate: finestra.", "", "finestra: window."))
        val llm = FakeLlm { _, _ -> bad }
        val book = write(llm)
        assertEquals(TaskWriter.ROUNDS, llm.count(Purpose.WRITE))
        assertEquals("Analysis. 60 minutes. Go.", book.tasks[0].normal.command, "template uses the weakest topic")
        assertEquals("One analysis problem.", book.tasks[1].normal.command)
        assertEquals("Translate: finestra.", book.tasks[2].normal.command)
    }

    @Test
    fun `garbage twice means a code book`() {
        val llm = FakeLlm { _, _ -> "I can't help with that." }
        val book = write(llm)
        assertEquals(Books.WRITER_CODE, book.writer)
        assertEquals(4, book.tasks.size)
    }

    @Test
    fun `the same command twice in a day is refused`() {
        val dup = answer(entry(0, "analysis"), entry(1, "analysis", "Translate: finestra.", "", "finestra: window."), entry(2, "", "Translate: finestra.", "", "finestra: window."))
        val llm = FakeLlm { _, _ -> dup }
        val book = write(llm)
        assertTrue("repeats the command" in llm.lastUser(Purpose.WRITE, 1))
        assertTrue(book.tasks[1].normal.command != book.tasks[2].normal.command)
    }

    @Test
    fun `an unreachable model throws`() {
        assertFailsWith<LlmUnavailable> { write { throw LlmUnavailable("no route to host") } }
    }

    @Test
    fun `a day with nothing for the model never calls it`() {
        val llm = FakeLlm { _, _ -> error("should not be called") }
        val book = TaskWriter(llm).write(date, 1, listOf(specs[3]), "STATE", emptyList(), Random(1))
        assertEquals(Books.WRITER_CODE, book.writer)
    }
}
