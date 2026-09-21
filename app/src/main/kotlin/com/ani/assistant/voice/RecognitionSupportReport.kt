package com.ani.assistant.voice

/**
 * What the device's recogniser actually says it supports.
 *
 * Read rather than assumed. The project had been sending `en-IN` for Tanglish on the
 * reasoning that it transcribes Telugu words into Latin script — which is sound
 * reasoning, and completely untested against what this particular phone will do with
 * `te-IN`, or whether either language is installed at all.
 *
 * `SpeechRecognizer.checkRecognitionSupport` (API 33) is the platform's own answer, and
 * it distinguishes three states that matter here: a language that is supported and
 * installed, one that is supported but needs downloading, and one that is not supported.
 * Treating the middle case as the last is how you conclude a device cannot do Telugu when
 * it simply has not fetched it yet.
 */
data class RecognitionSupportReport(
    val queried: Boolean,
    val onDeviceAvailable: Boolean,
    val installedOnDevice: List<String> = emptyList(),
    val supportedOnDevice: List<String> = emptyList(),
    val pendingDownload: List<String> = emptyList(),
    /** Null when the query succeeded. */
    val error: String? = null
) {
    fun isInstalled(localeTag: String): Boolean =
        installedOnDevice.any { it.equals(localeTag, ignoreCase = true) }

    fun isSupported(localeTag: String): Boolean =
        isInstalled(localeTag) || supportedOnDevice.any { it.equals(localeTag, ignoreCase = true) }

    /** One line for the log and the benchmark screen. */
    fun describe(): String = when {
        !queried -> "ON_DEVICE_SUPPORT_NOT_QUERIED (needs Android 13+)"
        error != null -> "ON_DEVICE_SUPPORT_ERROR $error"
        !onDeviceAvailable -> "ON_DEVICE_UNAVAILABLE"
        else -> "installed=[${installedOnDevice.joinToString()}] " +
            "supported=[${supportedOnDevice.joinToString()}] " +
            "pending=[${pendingDownload.joinToString()}]"
    }

    companion object {
        val UNAVAILABLE = RecognitionSupportReport(queried = false, onDeviceAvailable = false)
    }
}
