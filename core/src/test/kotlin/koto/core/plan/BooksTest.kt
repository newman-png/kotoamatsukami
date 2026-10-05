package koto.core.plan

import koto.core.spell.Domain
import koto.core.spell.TaskKind
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BooksTest {
    private val study = SlotSpec(TaskKind.SIEGE, Domain.STUDY, SlotRole.WORK, minutes = 90, topics = listOf("linear algebra", "analysis"))
    private val micro = SlotSpec(TaskKind.PULSE, Domain.STUDY, SlotRole.MICRO, maxSeconds = 60, topics = listOf("linear algebra", "analysis"))
    private val italian = SlotSpec(TaskKind.PULSE, Domain.ITALIAN, SlotRole.MICRO, maxSeconds = 60, topics = listOf("everyday vocabulary", "present tense"))

    @Test
    fun `every template passes the voice checks`() {
        val pools = Templates.NUTRITION + Templates.MERCY + Templates.BODY + Templates.FOCUS + Templates.EXERCISE + Templates.CHORES
        for ((n, f) in pools) {
            for (v in listOf(n, f)) {
                assertNull(Voice.commandProblem(v.command), v.command)
                assertNull(Voice.detailProblem(v.detail), v.detail)
                assertTrue(v.seconds in 1..60)
            }
            assertTrue(f.seconds <= n.seconds)
        }
        for ((word, meaning) in Templates.WORDS) {
            assertNull(Voice.commandProblem("Translate: $word."))
            assertNull(Voice.detailProblem("$word: $meaning.", "reveal"))
        }
        for ((verb, forms) in Templates.VERBS) {
            assertNull(Voice.commandProblem("Conjugate $verb."))
            assertNull(Voice.detailProblem("$forms.", "reveal"))
            assertEquals(6, forms.split(", ").size, verb)
        }
    }

    @Test
    fun `generated days read in the voice and sieges say their minutes`() {
        val plan = Fixtures.plan()
        val start = java.time.LocalDate.parse(plan.start)
        for (d in 0L until plan.weeks.size * 7L step 3) {
            val date = start.plusDays(d)
            val specs = DayComposer.compose(DayInput(plan, date, 690, 118, Fixtures.profile.priorities))
            val book = Books.fallback(date.toString(), plan.weekIndexOf(date), specs, Random(d))
            assertEquals(book.tasks.size, book.tasks.map { it.id }.toSet().size)
            for (t in book.tasks) {
                assertNull(Voice.commandProblem(t.normal.command), t.normal.command)
                assertNull(Voice.commandProblem(t.floor.command), t.floor.command)
                assertNull(Voice.detailProblem(t.normal.detail), t.normal.detail)
                assertTrue(t.floor.seconds <= t.normal.seconds, "${t.normal.command}: floor longer than normal")
                if (t.kind == TaskKind.SIEGE) {
                    assertTrue("${t.normal.seconds / 60} minutes" in t.normal.command, t.normal.command)
                    assertTrue("${t.floor.seconds / 60} minutes" in t.floor.command, t.floor.command)
                } else {
                    assertTrue(t.normal.seconds in 6..60, "${t.normal.command} ${t.normal.seconds}")
                }
            }
        }
    }

    @Test
    fun `a siege takes only its topic from the writer`() {
        val w = WrittenTask(0, topic = "analysis", command = "Something else entirely.")
        assertNull(Books.problem(study, w))
        val book = Books.assemble("2026-12-01", 8, listOf(study), mapOf(0 to w), Random(1))
        val t = book.tasks.single()
        assertEquals("Analysis. 90 minutes. Go.", t.normal.command)
        assertEquals("Analysis. 30 minutes. Go.", t.floor.command)
        assertEquals("analysis", t.topic)
        assertEquals(Books.WRITER_AI, book.writer)
    }

    @Test
    fun `good micro text is used as written`() {
        val w = WrittenTask(0, topic = "linear algebra", command = "Define the rank of a matrix.", detail = "One sentence.", reveal = "the number of independent rows.")
        val t = Books.assemble("2026-12-01", 8, listOf(micro), mapOf(0 to w), Random(1)).tasks.single()
        assertEquals("Define the rank of a matrix.", t.normal.command)
        assertEquals("the number of independent rows.", t.reveal)
        assertEquals("linear algebra", t.topic)
        assertTrue(t.normal.seconds <= 60)
    }

    @Test
    fun `bad writer output falls back to templates, slot by slot`() {
        val bad = mapOf(
            0 to WrittenTask(0, topic = "linear algebra", command = "You got this! Solve one problem"),
            1 to WrittenTask(1, topic = "topology", command = "One topology problem."),
            2 to WrittenTask(2, topic = "everyday vocabulary", command = "Translate: gatto.", reveal = "gatto: cat."),
        )
        val specs = listOf(micro, micro, italian)
        assertNotNull(Books.problem(micro, bad.getValue(0)))
        assertTrue(Books.problem(micro, bad.getValue(1))!!.contains("not one of"))
        val book = Books.assemble("2026-12-01", 8, specs, bad, Random(1))
        assertTrue(book.tasks[0].normal.command.startsWith("One "), book.tasks[0].normal.command)
        assertTrue(book.tasks[1].topic != "topology")
        assertEquals("Translate: gatto.", book.tasks[2].normal.command)
        assertEquals(Books.WRITER_AI, book.writer)
    }

    @Test
    fun `a pulse may not carry minutes, a siege topic must be allowed`() {
        assertNotNull(Books.problem(micro, WrittenTask(0, topic = "analysis", command = "Analysis. 20 minutes.")))
        assertNotNull(Books.problem(study, WrittenTask(0, topic = "chemistry")))
    }

    @Test
    fun `nothing from the writer means a code book`() {
        val book = Books.fallback("2026-12-01", 8, listOf(study, micro), Random(2))
        assertEquals(Books.WRITER_CODE, book.writer)
        assertEquals("Linear algebra. 90 minutes. Go.", book.tasks[0].normal.command)
    }

    @Test
    fun `a siege can be cut short and still says its minutes`() {
        val t = Books.fallback("2026-12-01", 8, listOf(study), Random(2)).tasks.single()
        val cut = Books.shortened(t, 40)
        assertEquals("Linear algebra. 40 minutes. Go.", cut.normal.command)
        assertEquals(2400, cut.normal.seconds)
        assertEquals(t.floor, cut.floor, "the 30-minute floor already fits")
        assertEquals("Linear algebra. 20 minutes. Go.", Books.shortened(t, 20).floor.command)
    }
}
