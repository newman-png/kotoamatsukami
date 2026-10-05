package koto.core.time

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WindowsTest {
    // 2026-10-05 is a Monday.
    private val monday = LocalDate.of(2026, 10, 5)
    private fun at(date: LocalDate, h: Int, m: Int = 0) = date.atTime(h, m)

    private fun parse(text: String): SpellConfig {
        val r = SpellConfigParser.parse(text)
        assertIs<ConfigParse.Ok>(r, r.toString())
        return r.config
    }

    @Test
    fun `waking, quiet and protected blocks combine`() {
        val w = parse(
            """
            waking 07:30-23:00
            quiet 22:00-23:00
            protect mon-fri 09:00-13:00 classes
            """.trimIndent(),
        ).windows
        assertFalse(w.allows(at(monday, 7, 0)))
        assertTrue(w.allows(at(monday, 7, 30)))
        assertFalse(w.allows(at(monday, 10)))
        assertTrue(w.allows(at(monday, 13)))
        assertFalse(w.allows(at(monday, 22, 30)))
        assertFalse(w.allows(at(monday, 23, 30)))
        val saturday = monday.plusDays(5)
        assertTrue(w.allows(at(saturday, 10)))
        assertEquals("classes", w.protectedBy(at(monday, 9))?.label)
    }

    @Test
    fun `waking hours can cross midnight`() {
        val w = parse("waking 09:00-01:00").windows
        assertTrue(w.allows(at(monday, 23, 59)))
        assertTrue(w.allows(at(monday.plusDays(1), 0, 30)))
        assertFalse(w.allows(at(monday, 1, 0)))
        assertEquals(monday, w.wakingDateOf(at(monday.plusDays(1), 0, 30)))
        assertEquals(monday, w.wakingDateOf(at(monday, 9)))
        assertNull(w.wakingDateOf(at(monday, 3)))
    }

    @Test
    fun `a protected block crossing midnight belongs to its start day`() {
        val w = parse("waking 09:00-03:00\nprotect fri 23:00-02:00 night shift").windows
        val friday = monday.plusDays(4)
        assertFalse(w.allows(at(friday, 23, 30)))
        assertFalse(w.allows(at(friday.plusDays(1), 1, 0)))
        // Thursday night is not protected.
        assertTrue(w.allows(at(friday.minusDays(1), 23, 30)))
        assertTrue(w.allows(at(friday, 1, 0)))
    }

    @Test
    fun `day lists and ranges parse, including wrap-around`() {
        assertEquals(setOf(DayOfWeek.FRIDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY, DayOfWeek.MONDAY),
            SpellConfigParser.parseDays("fri-mon"))
        assertEquals(setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY), SpellConfigParser.parseDays("mon,wednesday"))
        assertEquals(7, SpellConfigParser.parseDays("daily")?.size)
        assertEquals(5, SpellConfigParser.parseDays("weekdays")?.size)
        assertNull(SpellConfigParser.parseDays("someday"))
        assertNull(SpellConfigParser.parseDays("mo"))
    }

    @Test
    fun `strict parsing reports every bad line`() {
        val r = SpellConfigParser.parse(
            """
            waking 7:30-25:00
            protect mon-fri 09:00 classes
            prtect daily 10:00-11:00
            limit pulse 11
            limit siege 0
            spells 99
            """.trimIndent(),
        )
        assertIs<ConfigParse.Invalid>(r)
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6).sorted(), r.errors.map { it.line }.sorted())
    }

    @Test
    fun `empty or equal ranges are rejected`() {
        assertNull(SpellConfigParser.parseRange("09:00-09:00"))
        assertNull(SpellConfigParser.parseRange("9-17"))
    }

    @Test
    fun `render round-trips`() {
        val config = parse(SpellConfig.TEMPLATE)
        assertEquals(config, parse(SpellConfigParser.render(config)))
    }

    @Test
    fun `template is valid`() {
        val config = parse(SpellConfig.TEMPLATE)
        assertEquals(LocalTime.of(7, 30), config.windows.waking.start)
        assertEquals(1, config.windows.protectedBlocks.size)
    }

    @Test
    fun `layer 2 rules parse, with defaults when absent`() {
        val bare = parse("waking 08:00-22:00")
        assertEquals(0, bare.siegesPerDay)
        assertEquals(koto.core.spell.Distractions.DEFAULT_PACKAGES, bare.distractions)
        assertEquals(koto.core.spell.ReactiveRule(3, 60), bare.reactive)

        val c = parse("waking 08:00-22:00\nspells 5\nsieges 2\ndistract instagram com.example.feed\ndistract youtube\nreactive 4 30")
        assertEquals(2, c.siegesPerDay)
        assertEquals(setOf("com.instagram.android", "com.example.feed", "com.google.android.youtube"), c.distractions)
        assertEquals(koto.core.spell.ReactiveRule(4, 30), c.reactive)
        assertNull(parse("waking 08:00-22:00\nreactive off").reactive)
    }

    @Test
    fun `layer 2 rules reject nonsense`() {
        val r = SpellConfigParser.parse(
            "waking 08:00-22:00\nspells 2\nsieges 3\ndistract instagran\nreactive 1 60\nreactive 3",
        )
        assertIs<ConfigParse.Invalid>(r)
        assertEquals(listOf(0, 4, 5, 6), r.errors.map { it.line }.sorted())
        val tooMany = SpellConfigParser.parse("waking 08:00-22:00\nspells 1\nsieges 2")
        assertIs<ConfigParse.Invalid>(tooMany)
    }

    @Test
    fun `a siege fits only if every minute of it is allowed`() {
        val w = parse("waking 08:00-22:00\nprotect daily 12:00-13:00 lunch").windows
        assertTrue(w.allowsSpan(at(monday, 10), 50))
        assertFalse(w.allowsSpan(at(monday, 11, 30), 50))
        assertTrue(w.allowsSpan(at(monday, 11, 10), 50))
        assertFalse(w.allowsSpan(at(monday, 21, 30), 50))
    }

    @Test
    fun `allowed minutes exclude every blocked minute`() {
        val w = parse("waking 08:00-10:00\nprotect daily 09:00-09:30 x").windows
        val minutes = w.allowedMinutes(monday)
        assertEquals(90, minutes.size)
        assertTrue(minutes.none { it.hour == 9 && it.minute < 30 })
        assertEquals(LocalDateTime.of(2026, 10, 5, 8, 0), minutes.first())
    }
}
