package com.ani.assistant.voice.wake

import android.content.Context
import ai.picovoice.porcupine.PorcupineManager
import com.ani.assistant.core.log.AniLog
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.io.File

/**
 * Wake-word detection with Picovoice Porcupine. **Optional, and it costs money.**
 *
 * Porcupine is the better engine on the two axes that matter for an always-on listener:
 * it uses markedly less CPU than a speech model, and its false-accept rate is lower. If
 * you have a licence, use it.
 *
 * Two things stop it being the default:
 *
 *  1. **Picovoice discontinued its free tier on 30 June 2026**, replacing it with a
 *     seven-day trial. A personal assistant that stops waking up when a trial lapses is
 *     not a personal assistant, so the free, offline [VoskWakeWordEngine] is the default
 *     and this is opt-in.
 *  2. **It cannot detect an arbitrary phrase.** Porcupine only spots phrases it has a
 *     trained `.ppn` model for. "Rey" is not one of the fourteen built-in keywords, so it
 *     needs a custom keyword trained in the Picovoice Console. That is why
 *     [supportsCustomPhrase] is false: the Settings screen greys the phrase field out
 *     rather than accepting text this engine would ignore.
 *
 * **Setup** (see docs/WAKE_WORD.md):
 *  1. Get an AccessKey from console.picovoice.ai on a current plan.
 *  2. Train a custom keyword for "Rey", Android platform, and download the `.ppn`.
 *  3. Put the file at `app/src/main/assets/porcupine/rey_android.ppn`.
 *  4. Enter the AccessKey in Settings. It is stored in `EncryptedSharedPreferences` and
 *     is never written to the repository, to logs, or to any build output.
 */
class PorcupineWakeWordEngine(
    private val context: Context,
    private val accessKeyProvider: () -> String?,
    private val sensitivityProvider: () -> WakeSensitivity,
    private val hasMicrophonePermission: () -> Boolean
) : WakeWordEngine {

    override val id = WakeWordEngineId.PORCUPINE
    override val displayName = "Picovoice Porcupine"

    /** Only phrases with a trained keyword file, which is Porcupine's actual constraint. */
    override val supportsCustomPhrase = false

    override val costDescription: String =
        "A purpose-built hotword engine. Lowest battery use of the three, nothing leaves " +
            "the phone. Needs a paid Picovoice AccessKey and a trained keyword file for " +
            "your phrase — Picovoice ended its free tier on 30 June 2026."

    @Volatile
    private var manager: PorcupineManager? = null

    @Volatile
    private var paused: Boolean = false

    override suspend fun availability(): WakeEngineAvailability = when {
        !hasMicrophonePermission() ->
            WakeEngineAvailability.Blocked("Microphone permission is not granted.")

        accessKeyProvider().isNullOrBlank() ->
            WakeEngineAvailability.NeedsCredentials(
                "A Picovoice AccessKey. Add it in Settings; it is stored encrypted on this phone."
            )

        keywordFile() == null ->
            WakeEngineAvailability.NeedsCredentials(
                "A trained keyword file for your wake phrase at " +
                    "assets/$KEYWORD_ASSET_DIRECTORY/$KEYWORD_ASSET_NAME."
            )

        else -> WakeEngineAvailability.Ready
    }

    override fun detections(): Flow<WakeDetection> = callbackFlow {
        val accessKey = accessKeyProvider()
        val keyword = keywordFile()

        if (accessKey.isNullOrBlank() || keyword == null || !hasMicrophonePermission()) {
            AniLog.w(
                TAG,
                "porcupine not configured",
                "hasKey" to !accessKey.isNullOrBlank(),
                "hasKeyword" to (keyword != null)
            )
            close()
            return@callbackFlow
        }

        var started: PorcupineManager? = null
        try {
            started = PorcupineManager.Builder()
                .setAccessKey(accessKey)
                .setKeywordPath(keyword.absolutePath)
                .setSensitivity(sensitivityProvider().porcupineSensitivity)
                .setErrorCallback { error ->
                    // Porcupine reports an expired or invalid key here rather than at build time.
                    AniLog.w(TAG, "porcupine runtime error", "type" to error.javaClass.simpleName)
                }
                .build(context) { _ ->
                    if (!paused) {
                        trySend(
                            WakeDetection(
                                phrase = KEYWORD_LABEL,
                                confidence = 1.0,
                                trailingText = null
                            )
                        )
                    }
                }

            started.start()
            manager = started
            AniLog.i(TAG, "porcupine listening")
        } catch (error: Exception) {
            AniLog.e(TAG, "could not start porcupine", error)
            runCatching { started?.delete() }
            manager = null
            close()
            return@callbackFlow
        }

        awaitClose {
            manager = null
            runCatching { started.stop() }
            runCatching { started.delete() }
            AniLog.i(TAG, "porcupine stopped")
        }
    }

    /**
     * Porcupine has no pause, so pausing stops the engine and resuming restarts it.
     *
     * Restarting reacquires the microphone, which takes a moment — acceptable, because
     * the only caller is "Ani is speaking right now", and Ani must not hear itself.
     */
    override fun setPaused(paused: Boolean) {
        this.paused = paused
        val current = manager ?: return
        runCatching {
            if (paused) current.stop() else current.start()
        }.onFailure {
            AniLog.w(TAG, "could not toggle porcupine pause", "paused" to paused)
        }
    }

    override fun release() {
        runCatching { manager?.stop() }
        runCatching { manager?.delete() }
        manager = null
    }

    /**
     * Stops Porcupine and confirms it.
     *
     * `PorcupineManager.stop()` is synchronous — it joins its own audio thread before
     * returning — so unlike the Vosk and platform engines there is nothing to wait for
     * here beyond the call itself. The confirmation is that `stop()` and `delete()` both
     * returned without throwing; there is no state to query afterwards.
     */
    override suspend fun releaseAndAwait(timeoutMillis: Long): Boolean {
        val stopped = runCatching { manager?.stop() }.isSuccess
        val deleted = runCatching { manager?.delete() }.isSuccess
        manager = null
        val released = stopped && deleted
        AniLog.i(TAG, "[MIC] porcupine release", "released" to released)
        return released
    }

    /**
     * Copies the bundled keyword out of assets, because Porcupine needs a real file path.
     * Returns null when no keyword file was bundled.
     */
    private fun keywordFile(): File? {
        val target = File(context.filesDir, "$KEYWORD_ASSET_DIRECTORY/$KEYWORD_ASSET_NAME")
        if (target.exists() && target.length() > 0) return target

        return try {
            val available = context.assets.list(KEYWORD_ASSET_DIRECTORY).orEmpty()
            if (KEYWORD_ASSET_NAME !in available) return null

            target.parentFile?.mkdirs()
            context.assets.open("$KEYWORD_ASSET_DIRECTORY/$KEYWORD_ASSET_NAME").use { input ->
                target.outputStream().use { input.copyTo(it) }
            }
            target
        } catch (error: Exception) {
            AniLog.w(TAG, "could not stage keyword file", "type" to error.javaClass.simpleName)
            null
        }
    }

    companion object {
        private const val TAG = "AniPorcupine"
        private const val KEYWORD_LABEL = "rey"

        const val KEYWORD_ASSET_DIRECTORY = "porcupine"
        const val KEYWORD_ASSET_NAME = "rey_android.ppn"
    }
}
