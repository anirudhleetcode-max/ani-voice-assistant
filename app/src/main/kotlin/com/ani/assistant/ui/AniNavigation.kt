package com.ani.assistant.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.ui.graphics.vector.ImageVector

/** Every screen, and the five that appear in the bottom bar. */
enum class AniDestination(
    val route: String,
    val label: String,
    val icon: ImageVector?,
    val inBottomBar: Boolean
) {
    HOME("home", "Home", Icons.Rounded.Home, true),
    HISTORY("history", "History", Icons.Rounded.History, true),
    COMMANDS("commands", "Commands", Icons.Rounded.Bolt, true),
    MEMORY("memory", "Memory", Icons.Rounded.AutoAwesome, true),
    SETTINGS("settings", "Settings", Icons.Rounded.Settings, true),

    ONBOARDING("onboarding", "Welcome", null, false),
    PRIVACY("privacy", "Privacy Center", null, false),
    DIAGNOSTICS("diagnostics", "Diagnostics", null, false),
    WAKE_SETTINGS("settings/wake", "Wake word", null, false),
    VOICE_SETTINGS("settings/voice", "Voice", null, false),
    NOTIFICATION_SETTINGS("settings/notifications", "Notification access", null, false);

    companion object {
        val bottomBar: List<AniDestination> = entries.filter { it.inBottomBar }

        fun fromRoute(route: String?): AniDestination? = entries.firstOrNull { it.route == route }
    }
}
