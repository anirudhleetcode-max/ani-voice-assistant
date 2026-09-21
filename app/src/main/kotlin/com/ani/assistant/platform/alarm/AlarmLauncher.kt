package com.ani.assistant.platform.alarm

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import com.ani.assistant.core.log.AniLog
import com.ani.assistant.platform.launch.ActivityLauncher
import com.ani.assistant.platform.launch.LaunchOutcome
import com.ani.nlu.time.TimeSpec

/**
 * Sets alarms and timers in the user's own clock app.
 *
 * Deliberately not implemented with our own AlarmManager. An alarm the user sets by voice
 * should appear in the same list as the ones they set by hand, ring with the sound they
 * chose, and survive Ani being uninstalled. `AlarmClock.ACTION_SET_ALARM` gives all of
 * that; a private alarm implementation gives none of it and rings through a media stream
 * the user may have muted.
 *
 * `EXTRA_SKIP_UI` asks the clock app to set it silently. Most honour it; some show their
 * own confirmation screen, which is their prerogative and not a failure.
 */
class AlarmLauncher(
    private val context: Context,
    private val launcher: ActivityLauncher
) {

    /** @return true when a clock app accepted the alarm. */
    fun setAlarm(spec: TimeSpec, label: String): Boolean {
        val hour = spec.resolvedHour()
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, spec.minute)
            putExtra(AlarmClock.EXTRA_MESSAGE, label)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            spec.weekday?.let { day ->
                // AlarmClock uses java.util.Calendar's 1=Sunday numbering.
                putExtra(AlarmClock.EXTRA_DAYS, arrayListOf((day.value % 7) + 1))
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return launch(intent, "alarm")
    }

    fun setTimer(durationSeconds: Long, label: String): Boolean {
        if (durationSeconds <= 0) return false
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, durationSeconds.toInt())
            putExtra(AlarmClock.EXTRA_MESSAGE, label)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return launch(intent, "timer")
    }

    /** Opens the alarm list, used when no clock app can handle the set intent. */
    fun showAlarms(): Boolean = launch(
        Intent(AlarmClock.ACTION_SHOW_ALARMS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        "alarm list"
    )

    private fun launch(intent: Intent, what: String): Boolean =
        when (launcher.launch(intent, "Set $what")) {
            LaunchOutcome.Launched, is LaunchOutcome.Deferred -> true
            LaunchOutcome.NoHandler -> {
                AniLog.w(TAG, "no app handles this intent", "what" to what)
                false
            }
            is LaunchOutcome.Failed -> {
                AniLog.w(TAG, "clock app refused", "what" to what)
                false
            }
        }

    private companion object {
        const val TAG = "AniAlarm"
    }
}
