package com.ani.assistant.platform.music

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.SystemClock
import android.view.KeyEvent
import com.ani.assistant.core.log.AniLog
import com.ani.assistant.notifications.AniNotificationListenerService
import java.net.URLEncoder

/** What is playing right now, when Ani can see it. */
data class NowPlaying(
    val title: String?,
    val artist: String?,
    val appPackage: String,
    val isPlaying: Boolean
)

/** The outcome of a music request, kept precise so Ani never overstates what happened. */
sealed interface MusicOutcome {
    /** Transport control reached a real media session. */
    data class Controlled(val action: String) : MusicOutcome

    /** A search was opened in the music app. The user still presses play. */
    data class SearchOpened(val appLabel: String, val query: String) : MusicOutcome

    data class AppNotInstalled(val appLabel: String) : MusicOutcome

    data object NothingPlaying : MusicOutcome

    data object Failed : MusicOutcome
}

/**
 * Music playback and search.
 *
 * Two distinct capabilities, and it matters that they are not conflated:
 *
 * **Transport control** (pause, next, previous) is real. When the user has granted
 * notification access, `MediaSessionManager` hands us a [MediaController] for whatever is
 * playing and the commands go straight to it — Spotify, YouTube Music, a podcast app, any
 * of them. Without notification access there is still `dispatchMediaKeyEvent`, which is
 * exactly what a headset button does and needs no permission at all.
 *
 * **Starting a specific track** is not. No third-party app can tell Spotify to play a
 * named song: that needs the Spotify App Remote SDK, which is distributed separately,
 * requires a registered client ID and an authenticated user, and is out of scope for a
 * personal build. What Ani does instead is open Spotify's search deep link with the query
 * already filled in, and *say that is what it did*. See SPOTIFY_INTEGRATION.md.
 */
class MusicController(private val context: Context) {

    private val audioManager: AudioManager?
        get() = context.getSystemService(AudioManager::class.java)

    private val sessionManager: MediaSessionManager?
        get() = context.getSystemService(MediaSessionManager::class.java)

    // ---- Transport control -------------------------------------------------------------

    fun control(action: String): MusicOutcome {
        val controller = activeController()
        if (controller != null) {
            val transport = controller.transportControls
            when (action) {
                "pause" -> transport.pause()
                "resume" -> transport.play()
                "next" -> transport.skipToNext()
                "previous" -> transport.skipToPrevious()
                "stop" -> transport.pause()
                "replay" -> {
                    transport.seekTo(0)
                    transport.play()
                }
                else -> return MusicOutcome.Failed
            }
            return MusicOutcome.Controlled(action)
        }

        // No visible session — either notification access is off, or nothing is playing.
        // Media key events reach the system's current media owner either way.
        val keyCode = when (action) {
            "pause", "stop" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "resume", "replay" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            else -> return MusicOutcome.Failed
        }
        return if (dispatchMediaKey(keyCode)) {
            MusicOutcome.Controlled(action)
        } else {
            MusicOutcome.NothingPlaying
        }
    }

    fun nowPlaying(): NowPlaying? {
        val controller = activeController() ?: return null
        val metadata = controller.metadata
        return NowPlaying(
            title = metadata?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE),
            artist = metadata?.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST),
            appPackage = controller.packageName,
            isPlaying = controller.playbackState?.state == PlaybackState.STATE_PLAYING
        )
    }

    /**
     * The media session Ani should talk to.
     *
     * Prefers one that is actually playing; falls back to the most recently active, which
     * is what "resume" needs. Returns null when notification access has not been granted —
     * `getActiveSessions` throws in that case, and catching it is the documented way to
     * find out.
     */
    private fun activeController(): MediaController? = try {
        val listener = ComponentName(context, AniNotificationListenerService::class.java)
        val sessions = sessionManager?.getActiveSessions(listener).orEmpty()
        sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: sessions.firstOrNull()
    } catch (error: SecurityException) {
        AniLog.d(TAG, "no media session access; falling back to media keys")
        null
    } catch (error: Exception) {
        AniLog.w(TAG, "media session lookup failed", "error" to error.javaClass.simpleName)
        null
    }

    private fun dispatchMediaKey(keyCode: Int): Boolean {
        val manager = audioManager ?: return false
        return try {
            val now = SystemClock.uptimeMillis()
            manager.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
            manager.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
            true
        } catch (error: Exception) {
            AniLog.w(TAG, "media key dispatch failed")
            false
        }
    }

    // ---- Search ---------------------------------------------------------------------

    /**
     * Opens [query] in the user's music app.
     *
     * Spotify's `spotify:search:` URI is a documented deep link and lands on the results
     * page with the query applied. It does not start playback, and the caller must say so.
     */
    fun openSearch(query: String, provider: String): MusicOutcome {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val (label, candidates) = when (provider) {
            "youtube", "youtubemusic" -> "YouTube Music" to listOf(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://music.youtube.com/search?q=$encoded")),
                Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=$encoded"))
            )

            "gaana" -> "Gaana" to listOf(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://gaana.com/search/$encoded"))
            )

            "jiosaavn" -> "JioSaavn" to listOf(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://www.jiosaavn.com/search/$encoded"))
            )

            else -> "Spotify" to listOf(
                Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:${query.replace(' ', '+')}"))
                    .setPackage(SPOTIFY_PACKAGE),
                Intent(Intent.ACTION_VIEW, Uri.parse("https://open.spotify.com/search/$encoded"))
            )
        }

        for (intent in candidates) {
            val launchable = intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (launchable.resolveActivity(context.packageManager) == null) continue
            return try {
                context.startActivity(launchable)
                MusicOutcome.SearchOpened(label, query)
            } catch (error: Exception) {
                AniLog.w(TAG, "music search failed to open", "provider" to provider)
                continue
            }
        }
        return MusicOutcome.AppNotInstalled(label)
    }

    /** Opens the music app itself, with no search. */
    fun openApp(provider: String): Boolean {
        val packageName = when (provider) {
            "youtube" -> "com.google.android.youtube"
            "youtubemusic" -> "com.google.android.apps.youtube.music"
            "gaana" -> "com.gaana"
            "wynk" -> "com.bsbportal.music"
            "jiosaavn" -> "com.jio.media.jiobeats"
            else -> SPOTIFY_PACKAGE
        }
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?: return false
        return try {
            context.startActivity(intent)
            true
        } catch (error: Exception) {
            false
        }
    }

    private companion object {
        const val TAG = "AniMusic"
        const val SPOTIFY_PACKAGE = "com.spotify.music"
    }
}
