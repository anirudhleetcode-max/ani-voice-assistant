package com.ani.nlu.response

import com.ani.nlu.time.PartOfDay
import com.ani.nlu.time.TimeKind
import com.ani.nlu.time.TimeSpec

/**
 * Says a [TimeSpec] back to the user in their own words.
 *
 * Reading the time back is not decoration — it is the confirmation step. "Repu morning
 * 7 ki alarm petta" is how the user catches Ani having heard 7pm.
 */
object TimePhrasing {

    fun describe(spec: TimeSpec, style: ResponseStyle): String = when (spec.kind) {
        TimeKind.DURATION -> describeDuration(spec.durationSeconds ?: 0L, style)
        TimeKind.ABSOLUTE -> describeClock(spec, style)
        TimeKind.VAGUE -> describeVague(spec, style)
    }

    private fun describeDuration(seconds: Long, style: ResponseStyle): String {
        val minutes = seconds / 60
        val hours = minutes / 60
        return if (style.speaksTelugu) {
            when {
                seconds < 60 -> "$seconds sekanlu"
                hours >= 1 && minutes % 60 == 0L -> "$hours ${if (hours == 1L) "ganta" else "gantalu"}"
                hours >= 1 -> "$hours ${if (hours == 1L) "ganta" else "gantalu"} ${minutes % 60} nimishalu"
                else -> "$minutes ${if (minutes == 1L) "nimisham" else "nimishalu"}"
            }
        } else {
            when {
                seconds < 60 -> "$seconds seconds"
                hours >= 1 && minutes % 60 == 0L -> "$hours ${if (hours == 1L) "hour" else "hours"}"
                hours >= 1 -> "$hours ${if (hours == 1L) "hour" else "hours"} ${minutes % 60} minutes"
                else -> "$minutes ${if (minutes == 1L) "minute" else "minutes"}"
            }
        }
    }

    private fun describeClock(spec: TimeSpec, style: ResponseStyle): String {
        val hour24 = spec.resolvedHour()
        val hour12 = when {
            hour24 % 12 == 0 -> 12
            else -> hour24 % 12
        }
        val minutePart = if (spec.minute == 0) "" else ":%02d".format(spec.minute)
        val part = spec.partOfDay ?: partOfDayFor(hour24)
        val day = dayPrefix(spec, style)
        return if (style.speaksTelugu) {
            listOf(day, teluguPartOfDay(part), "$hour12$minutePart ki")
                .filter { it.isNotBlank() }
                .joinToString(" ")
        } else {
            listOf(day, "$hour12$minutePart ${englishMeridiem(hour24)}")
                .filter { it.isNotBlank() }
                .joinToString(" ")
        }
    }

    private fun describeVague(spec: TimeSpec, style: ResponseStyle): String {
        val day = dayPrefix(spec, style)
        val part = spec.partOfDay
        return if (style.speaksTelugu) {
            listOf(day, part?.let { teluguPartOfDay(it) }.orEmpty())
                .filter { it.isNotBlank() }
                .joinToString(" ")
                .ifBlank { "tarvata" }
        } else {
            listOf(day, part?.name?.lowercase().orEmpty())
                .filter { it.isNotBlank() }
                .joinToString(" ")
                .ifBlank { "later" }
        }
    }

    private fun dayPrefix(spec: TimeSpec, style: ResponseStyle): String {
        spec.weekday?.let { day ->
            val name = day.name.lowercase().replaceFirstChar { it.titlecase() }
            return if (style.speaksTelugu) name else "on $name"
        }
        return when (spec.dayOffset) {
            0 -> if (style.speaksTelugu) "ivala" else "today"
            1 -> if (style.speaksTelugu) "repu" else "tomorrow"
            else -> ""
        }
    }

    private fun teluguPartOfDay(part: PartOfDay): String = when (part) {
        PartOfDay.MORNING -> "morning"
        PartOfDay.AFTERNOON -> "madhyanam"
        PartOfDay.EVENING -> "evening"
        PartOfDay.NIGHT -> "raatri"
    }

    private fun englishMeridiem(hour24: Int): String = if (hour24 < 12) "AM" else "PM"

    private fun partOfDayFor(hour24: Int): PartOfDay = when {
        hour24 < 12 -> PartOfDay.MORNING
        hour24 < 16 -> PartOfDay.AFTERNOON
        hour24 < 20 -> PartOfDay.EVENING
        else -> PartOfDay.NIGHT
    }
}
