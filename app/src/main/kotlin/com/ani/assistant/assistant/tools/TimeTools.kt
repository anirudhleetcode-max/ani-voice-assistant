package com.ani.assistant.assistant.tools

import com.ani.assistant.assistant.AniTool
import com.ani.assistant.assistant.ToolContext
import com.ani.assistant.core.permission.AniPermission
import com.ani.assistant.core.result.AniResult
import com.ani.assistant.platform.alarm.AlarmLauncher
import com.ani.assistant.platform.alarm.ReminderScheduler
import com.ani.assistant.platform.alarm.ScheduleResult
import com.ani.nlu.intent.IntentType
import com.ani.nlu.intent.ParsedCommand
import com.ani.nlu.intent.SlotKey
import com.ani.nlu.response.Responses
import com.ani.nlu.time.TimeKind
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Alarms and timers, set in the user's own clock app.
 *
 * The ambiguity check happens here rather than in the parser because it is a
 * *conversational* decision: "repu 7 ki" is a perfectly well-formed time, it just has two
 * readings, and the right response is a question rather than a coin flip.
 */
class AlarmTool(private val alarms: AlarmLauncher) : AniTool {

    override val id: String = "alarm"
    override val handles: Set<IntentType> = setOf(IntentType.SET_ALARM, IntentType.SET_TIMER)

    override suspend fun execute(command: ParsedCommand, context: ToolContext): AniResult {
        val spec = command.timeSpec
            ?: return AniResult.NeedsInput(Responses.askWhen(context.style), "TIME")

        return when (command.type) {
            IntentType.SET_TIMER -> setTimer(command, context)
            else -> setAlarm(command, context, spec)
        }
    }

    private fun setAlarm(
        command: ParsedCommand,
        context: ToolContext,
        spec: com.ani.nlu.time.TimeSpec
    ): AniResult {
        if (spec.isAmbiguous) {
            return AniResult.NeedsInput(
                Responses.askMorningOrEvening(spec.hour ?: 0, context.style),
                "TIME"
            )
        }

        val label = context.settings.assistantName
        if (!alarms.setAlarm(spec, label)) {
            return AniResult.Failure(
                if (context.style.speaksTelugu) {
                    "Clock app dorakaledu${context.style.particle}, alarm pettaledu."
                } else {
                    "I couldn't find a clock app to set the alarm in."
                }
            )
        }

        // Clock apps may show their own confirmation screen despite EXTRA_SKIP_UI; the
        // alarm is still set either way.
        return AniResult.Success(Responses.alarmSet(spec, context.style))
    }

    private fun setTimer(command: ParsedCommand, context: ToolContext): AniResult {
        val spec = command.timeSpec
        val seconds = when {
            spec?.kind == TimeKind.DURATION -> spec.durationSeconds ?: 0L
            else -> 0L
        }
        if (seconds <= 0L) {
            return AniResult.NeedsInput(
                if (context.style.speaksTelugu) "Entha sepu timer?" else "How long?",
                "TIME"
            )
        }

        if (!alarms.setTimer(seconds, context.settings.assistantName)) {
            return AniResult.Failure(
                if (context.style.speaksTelugu) {
                    "Timer pettagalige app dorakaledu${context.style.particle}."
                } else {
                    "I couldn't find a clock app that accepts timers."
                }
            )
        }
        return AniResult.Success(Responses.timerSet(spec!!, context.style))
    }
}

/**
 * Reminders, which Ani delivers itself.
 *
 * Tells the truth about exactness: from Android 12 the system can refuse exact alarms, and
 * a reminder that may drift by several minutes is a different promise from one that will
 * not.
 */
class ReminderTool(
    private val reminders: ReminderScheduler,
    private val clock: Clock = Clock.systemDefaultZone()
) : AniTool {

    override val id: String = "reminder"
    override val handles: Set<IntentType> = setOf(IntentType.CREATE_REMINDER)

    override suspend fun execute(command: ParsedCommand, context: ToolContext): AniResult {
        val text = command[SlotKey.REMINDER_TEXT]
            ?: return AniResult.NeedsInput(
                Responses.askWhatToRemind(context.style),
                SlotKey.REMINDER_TEXT.name
            )

        val spec = command.timeSpec
            ?: return AniResult.NeedsInput(Responses.askWhen(context.style), "TIME")

        if (spec.isAmbiguous) {
            return AniResult.NeedsInput(
                Responses.askMorningOrEvening(spec.hour ?: 0, context.style),
                "TIME"
            )
        }

        val triggerAt = spec.resolve(LocalDateTime.now(clock))
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

        return when (val outcome = reminders.schedule(text, triggerAt)) {
            is ScheduleResult.Scheduled -> if (outcome.exact) {
                AniResult.Success(Responses.reminderSet(text, spec, context.style))
            } else {
                AniResult.Limitation(
                    spokenResponse = Responses.reminderSet(text, spec, context.style) + " " +
                        approximateHint(context),
                    fallbackTaken = "scheduled inexactly"
                )
            }

            ScheduleResult.TimeInPast -> AniResult.Failure(
                if (context.style.speaksTelugu) {
                    "Aa time already ayipoyindi${context.style.particle}."
                } else {
                    "That time has already passed."
                }
            )

            ScheduleResult.NeedsExactAlarmPermission -> AniResult.NeedsPermission(
                Responses.permissionMissing(AniPermission.EXACT_ALARMS.displayName, context.style),
                AniPermission.EXACT_ALARMS.storageKey
            )
        }
    }

    private fun approximateHint(context: ToolContext) = if (context.style.speaksTelugu) {
        "Konchem alasyam avvachu — exact alarms permission ivvu."
    } else {
        "It may be a few minutes late — grant exact alarm permission to fix that."
    }
}

/** Time, date and weather. */
class InformationTool(
    private val clock: Clock = Clock.systemDefaultZone()
) : AniTool {

    override val id: String = "information"
    override val handles: Set<IntentType> =
        setOf(IntentType.GET_TIME, IntentType.GET_DATE, IntentType.GET_WEATHER)

    override suspend fun execute(command: ParsedCommand, context: ToolContext): AniResult {
        val now = LocalDateTime.now(clock)
        return when (command.type) {
            IntentType.GET_TIME -> AniResult.Success(
                Responses.currentTime(now.format(TIME_FORMAT), context.style)
            )

            IntentType.GET_DATE -> AniResult.Success(
                Responses.currentDate(now.format(DATE_FORMAT), context.style)
            )

            // Weather needs a data source Ani does not ship. Saying so is better than a
            // made-up forecast, and the backend can answer it as a general question.
            else -> AniResult.Limitation(
                spokenResponse = Responses.weatherUnavailable(context.style),
                fallbackTaken = null
            )
        }
    }

    private companion object {
        val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)
        val DATE_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale.ENGLISH)
    }
}
