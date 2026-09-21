package com.ani.assistant.voice.wake

import android.content.Context
import com.ani.assistant.core.log.AniLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.zip.ZipInputStream

/** Where the Vosk acoustic model is, if anywhere. */
sealed interface VoskModelState {
    data class Ready(val path: String) : VoskModelState
    data object NotInstalled : VoskModelState
    data class Installing(val percent: Int) : VoskModelState
    data class Failed(val reason: String) : VoskModelState
}

/**
 * Manages the Vosk acoustic model on disk.
 *
 * Vosk needs a model — around 40 MB for the small Indian-English one — and there are two
 * honest ways to get it onto the phone:
 *
 *  - **Bundled in assets.** Zero-config for the user, but it adds 40 MB to the APK.
 *  - **Downloaded on first use.** Keeps the APK small; needs one network fetch.
 *
 * Both are supported, assets first. What is *not* supported is pretending the wake word
 * works before the model exists: with no model, [state] reports [VoskModelState.NotInstalled],
 * the engine reports `NeedsModel`, and Ani says so rather than silently never waking.
 *
 * The small Indian-English model is the right default here. Vosk has no Telugu model, but
 * "Rey" is a short syllable that an Indian-English acoustic model handles well, and the
 * grammar restriction below means it is only ever choosing between the wake phrase and
 * "not the wake phrase".
 */
class VoskModelStore(
    private val context: Context,
    private val client: OkHttpClient = OkHttpClient()
) {

    private val _state = MutableStateFlow<VoskModelState>(VoskModelState.NotInstalled)
    val state: StateFlow<VoskModelState> = _state.asStateFlow()

    /** Where an installed model lives. */
    private val modelDirectory: File
        get() = File(context.filesDir, MODEL_DIRECTORY_NAME)

    /** Cheap synchronous check, safe to call from Diagnostics. */
    fun isInstalled(): Boolean = modelDirectory.resolve(MODEL_MARKER_FILE).exists()

    fun installedPath(): String? = if (isInstalled()) modelDirectory.absolutePath else null

    fun refreshState() {
        _state.value = installedPath()
            ?.let { VoskModelState.Ready(it) }
            ?: VoskModelState.NotInstalled
    }

    /**
     * Makes the model available, unpacking from assets if it was bundled.
     *
     * @return the model path, or null when there is nothing to unpack.
     */
    suspend fun installFromAssetsIfPresent(): String? = withContext(Dispatchers.IO) {
        installedPath()?.let { return@withContext it }

        val assetNames = runCatching { context.assets.list("")?.toList().orEmpty() }.getOrDefault(emptyList())
        if (ASSET_MODEL_DIRECTORY !in assetNames) return@withContext null

        _state.value = VoskModelState.Installing(0)
        try {
            copyAssetDirectory(ASSET_MODEL_DIRECTORY, modelDirectory)
            modelDirectory.resolve(MODEL_MARKER_FILE).takeIf { it.exists() }
                ?: error("unpacked model is missing $MODEL_MARKER_FILE")
            val path = modelDirectory.absolutePath
            _state.value = VoskModelState.Ready(path)
            AniLog.i(TAG, "unpacked wake model from assets")
            path
        } catch (error: Exception) {
            AniLog.e(TAG, "could not unpack model from assets", error)
            modelDirectory.deleteRecursively()
            _state.value = VoskModelState.Failed("Could not unpack the bundled model.")
            null
        }
    }

    /**
     * Downloads and extracts the model.
     *
     * Extraction is strict about entry paths: a zip entry that escapes the target
     * directory is rejected rather than written. The archive comes from a URL the user can
     * change in settings, so treating it as trusted input would be a path-traversal bug
     * waiting to happen.
     */
    suspend fun download(url: String = DEFAULT_MODEL_URL): Boolean = withContext(Dispatchers.IO) {
        if (isInstalled()) return@withContext true

        _state.value = VoskModelState.Installing(0)
        val staging = File(context.cacheDir, "vosk-download").apply {
            deleteRecursively()
            mkdirs()
        }

        try {
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    _state.value = VoskModelState.Failed("Download failed (${response.code}).")
                    return@withContext false
                }
                val body = response.body ?: run {
                    _state.value = VoskModelState.Failed("Download returned nothing.")
                    return@withContext false
                }

                ZipInputStream(body.byteStream().buffered()).use { zip ->
                    var entry = zip.nextEntry
                    var extracted = 0
                    while (entry != null) {
                        val target = staging.resolve(entry.name).canonicalFile
                        if (!target.path.startsWith(staging.canonicalFile.path)) {
                            error("zip entry escapes the target directory")
                        }
                        if (entry.isDirectory) {
                            target.mkdirs()
                        } else {
                            target.parentFile?.mkdirs()
                            target.outputStream().use { zip.copyTo(it) }
                        }
                        extracted++
                        // The archive has no reliable total, so report coarse progress.
                        _state.value = VoskModelState.Installing((extracted * 100 / EXPECTED_ENTRIES).coerceAtMost(95))
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
            }

            // Archives wrap the model in a single top-level folder; find the real root.
            val root = findModelRoot(staging)
                ?: error("archive did not contain a Vosk model")

            modelDirectory.deleteRecursively()
            modelDirectory.parentFile?.mkdirs()
            if (!root.renameTo(modelDirectory)) {
                root.copyRecursively(modelDirectory, overwrite = true)
            }

            val path = modelDirectory.absolutePath
            _state.value = VoskModelState.Ready(path)
            AniLog.i(TAG, "wake model installed")
            true
        } catch (error: Exception) {
            AniLog.e(TAG, "model download failed", error)
            modelDirectory.deleteRecursively()
            _state.value = VoskModelState.Failed("Could not install the model.")
            false
        } finally {
            staging.deleteRecursively()
        }
    }

    /** Frees the ~40 MB again. */
    fun remove() {
        modelDirectory.deleteRecursively()
        _state.value = VoskModelState.NotInstalled
    }

    fun installedSizeBytes(): Long =
        modelDirectory.walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    private fun findModelRoot(directory: File): File? {
        if (directory.resolve(MODEL_MARKER_FILE).exists()) return directory
        return directory.listFiles()
            ?.filter { it.isDirectory }
            ?.firstNotNullOfOrNull { findModelRoot(it) }
    }

    private fun copyAssetDirectory(assetPath: String, target: File) {
        val children = context.assets.list(assetPath).orEmpty()
        if (children.isEmpty()) {
            target.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input ->
                target.outputStream().use { input.copyTo(it) }
            }
            return
        }
        target.mkdirs()
        for (child in children) {
            copyAssetDirectory("$assetPath/$child", target.resolve(child))
        }
    }

    companion object {
        private const val TAG = "AniVoskModel"

        /** Folder name under `app/src/main/assets/` if the model is bundled. */
        const val ASSET_MODEL_DIRECTORY = "vosk-model"

        private const val MODEL_DIRECTORY_NAME = "vosk-model"

        /** Every Vosk model has this; its presence is how we know an install completed. */
        private const val MODEL_MARKER_FILE = "am/final.mdl"

        private const val EXPECTED_ENTRIES = 40

        /**
         * Small Indian-English model, roughly 40 MB.
         *
         * Indian English rather than US: the wake phrase is spoken by a Telugu speaker,
         * and the acoustic model that has heard Indian speakers is the one that will
         * recognise "Rey" from one.
         */
        const val DEFAULT_MODEL_URL =
            "https://alphacephei.com/vosk/models/vosk-model-small-en-in-0.4.zip"

        const val APPROXIMATE_SIZE_MEGABYTES = 40
    }
}
