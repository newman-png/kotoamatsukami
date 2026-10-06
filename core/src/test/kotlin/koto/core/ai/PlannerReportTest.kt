package koto.core.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlannerReportTest {
    @Test
    fun `an error keeps its type, message, place and cause`() {
        val e = IllegalStateException("outer", java.io.IOException("Connection refused"))
        val text = PlannerReport.describe(e)
        assertTrue(text.startsWith("IllegalStateException: outer (PlannerReportTest.kt:"), text)
        assertTrue(text.endsWith(", caused by IOException: Connection refused (PlannerReportTest.kt:${e.cause!!.stackTrace.first { it.className.startsWith("koto.") }.lineNumber})"), text)
    }

    @Test
    fun `rejected drafts show rule names, never numbers`() {
        val line = "draft 2: rejected: [RAMP] week 6 STUDY: rises from 90 to 130 min/day | [DELOAD] week 9: no light week"
        assertEquals("draft 2: rejected (RAMP, DELOAD)", PlannerReport.visible(line))
        assertEquals("draft 1: rejected (intent checks)", PlannerReport.visible("draft 1: rejected: goal 'x': quote 'y' does not appear"))
        assertEquals("draft 3: passed every check after 1 repairs", PlannerReport.visible("draft 3: passed every check after 1 repairs"))
    }

    @Test
    fun `short keeps one line`() {
        assertEquals("a", PlannerReport.short("a\nb"))
        assertEquals(PlannerReport.SHORT, PlannerReport.short("x".repeat(500)).length)
    }
}
