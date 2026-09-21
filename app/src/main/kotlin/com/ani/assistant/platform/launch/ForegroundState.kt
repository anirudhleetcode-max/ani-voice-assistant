package com.ani.assistant.platform.launch

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.util.concurrent.atomic.AtomicInteger

/**
 * Whether any of Ani's own activities is currently resumed.
 *
 * This is not a nicety. From Android 10, an app in the background cannot start an
 * activity: `startActivity` returns normally, logs a line to the system log, and does
 * nothing. Code that treats "no exception" as "it worked" will cheerfully tell the user
 * it is calling their brother while the dialler never opens — which is exactly the bug
 * this was written to fix.
 *
 * Knowing which side of that line we are on is what lets [ActivityLauncher] pick a route
 * that actually works, and report honestly when no route does.
 */
class ForegroundState : Application.ActivityLifecycleCallbacks {

    private val resumedActivities = AtomicInteger(0)

    /** True when an Ani activity is resumed, so a direct activity start will be allowed. */
    val isInForeground: Boolean get() = resumedActivities.get() > 0

    override fun onActivityResumed(activity: Activity) {
        resumedActivities.incrementAndGet()
    }

    override fun onActivityPaused(activity: Activity) {
        resumedActivities.updateAndGet { current -> if (current > 0) current - 1 else 0 }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
