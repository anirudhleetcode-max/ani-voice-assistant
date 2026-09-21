package com.ani.assistant.platform.apps

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import com.ani.assistant.core.log.AniLog
import com.ani.assistant.platform.launch.ActivityLauncher
import com.ani.assistant.platform.launch.LaunchOutcome
import com.ani.nlu.intent.KnownApps
import com.ani.nlu.text.Fuzzy
import com.ani.nlu.text.PhoneticKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class InstalledApp(
    val packageName: String,
    val label: String,
    /** Phonetic form of [label], for matching what the user said. */
    val labelKey: String
)

/**
 * Maps "insta", "WA", "whatsapp" and "Instagram" onto something launchable.
 *
 * Two sources, in order: the canonical names the language engine recognises (which map to
 * a short list of known package names), and the device's actual launcher inventory
 * matched phonetically. The second is what makes apps Ani has never heard of work —
 * "rey Zomato open chey" resolves purely from the installed label.
 *
 * The inventory is read through the launcher intent rather than `getInstalledPackages`,
 * so it needs no QUERY_ALL_PACKAGES and sees exactly what the user sees on their home
 * screen.
 */
class AppResolver(
    private val context: Context,
    private val launcher: ActivityLauncher
) {

    @Volatile
    private var cache: List<InstalledApp>? = null

    /** Invalidated when a package is installed or removed, and on every cold start. */
    fun invalidate() {
        cache = null
    }

    suspend fun installedApps(): List<InstalledApp> = withContext(Dispatchers.IO) {
        cache ?: loadInstalledApps().also { cache = it }
    }

    /**
     * Finds the app the user named, or null.
     *
     * @param spoken what the classifier extracted, e.g. "insta" or "google maps"
     */
    suspend fun resolve(spoken: String): InstalledApp? {
        if (spoken.isBlank()) return null
        val apps = installedApps()

        // 1. A canonical name the language engine already knows maps to known packages.
        KnownApps.canonicalOrNull(spoken)?.let { canonical ->
            knownPackagesFor(canonical).firstNotNullOfOrNull { packageName ->
                apps.firstOrNull { it.packageName == packageName }
            }?.let { return it }

            // The canonical name may still match an installed label ("Spotify").
            val canonicalKey = PhoneticKey.of(canonical)
            apps.firstOrNull { it.labelKey == canonicalKey }?.let { return it }
        }

        // 2. Match the spoken words against installed labels.
        val spokenKey = PhoneticKey.of(spoken)
        apps.firstOrNull { it.labelKey == spokenKey }?.let { return it }

        apps.firstOrNull { app ->
            app.label.split(' ', '-', ':').any { PhoneticKey.of(it) == spokenKey }
        }?.let { return it }

        // 3. Tolerant pass, longest label first so "Google Maps" beats "Maps" for "google maps".
        return apps
            .sortedByDescending { it.labelKey.length }
            .firstOrNull { Fuzzy.matches(it.labelKey, spokenKey) }
    }

    /** The launch intent for [app], or null if the app has since been removed. */
    fun launchIntentFor(app: InstalledApp): Intent? =
        context.packageManager.getLaunchIntentForPackage(app.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * Launches a resolved intent through [ActivityLauncher], so a background start is
     * deferred rather than silently dropped.
     */
    fun startApp(intent: Intent, label: String): LaunchOutcome =
        launcher.launch(intent, label)

    fun isInstalled(packageName: String): Boolean = try {
        context.packageManager.getPackageInfo(packageName, 0)
        true
    } catch (error: PackageManager.NameNotFoundException) {
        false
    }

    private fun loadInstalledApps(): List<InstalledApp> = try {
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        context.packageManager
            .queryIntentActivities(launcherIntent, PackageManager.MATCH_ALL)
            .mapNotNull { it.toInstalledApp() }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    } catch (error: Exception) {
        AniLog.e(TAG, "could not list installed apps", error)
        emptyList()
    }

    private fun ResolveInfo.toInstalledApp(): InstalledApp? {
        val packageName = activityInfo?.packageName ?: return null
        val label = loadLabel(context.packageManager).toString().ifBlank { return null }
        return InstalledApp(packageName, label, PhoneticKey.of(label))
    }

    /**
     * Candidate package names for a canonical app.
     *
     * Several of these ship under more than one identifier (WhatsApp Business, the two
     * YouTube Music builds), so this is a list rather than a single name.
     */
    private fun knownPackagesFor(canonical: String): List<String> = when (canonical) {
        "whatsapp" -> listOf("com.whatsapp", "com.whatsapp.w4b")
        "instagram" -> listOf("com.instagram.android")
        "spotify" -> listOf("com.spotify.music")
        "youtube" -> listOf("com.google.android.youtube")
        "youtubemusic" -> listOf("com.google.android.apps.youtube.music")
        "chrome" -> listOf("com.android.chrome")
        "gmail" -> listOf("com.google.android.gm")
        "maps" -> listOf("com.google.android.apps.maps")
        "telegram" -> listOf("org.telegram.messenger")
        "facebook" -> listOf("com.facebook.katana")
        "snapchat" -> listOf("com.snapchat.android")
        "netflix" -> listOf("com.netflix.mediaclient")
        "hotstar" -> listOf("in.startv.hotstar", "in.startv.hotstar.dplus")
        "phonepe" -> listOf("com.phonepe.app")
        "gpay" -> listOf("com.google.android.apps.nbu.paisa.user")
        "paytm" -> listOf("net.one97.paytm")
        "zomato" -> listOf("com.application.zomato")
        "swiggy" -> listOf("in.swiggy.android")
        "uber" -> listOf("com.ubercab")
        "ola" -> listOf("com.olacabs.customer")
        "gaana" -> listOf("com.gaana")
        "wynk" -> listOf("com.bsbportal.music")
        "jiosaavn" -> listOf("com.jio.media.jiobeats")
        "playstore" -> listOf("com.android.vending")
        "contacts" -> listOf("com.google.android.contacts", "com.android.contacts")
        "gallery" -> listOf("com.google.android.apps.photos")
        else -> emptyList()
    }

    private companion object {
        const val TAG = "AniApps"
    }
}
