@file:Suppress("UNUSED_PARAMETER", "unused")

package androidx.compose.material3

import android.content.Context

// Dynamic colour is Android 12+ only and ships only in the Android material3 artifact.
fun dynamicDarkColorScheme(context: Context): ColorScheme = darkColorScheme()

fun dynamicLightColorScheme(context: Context): ColorScheme = lightColorScheme()
