@file:Suppress("UNUSED_PARAMETER", "unused")

package androidx.core.app

import android.app.Activity
import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle

object ActivityCompat {
    @JvmStatic
    fun shouldShowRequestPermissionRationale(activity: Activity, permission: String): Boolean = false

    @JvmStatic
    fun requestPermissions(activity: Activity, permissions: Array<String>, requestCode: Int) = Unit
}

object ServiceCompat {
    const val STOP_FOREGROUND_REMOVE = 1
    const val STOP_FOREGROUND_DETACH = 2

    @JvmStatic
    fun startForeground(service: Service, id: Int, notification: Notification, foregroundServiceType: Int) = Unit

    @JvmStatic
    fun stopForeground(service: Service, flags: Int) = Unit
}

class NotificationManagerCompat private constructor() {
    fun notify(id: Int, notification: Notification) = Unit
    fun cancel(id: Int) = Unit
    fun areNotificationsEnabled(): Boolean = true

    companion object {
        @JvmStatic
        fun from(context: Context): NotificationManagerCompat = NotificationManagerCompat()
    }
}

object NotificationCompat {
    const val PRIORITY_LOW = -1
    const val PRIORITY_DEFAULT = 0
    const val PRIORITY_HIGH = 1
    const val CATEGORY_SERVICE = "service"
    const val CATEGORY_REMINDER = "reminder"
    const val CATEGORY_CALL = "call"
    const val CATEGORY_ALARM = "alarm"
    const val CATEGORY_MESSAGE = "msg"
    const val VISIBILITY_PUBLIC = 1
    const val VISIBILITY_PRIVATE = 0
    const val VISIBILITY_SECRET = -1
    const val FOREGROUND_SERVICE_IMMEDIATE = 1

    open class Style

    class BigTextStyle : Style() {
        fun bigText(text: CharSequence): BigTextStyle = this
    }

    class Builder(context: Context, channelId: String) {
        fun setSmallIcon(icon: Int): Builder = this
        fun setContentTitle(title: CharSequence?): Builder = this
        fun setContentText(text: CharSequence?): Builder = this
        fun setStyle(style: Style?): Builder = this
        fun setPriority(priority: Int): Builder = this
        fun setCategory(category: String?): Builder = this
        fun setAutoCancel(autoCancel: Boolean): Builder = this
        fun setOngoing(ongoing: Boolean): Builder = this
        fun setSilent(silent: Boolean): Builder = this
        fun setVisibility(visibility: Int): Builder = this
        fun setForegroundServiceBehavior(behavior: Int): Builder = this
        fun setContentIntent(intent: android.app.PendingIntent?): Builder = this
        fun setFullScreenIntent(intent: android.app.PendingIntent?, highPriority: Boolean): Builder = this
        fun addAction(icon: Int, title: CharSequence?, intent: android.app.PendingIntent?): Builder = this
        fun setShowWhen(show: Boolean): Builder = this
        fun build(): Notification = Notification()
    }
}
