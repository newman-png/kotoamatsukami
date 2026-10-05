package koto.core.plan

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class DeadlineTextTest {
    private val today = LocalDate.of(2026, 10, 12)

    @Test
    fun `good lines become numbered deadlines`() {
        val r = DeadlineText.parse("# exams\n2027-01-18 Linear algebra exam\n\n2027-01-25   Analysis exam\n", today)
        assertIs<DeadlineText.Parsed.Ok>(r)
        assertEquals(listOf(Deadline("d1", "2027-01-18", "Linear algebra exam"), Deadline("d2", "2027-01-25", "Analysis exam")), r.deadlines)
        assertEquals("2027-01-18 Linear algebra exam\n2027-01-25 Analysis exam", DeadlineText.render(r.deadlines))
    }

    @Test
    fun `every bad line is named`() {
        val r = DeadlineText.parse("18/01/2027 exam\n2026-10-01 old exam\n2027-02-01\n", today)
        assertIs<DeadlineText.Parsed.Invalid>(r)
        assertEquals(3, r.errors.size)
        assertEquals("line 2: 2026-10-01 has passed", r.errors[1])
    }
}
