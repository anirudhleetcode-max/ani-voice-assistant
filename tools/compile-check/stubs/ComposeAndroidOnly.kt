// Compose APIs that exist only in the Android artifacts, not in Compose Multiplatform's
// desktop targets. Declared in their real packages so app code compiles unchanged.
@file:Suppress("UNUSED_PARAMETER", "unused")

package androidx.compose.ui.platform

import android.content.Context
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf

val LocalContext: ProvidableCompositionLocal<Context> =
    staticCompositionLocalOf { error("LocalContext is Android-only") }
