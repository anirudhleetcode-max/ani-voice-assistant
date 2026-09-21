package com.ani.assistant.launch

import com.ani.assistant.platform.launch.LaunchPolicy
import com.ani.assistant.platform.launch.LaunchRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The rule that was missing when actions silently did nothing.
 *
 * From Android 10, an app in the background cannot start an activity: `startActivity`
 * returns normally and nothing happens. Every external action in Ani — calling, Spotify,
 * opening an app — went straight to `startActivity` and treated the absence of an
 * exception as success. So a command typed in the UI worked, and the identical command
 * spoken to the wake word reported "calling Annayya" while the dialler never opened.
 *
 * These pin the decision that replaced that assumption.
 */
class LaunchPolicyTest {

    private val modernAndroid = 35
    private val preRestrictionAndroid = 28

    @Test
    fun `in the foreground it starts directly`() {
        assertEquals(
            LaunchRoute.DIRECT,
            LaunchPolicy.route(
                hasHandler = true,
                isInForeground = true,
                sdkInt = modernAndroid,
                allowDeferral = true
            )
        )
    }

    @Test
    fun `in the background on modern Android it defers instead of pretending`() {
        assertEquals(
            LaunchRoute.DEFER,
            LaunchPolicy.route(
                hasHandler = true,
                isInForeground = false,
                sdkInt = modernAndroid,
                allowDeferral = true
            )
        )
    }

    @Test
    fun `before the restriction existed a background start was fine`() {
        assertEquals(
            LaunchRoute.DIRECT,
            LaunchPolicy.route(
                hasHandler = true,
                isInForeground = false,
                sdkInt = preRestrictionAndroid,
                allowDeferral = true
            )
        )
    }

    @Test
    fun `the restriction begins exactly at API 29`() {
        assertEquals(
            LaunchRoute.DIRECT,
            LaunchPolicy.route(true, isInForeground = false, sdkInt = 28, allowDeferral = true)
        )
        assertEquals(
            LaunchRoute.DEFER,
            LaunchPolicy.route(true, isInForeground = false, sdkInt = 29, allowDeferral = true)
        )
    }

    @Test
    fun `no handler is reported before anything else is considered`() {
        for (foreground in listOf(true, false)) {
            assertEquals(
                LaunchRoute.NO_HANDLER,
                LaunchPolicy.route(
                    hasHandler = false,
                    isInForeground = foreground,
                    sdkInt = modernAndroid,
                    allowDeferral = true
                )
            )
        }
    }

    @Test
    fun `an action that must not become a notification is refused rather than deferred`() {
        // Opening a settings screen the user did not ask to be interrupted by should fail
        // honestly, not arrive as a notification an hour later.
        assertEquals(
            LaunchRoute.REFUSE,
            LaunchPolicy.route(
                hasHandler = true,
                isInForeground = false,
                sdkInt = modernAndroid,
                allowDeferral = false
            )
        )
    }

    @Test
    fun `a refusable action still starts directly in the foreground`() {
        assertEquals(
            LaunchRoute.DIRECT,
            LaunchPolicy.route(
                hasHandler = true,
                isInForeground = true,
                sdkInt = modernAndroid,
                allowDeferral = false
            )
        )
    }

    @Test
    fun `no route ever silently succeeds`() {
        // Every combination resolves to a route the caller must handle explicitly. The
        // bug being fixed was a path with no such decision at all.
        val routes = buildSet {
            for (handler in listOf(true, false)) {
                for (foreground in listOf(true, false)) {
                    for (sdk in listOf(26, 28, 29, 34, 35)) {
                        for (defer in listOf(true, false)) {
                            add(LaunchPolicy.route(handler, foreground, sdk, defer))
                        }
                    }
                }
            }
        }
        assertEquals(LaunchRoute.entries.toSet(), routes)
    }
    @Test
    fun `display over other apps lifts the background block`() {
        // SYSTEM_ALERT_WINDOW is the platform's own exemption. With it granted, a command
        // spoken to a locked phone opens the dialler instead of a notification.
        assertEquals(
            LaunchRoute.DIRECT,
            LaunchPolicy.route(
                hasHandler = true,
                isInForeground = false,
                sdkInt = modernAndroid,
                allowDeferral = true,
                canDrawOverlays = true
            )
        )
    }

    @Test
    fun `the overlay exemption still cannot conjure a handler`() {
        assertEquals(
            LaunchRoute.NO_HANDLER,
            LaunchPolicy.route(
                hasHandler = false,
                isInForeground = false,
                sdkInt = modernAndroid,
                allowDeferral = true,
                canDrawOverlays = true
            )
        )
    }

    @Test
    fun `without the overlay permission a background start is never direct`() {
        // The default matters: any caller that forgets to pass it gets the safe answer,
        // which is the honest one rather than the optimistic one.
        assertNotEquals(
            LaunchRoute.DIRECT,
            LaunchPolicy.route(
                hasHandler = true,
                isInForeground = false,
                sdkInt = modernAndroid,
                allowDeferral = true
            )
        )
    }

}
