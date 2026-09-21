package com.ani.nlu.time

import com.ani.nlu.lexicon.Lexicon
import com.ani.nlu.lexicon.Morphology
import com.ani.nlu.lexicon.Numbers
import com.ani.nlu.lexicon.SemanticTag
import com.ani.nlu.text.NormalizedText
import com.ani.nlu.text.PhoneticKey
import com.ani.nlu.text.Token
import java.time.DayOfWeek

/**
 * Pulls a [TimeSpec] out of Telugu, English or mixed speech.
 *
 * Handles the forms that actually come up:
 * ```
 * "repu morning 7 ki"        -> tomorrow 07:00
 * "repu edu gantalaki"       -> tomorrow 07:00 (spelled-out Telugu numeral)
 * "evening 6 ki"             -> today/tomorrow 18:00
 * "20 minutes lo"            -> +20 min
 * "ara ganta tarvata"        -> +30 min
 * "7 30 ki"                  -> 07:30 (the recogniser drops the colon)
 * "next monday"              -> the coming Monday
 * "repu"                     -> VAGUE, tomorrow
 * ```
 * A bare "7 ki" comes back [TimeSpec.isAmbiguous], which is the signal for Ani to ask
 * "morning aa, evening aa?" rather than guess.
 */
object TeluguTimeParser {

    private val halfKeys = setOf("ara", "half", "arda").map { PhoneticKey.of(it) }.toSet()
    private val quarterKeys = setOf("pavu", "quarter").map { PhoneticKey.of(it) }.toSet()

    private val weekdays: Map<String, DayOfWeek> = buildMap {
        fun reg(day: DayOfWeek, vararg spellings: String) {
            spellings.forEach { put(PhoneticKey.of(it), day) }
        }
        reg(DayOfWeek.MONDAY, "monday", "somavaram")
        reg(DayOfWeek.TUESDAY, "tuesday", "mangalavaram")
        reg(DayOfWeek.WEDNESDAY, "wednesday", "budhavaram")
        reg(DayOfWeek.THURSDAY, "thursday", "guruvaram")
        reg(DayOfWeek.FRIDAY, "friday", "shukravaram")
        reg(DayOfWeek.SATURDAY, "saturday", "shanivaram")
        reg(DayOfWeek.SUNDAY, "sunday", "adivaram")
    }

    /** Returns null when the utterance carries no time information at all. */
    fun parse(text: NormalizedText): TimeSpec? {
        if (text.isEmpty) return null
        val tokens = text.tokens

        val duration = parseDuration(tokens)
        if (duration != null) return duration

        val dayOffset = parseDayOffset(tokens)
        val weekday = tokens.firstNotNullOfOrNull { weekdays[it.key] }
        val partOfDay = parsePartOfDay(tokens)
        val clock = parseClock(tokens)

        if (clock == null) {
            if (dayOffset == null && weekday == null && partOfDay == null) return null
            return TimeSpec(
                kind = TimeKind.VAGUE,
                dayOffset = dayOffset ?: 0,
                weekday = weekday,
                partOfDay = partOfDay,
                rawText = text.normalized
            )
        }

        val (hour, minute, explicit24) = clock
        val meridiemKnown = partOfDay != null || explicit24 || hour == 0 || hour > 12
        return TimeSpec(
            kind = TimeKind.ABSOLUTE,
            hour = hour,
            minute = minute,
            dayOffset = dayOffset ?: 0,
            weekday = weekday,
            partOfDay = partOfDay,
            meridiemKnown = meridiemKnown,
            rawText = text.normalized
        )
    }

    // -----------------------------------------------------------------------------

    private fun parseDayOffset(tokens: List<Token>): Int? {
        for (token in tokens) {
            val tags = Lexicon.tagsFor(token)
            when {
                SemanticTag.T_TOMORROW in tags -> return 1
                SemanticTag.T_TODAY in tags -> return 0
                SemanticTag.T_YESTERDAY in tags -> return -1
            }
        }
        // "tonight" is a part of day *and* today.
        return null
    }

    private fun parsePartOfDay(tokens: List<Token>): PartOfDay? {
        for (token in tokens) {
            val tags = Lexicon.tagsFor(token)
            when {
                SemanticTag.T_MORNING in tags -> return PartOfDay.MORNING
                SemanticTag.T_AFTERNOON in tags -> return PartOfDay.AFTERNOON
                SemanticTag.T_EVENING in tags -> return PartOfDay.EVENING
                SemanticTag.T_NIGHT in tags -> return PartOfDay.NIGHT
            }
        }
        return null
    }

    /**
     * "20 minutes lo", "ara ganta tarvata", "2 hours tarvata".
     *
     * A bare "2 gantalaki" is *not* a duration — that is 2 o'clock. We only read a
     * duration when a unit is present and either an explicit "after"/"in" marker or a
     * minute/second unit makes the countdown reading the only sensible one.
     */
    private fun parseDuration(tokens: List<Token>): TimeSpec? {
        for ((index, token) in tokens.withIndex()) {
            val tags = Lexicon.tagsFor(token)
            val unitSeconds = when {
                SemanticTag.T_SECOND_UNIT in tags -> 1L
                SemanticTag.T_MINUTE_UNIT in tags -> 60L
                SemanticTag.T_HOUR_UNIT in tags -> 3600L
                else -> null
            } ?: continue

            val amount = amountBefore(tokens, index) ?: continue
            val hasAfterMarker = tokens.any { SemanticTag.T_AFTER in Lexicon.tagsFor(it) } ||
                tokens.any { Morphology.isLocativeMarker(it.text) }

            val isCountdownUnit = unitSeconds < 3600L
            if (!isCountdownUnit && !hasAfterMarker) continue
            // "7 gantalaki" (dative on the unit) is a clock time, not "in 7 hours".
            if (!hasAfterMarker && isDativeUnit(token)) continue

            val seconds = (amount * unitSeconds).toLong()
            if (seconds <= 0L) continue
            return TimeSpec(
                kind = TimeKind.DURATION,
                durationSeconds = seconds,
                rawText = tokens.joinToString(" ") { it.text }
            )
        }
        return null
    }

    /** "ara ganta" = 0.5, "2 hours" = 2.0, "20 minutes" = 20.0. */
    private fun amountBefore(tokens: List<Token>, unitIndex: Int): Double? {
        val previous = tokens.getOrNull(unitIndex - 1) ?: return null
        Numbers.valueOf(previous)?.let { return it.toDouble() }
        if (previous.key in halfKeys) return 0.5
        if (previous.key in quarterKeys) return 0.25
        return null
    }

    /** True for "gantalaki"/"gantaki" — the unit itself carries the dative marker. */
    private fun isDativeUnit(token: Token): Boolean {
        val lower = token.text.lowercase()
        return lower.endsWith("ki") || lower.endsWith("ku")
    }

    /**
     * Finds a clock reading. Returns hour, minute and whether the hour was unambiguously
     * 24-hour (i.e. greater than 12).
     */
    private fun parseClock(tokens: List<Token>): Triple<Int, Int, Boolean>? {
        for ((index, token) in tokens.withIndex()) {
            val value = numberAt(tokens, index) ?: continue
            if (value !in 0..23) continue

            val next = tokens.getOrNull(index + 1)
            val nextTags = next?.let { Lexicon.tagsFor(it) }.orEmpty()

            val followedByHourUnit = SemanticTag.T_HOUR_UNIT in nextTags
            val carriesDative = carriesDativeMarker(token)
            val followedByDative = next != null && Morphology.isDativeMarker(next.text)
            val followedByPartOfDay = nextTags.any {
                it == SemanticTag.T_MORNING || it == SemanticTag.T_AFTERNOON ||
                    it == SemanticTag.T_EVENING || it == SemanticTag.T_NIGHT
            }
            val precededByPartOfDay = tokens.getOrNull(index - 1)
                ?.let { Lexicon.tagsFor(it) }
                ?.any {
                    it == SemanticTag.T_MORNING || it == SemanticTag.T_AFTERNOON ||
                        it == SemanticTag.T_EVENING || it == SemanticTag.T_NIGHT
                } ?: false

            // "7 30 ki" — the colon is lost in recognition, so an hour can be followed
            // by its minutes and only then by the marker that makes it a clock time.
            val nextValue = next?.let { Numbers.valueOf(it) }
            val afterNext = tokens.getOrNull(index + 2)
            val minuteThenMarker = nextValue != null && nextValue in 0..59 && afterNext != null &&
                (
                    Morphology.isDativeMarker(afterNext.text) ||
                        SemanticTag.T_HOUR_UNIT in Lexicon.tagsFor(afterNext)
                    )
            val minuteCarriesDative = next != null && nextValue != null && nextValue in 0..59 &&
                carriesDativeMarker(next)

            val looksLikeClock = followedByHourUnit || carriesDative || followedByDative ||
                followedByPartOfDay || precededByPartOfDay || minuteThenMarker || minuteCarriesDative
            if (!looksLikeClock) continue

            val minute = if (nextValue != null && nextValue in 0..59 && !followedByHourUnit) {
                nextValue
            } else {
                0
            }
            return Triple(value, minute, value > 12)
        }
        return null
    }

    /**
     * The number at [index], tolerating the dative marker being glued on ("7ki",
     * "eduki").
     */
    private fun numberAt(tokens: List<Token>, index: Int): Int? {
        val token = tokens.getOrNull(index) ?: return null
        Numbers.valueOf(token)?.let { return it }

        val text = token.text.lowercase()
        val stripped = when {
            text.endsWith("ki") -> text.dropLast(2)
            text.endsWith("ku") -> text.dropLast(2)
            else -> return null
        }
        if (stripped.isEmpty()) return null
        stripped.toIntOrNull()?.let { return it }
        return Numbers.valueOfKey(PhoneticKey.of(stripped))
    }

    private fun carriesDativeMarker(token: Token): Boolean {
        val text = token.text.lowercase()
        if (token.isNumeric) return false
        return (text.endsWith("ki") || text.endsWith("ku")) && text.length > 2 &&
            numberAt(listOf(token), 0) != null
    }
}
