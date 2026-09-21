package com.ani.assistant

import android.app.Application
import android.content.Context
import com.ani.assistant.core.log.AniLog
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Process entry point.
 *
 * Holds the [AppGraph] and does the small amount of housekeeping that has to happen once
 * per process: pruning stored data past the user's retention window, and refreshing the
 * permission snapshot, since the user may have changed things in Settings while the app
 * was not running.
 */
class AniApplication : Application() {

    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        instance = this

        graph.applicationScope.launch {
            try {
                pruneStoredData()
            } catch (error: Exception) {
                AniLog.e(TAG, "startup housekeeping failed", error)
            }
        }
    }

    /**
     * Enforces retention on every cold start.
     *
     * Doing it here rather than on read means a user who set retention to seven days and
     * then never opened the app again still has their old transcripts removed the next
     * time any part of Ani runs — including the notification listener.
     */
    private suspend fun pruneStoredData() {
        val settings = graph.settingsRepository.settings.first()
        graph.conversationRepository.pruneOlderThan(settings.historyRetentionDays)
        graph.notificationRepository.pruneOlderThan(settings.notificationRetentionHours)
        graph.reminderScheduler.clearFired()
        graph.permissionManager.refresh()
        graph.appResolver.invalidate()
    }

    companion object {
        private const val TAG = "AniApplication"

        @Volatile
        private var instance: AniApplication? = null

        /**
         * The graph, for the components Android constructs itself — services and
         * broadcast receivers, which get a [Context] and nothing else.
         *
         * Returns null rather than throwing: a receiver can fire while the process is
         * being torn down, and crashing there helps nobody.
         */
        fun graphOrNull(context: Context): AppGraph? {
            val application = instance
                ?: context.applicationContext as? AniApplication
                ?: return null
            // A receiver can fire before Application.onCreate has finished on a cold
            // start, so the lateinit has to be checked rather than assumed.
            return if (application::graph.isInitialized) application.graph else null
        }
    }
}
