package com.ani.nlu.time

import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.temporal.TemporalAdjusters

/** Which half of the day the user named, when they named one. */
enum class PartOfDay(val defaultHour: Int) {
    MORNING(7),
    AFTERNOON(14),
    EVENING(18),
    NIGHT(21);

    /** Converts a 1..12 clock reading into 0..23 for this part of the day. */
    fun to24Hour(hour12: Int): Int = when (this) {
        MORNING -> if (hour12 == 12) 0 else hour12
        AFTERNOON -> if (hour12 == 12) 12 else hour12 + 12
        EVENING -> if (hour12 >= 12) hour12 else hour12 + 12
        // "raatri 10" is 22:00, but "raatri 1" is 01:00 — night wraps past midnight.
        NIGHT -> when {
            hour12 == 12 -> 0
            hour12 <= 4 -> hour12
            else -> hour12 + 12
        }
    }
}

enum class TimeKind {
    /** A clock time: "repu 7 ki". */
    ABSOLUTE,

    /** A countdown: "20 minutes lo", "ara ganta tarvata". */
    DURATION,

    /** Only a day or a part of day: "repu", "saayantram". */
    VAGUE
}

/**
 * A time the user spoke, kept *unresolved* on purpose.
 *
 * "repu 7 ki" is not a timestamp until you know whether they meant morning or evening
 * and what "now" is. Keeping ambiguity explicit is what lets Ani ask
 * "Morning 7 aa, evening 7 aa?" instead of silently guessing and waking someone at 7pm.
 */
data class TimeSpec(
    val kind: TimeKind,
    val hour: Int? = null,
    val minute: Int = 0,
    /** 0 = today, 1 = tomorrow. Ignored when [weekday] is set. */
    val dayOffset: Int = 0,
    val weekday: DayOfWeek? = null,
    val durationSeconds: Long? = null,
    val partOfDay: PartOfDay? = null,
    /** True when we know whether [hour] is AM or PM. */
    val meridiemKnown: Boolean = false,
    /** The words this was parsed from, for echoing back to the user. */
    val rawText: String = ""
) {

    /**
     * True when the user said a bare 1..12 clock reading with no AM/PM cue, and Ani
     * should ask before scheduling anything.
     */
    val isAmbiguous: Boolean
        get() = kind == TimeKind.ABSOLUTE && !meridiemKnown && (hour ?: 0) in 1..12

    /** Both readings of an ambiguous hour, morning first. */
    fun ambiguousAlternatives(): Pair<TimeSpec, TimeSpec>? {
        if (!isAmbiguous) return null
        val h = hour ?: return null
        val morning = copy(
            hour = PartOfDay.MORNING.to24Hour(h),
            partOfDay = PartOfDay.MORNING,
            meridiemKnown = true
        )
        val evening = copy(
            hour = PartOfDay.EVENING.to24Hour(h),
            partOfDay = PartOfDay.EVENING,
            meridiemKnown = true
        )
        return morning to evening
    }

    /**
     * Turns this into a concrete instant relative to [now].
     *
     * For [TimeKind.ABSOLUTE] with no explicit day, a time that has already passed today
     * rolls to tomorrow — the behaviour every alarm clock has, and the one users expect
     * when they say "7 ki lepu" at midnight.
     */
    fun resolve(now: LocalDateTime): LocalDateTime = when (kind) {
        TimeKind.DURATION -> now.plusSeconds(durationSeconds ?: 0L)

        TimeKind.ABSOLUTE -> {
            val hourOfDay = resolvedHour()
            var candidate = now.toLocalDate()
                .let { date -> weekday?.let { date.with(TemporalAdjusters.nextOrSame(it)) } ?: date.plusDays(dayOffset.toLong()) }
                .atTime(hourOfDay, minute)
            if (weekday == null && dayOffset == 0 && !candidate.isAfter(now)) {
                candidate = candidate.plusDays(1)
            }
            candidate
        }

        TimeKind.VAGUE -> {
            val hourOfDay = partOfDay?.defaultHour ?: 9
            var candidate = now.toLocalDate()
                .let { date -> weekday?.let { date.with(TemporalAdjusters.nextOrSame(it)) } ?: date.plusDays(dayOffset.toLong()) }
                .atTime(hourOfDay, 0)
            if (weekday == null && dayOffset == 0 && !candidate.isAfter(now)) {
                candidate = candidate.plusDays(1)
            }
            candidate
        }
    }

    /** [hour] normalised to 0..23 using [partOfDay] when available. */
    fun resolvedHour(): Int {
        val h = hour ?: partOfDay?.defaultHour ?: 9
        if (h > 12) return h.coerceIn(0, 23)
        val part = partOfDay ?: return h
        return part.to24Hour(h)
    }
}
