package com.ani.assistant.platform.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.ani.assistant.core.log.AniLog
import com.ani.assistant.data.store.JsonListStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import java.util.UUID

/** A reminder the user asked for. */
@Serializable
data class Reminder(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val triggerAtMillis: Long,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val fired: Boolean = false
) {
    /** AlarmManager needs an int request code; the id's hash is stable across restarts. */
    val requestCode: Int get() = id.hashCode()
}

/** Why a reminder could not be scheduled, so the assistant can say something true. */
sealed interface ScheduleResult {
    data class Scheduled(val reminder: Reminder, val exact: Boolean) : ScheduleResult
    data object TimeInPast : ScheduleResult
    data object NeedsExactAlarmPermission : ScheduleResult
}

/**
 * Reminders, which — unlike alarms — Ani owns end to end.
 *
 * An alarm belongs in the clock app (see [AlarmLauncher]); a reminder is a notification
 * with arbitrary text at an arbitrary moment, which no standard intent covers.
 *
 * Exactness is not assumed. From Android 12 the system may refuse exact alarms, so this
 * checks first and falls back to an inexact one, reporting which it got. The difference
 * matters to the user: "6 ki gurthu chestha" and "6 ki around gurthu chestha" are
 * different promises.
 */
class ReminderScheduler(private val context: Context) {

    private val store = JsonListStore(
        context = context,
        fileName = "reminders.json",
        itemSerializer = Reminder.serializer(),
        maxEntries = MAX_REMINDERS
    )

    val reminders: Flow<List<Reminder>> = store.items

    private val alarmManager: AlarmManager?
        get() = context.getSystemService(AlarmManager::class.java)

    fun canScheduleExactly(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return alarmManager?.canScheduleExactAlarms() == true
    }

    suspend fun schedule(text: String, triggerAtMillis: Long): ScheduleResult {
        if (triggerAtMillis <= System.currentTimeMillis()) return ScheduleResult.TimeInPast

        val reminder = Reminder(text = text, triggerAtMillis = triggerAtMillis)
        val exact = armAlarm(reminder)
        store.add(reminder)
        return ScheduleResult.Scheduled(reminder, exact)
    }

    suspend fun cancel(id: String) {
        val reminder = store.items.first().firstOrNull { it.id == id } ?: return
        alarmManager?.cancel(pendingIntentFor(reminder, mutable = false))
        store.removeWhere { it.id == id }
    }

    suspend fun markFired(id: String) {
        store.update { current -> current.map { if (it.id == id) it.copy(fired = true) else it } }
    }

    suspend fun clearFired() = store.removeWhere { it.fired }

    /**
     * Re-arms everything still in the future.
     *
     * AlarmManager forgets every alarm across a reboot and across an app update, so this
     * runs from [BootReceiver] on both BOOT_COMPLETED and MY_PACKAGE_REPLACED. Without it
     * a reminder set before a restart simply never arrives, which is the single most
     * damaging way a reminder feature can fail.
     */
    suspend fun rescheduleAll(): Int {
        val now = System.currentTimeMillis()
        val pending = store.items.first().filter { !it.fired && it.triggerAtMillis > now }
        pending.forEach { armAlarm(it) }
        // Anything whose moment passed while the phone was off is no longer useful.
        store.update { current -> current.filterNot { !it.fired && it.triggerAtMillis <= now } }
        AniLog.i(TAG, "rescheduled reminders after restart", "count" to pending.size)
        return pending.size
    }

    /** @return true when the alarm was armed exactly. */
    private fun armAlarm(reminder: Reminder): Boolean {
        val manager = alarmManager ?: return false
        val pendingIntent = pendingIntentFor(reminder, mutable = false)
        return try {
            if (canScheduleExactly()) {
                manager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    reminder.triggerAtMillis,
                    pendingIntent
                )
                true
            } else {
                // Inexact alarms are still delivered, just batched with other wakeups.
                manager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    reminder.triggerAtMillis,
                    pendingIntent
                )
                false
            }
        } catch (error: SecurityException) {
            AniLog.w(TAG, "exact alarm refused, falling back to inexact")
            manager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                reminder.triggerAtMillis,
                pendingIntent
            )
            false
        }
    }

    private fun pendingIntentFor(reminder: Reminder, mutable: Boolean): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ReminderReceiver.ACTION_FIRE
            putExtra(ReminderReceiver.EXTRA_ID, reminder.id)
            putExtra(ReminderReceiver.EXTRA_TEXT, reminder.text)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, reminder.requestCode, intent, flags)
    }

    private companion object {
        const val TAG = "AniReminders"
        const val MAX_REMINDERS = 200
    }
}
