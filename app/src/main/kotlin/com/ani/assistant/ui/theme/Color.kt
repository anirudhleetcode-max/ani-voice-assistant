package com.ani.assistant.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Ani's palette.
 *
 * Built around a single violet that reads as "assistant" without borrowing any existing
 * product's identity, plus a warm amber used only for the listening state — so the one
 * moment the microphone is live is the one moment the UI changes temperature. Everything
 * else is near-neutral, because a voice app is mostly a dark screen with a glowing circle
 * on it and saturated chrome would fight that.
 */
internal object AniPalette {

    // Primary — violet
    val Violet10 = Color(0xFF1B0A4D)
    val Violet20 = Color(0xFF2E1A72)
    val Violet30 = Color(0xFF432C96)
    val Violet40 = Color(0xFF5B4BE1)
    val Violet80 = Color(0xFFC6BEFF)
    val Violet90 = Color(0xFFE5E0FF)

    // Secondary — cool slate, for supporting surfaces
    val Slate10 = Color(0xFF12131A)
    val Slate20 = Color(0xFF1E202B)
    val Slate30 = Color(0xFF2C2F3D)
    val Slate40 = Color(0xFF4A4E62)
    val Slate80 = Color(0xFFC5C7D6)
    val Slate90 = Color(0xFFE3E4EE)

    // Tertiary — amber, reserved for the live-microphone state
    val Amber30 = Color(0xFF7A4A00)
    val Amber40 = Color(0xFFB36A00)
    val Amber80 = Color(0xFFFFCC8A)
    val Amber90 = Color(0xFFFFE3C2)

    // Error
    val Red30 = Color(0xFF8C1D18)
    val Red40 = Color(0xFFB3261E)
    val Red80 = Color(0xFFF2B8B5)
    val Red90 = Color(0xFFF9DEDC)

    // Neutrals
    val Ink = Color(0xFF0E0D13)
    val InkSoft = Color(0xFF17161F)
    val Paper = Color(0xFFF7F6FB)
    val PaperSoft = Color(0xFFFFFFFF)
    val OnInk = Color(0xFFE9E7F2)
    val OnPaper = Color(0xFF1A1826)

    /** The orb's gradient, from centre outwards. */
    val OrbCore = Color(0xFF7C6BFF)
    val OrbMid = Color(0xFF5B4BE1)
    val OrbEdge = Color(0xFF2E1A72)
    val OrbListening = Color(0xFFFFB259)
    val OrbError = Color(0xFFE06A63)
}
