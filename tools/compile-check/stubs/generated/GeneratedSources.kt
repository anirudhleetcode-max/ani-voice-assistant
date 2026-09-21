// Stand-ins for the sources AGP generates: BuildConfig, and the R class from res/.
@file:Suppress("unused")

package com.ani.assistant

object BuildConfig {
    const val DEBUG: Boolean = true
    const val APPLICATION_ID: String = "com.ani.assistant"
    const val VERSION_NAME: String = "1.0.0"
    const val VERSION_CODE: Int = 1
    const val AI_BACKEND_URL: String = ""
}

object R {
    object string {
        const val app_name = 1
        const val notification_listener_label = 2
        const val listening_channel_name = 3
        const val listening_channel_description = 4
        const val listening_notification_title = 5
        const val listening_notification_text = 6
        const val listening_notification_stop = 7
        const val reminder_channel_name = 8
        const val reminder_channel_description = 9
        const val content_description_voice_orb = 10
    }

    object drawable {
        const val ic_notification = 100
        const val ic_ani_mark = 101
        const val ic_launcher_background = 102
    }

    object mipmap {
        const val ic_launcher = 200
        const val ic_launcher_round = 201
    }
}
