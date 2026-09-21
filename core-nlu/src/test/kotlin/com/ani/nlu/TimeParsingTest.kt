package com.ani.nlu

import com.ani.nlu.text.TextNormalizer
import com.ani.nlu.time.PartOfDay
import com.ani.nlu.time.TeluguTimeParser
import com.ani.nlu.time.TimeKind
import com.ani.nlu.time.TimeSpec
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TimeParsingTest {

    private fun parse(text: String): TimeSpec? = TeluguTimeParser.parse(TextNormalizer.normalize(text))

    /** Monday 21 September 2026, 10:00. */
    private val now: LocalDateTime = LocalDateTime.of(2026, 9, 21, 10, 0)

    @Test
    fun `clock times in Telugu and English`() {
        assertEquals(7, parse("repu 7 ki")?.hour)
        assertEquals(7, parse("repu edu gantalaki")?.hour)
        assertEquals(6, parse("evening 6 ki")?.hour)
        assertEquals(10, parse("tomorrow 10 am ki")?.hour)
        assertEquals(1, parse("repu 1 ki")?.dayOffset)
        assertEquals(0, parse("ivala 8 ki")?.dayOffset)
    }

    @Test
    fun `the recogniser drops the colon and we put the minutes back`() {
        val spec = parse("repu 7 30 ki")
        assertEquals(7, spec?.hour)
        assertEquals(30, spec?.minute)
    }

    @Test
    fun `parts of the day resolve the meridiem`() {
        assertEquals(7, parse("repu morning 7 ki")?.resolvedHour())
        assertEquals(18, parse("repu evening 6 ki")?.resolvedHour())
        assertEquals(22, parse("raatri 10 ki")?.resolvedHour())
        // Night wraps past midnight: "raatri 1" is 1 AM, not 1 PM.
        assertEquals(1, parse("raatri 1 ki")?.resolvedHour())
        assertEquals(14, parse("madhyanam 2 ki")?.resolvedHour())
    }

    @Test
    fun `a bare hour is reported as ambiguous instead of guessed`() {
        val spec = parse("repu 7 ki")
        assertTrue(spec!!.isAmbiguous)
        val (morning, evening) = spec.ambiguousAlternatives()!!
        assertEquals(7, morning.hour)
        assertEquals(19, evening.hour)
        assertTrue(!morning.isAmbiguous && !evening.isAmbiguous)
    }

    @Test
    fun `stated meridiem is not ambiguous`() {
        assertTrue(!parse("repu morning 7 ki")!!.isAmbiguous)
        assertTrue(!parse("evening 6 ki")!!.isAmbiguous)
    }

    @Test
    fun `durations for timers`() {
        assertEquals(20 * 60L, parse("20 minutes lo")?.durationSeconds)
        assertEquals(20 * 60L, parse("20 nimishalu tarvata")?.durationSeconds)
        assertEquals(30 * 60L, parse("ara ganta tarvata")?.durationSeconds)
        assertEquals(2 * 3600L, parse("2 hours tarvata")?.durationSeconds)
        assertEquals(TimeKind.DURATION, parse("20 minutes lo")?.kind)
    }

    @Test
    fun `an o clock reading is not mistaken for a countdown`() {
        val spec = parse("repu 7 gantalaki")
        assertEquals(TimeKind.ABSOLUTE, spec?.kind)
        assertEquals(7, spec?.hour)
    }

    @Test
    fun `vague times carry only the day or the part of day`() {
        val repu = parse("repu")
        assertEquals(TimeKind.VAGUE, repu?.kind)
        assertEquals(1, repu?.dayOffset)
        assertEquals(PartOfDay.EVENING, parse("saayantram")?.partOfDay)
    }

    @Test
    fun `weekdays are understood in both languages`() {
        assertEquals(java.time.DayOfWeek.MONDAY, parse("monday 9 ki")?.weekday)
        assertEquals(java.time.DayOfWeek.FRIDAY, parse("shukravaram 9 ki")?.weekday)
    }

    @Test
    fun `resolution rolls a passed time to the next day`() {
        // It is 10:00; "8 ki" with no day meant tomorrow morning.
        val spec = parse("morning 8 ki")!!
        val resolved = spec.resolve(now)
        assertEquals(22, resolved.dayOfMonth)
        assertEquals(8, resolved.hour)
    }

    @Test
    fun `resolution keeps a future time on the same day`() {
        val resolved = parse("evening 6 ki")!!.resolve(now)
        assertEquals(21, resolved.dayOfMonth)
        assertEquals(18, resolved.hour)
    }

    @Test
    fun `durations resolve relative to now`() {
        val resolved = parse("20 minutes lo")!!.resolve(now)
        assertEquals(10, resolved.hour)
        assertEquals(20, resolved.minute)
    }

    @Test
    fun `sentences with no time information return null`() {
        assertNull(parse("amma ki call chey"))
        assertNull(parse("whatsapp open chey"))
    }
}
