package com.ani.assistant.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.ani.assistant.ui.theme.AniPalette
import com.ani.assistant.voice.VoiceState

/**
 * The voice orb.
 *
 * It is the only thing on the home screen that moves, and everything it does is tied to
 * something real: the colour is the [VoiceState], the size follows the actual microphone
 * level, and the slow rotation only runs while Ani is thinking. Nothing animates
 * decoratively — an orb that pulses while idle would be both a lie and a battery cost on
 * a screen the user leaves open.
 *
 * When [state] is [VoiceState.IDLE] or [VoiceState.WAITING_FOR_WAKE] the composable is
 * static, so Compose stops recomposing it entirely.
 */
@Composable
fun VoiceOrb(
    state: VoiceState,
    audioLevel: Float,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 220.dp,
    contentDescription: String = "Tap to talk to Ani"
) {
    val isAnimating = state != VoiceState.IDLE && state != VoiceState.WAITING_FOR_WAKE

    // Breathing: only while Ani is doing something.
    val transition = rememberInfiniteTransition(label = "orb")
    val breathe by if (isAnimating) {
        transition.animateFloat(
            initialValue = 0.97f,
            targetValue = 1.03f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1600, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "breathe"
        )
    } else {
        remember { androidx.compose.runtime.mutableFloatStateOf(1f) }
    }

    val sweep by if (state == VoiceState.PROCESSING) {
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 2400, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "sweep"
        )
    } else {
        remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    }

    // The microphone level drives the orb directly, smoothed so it reads as a voice
    // rather than as noise.
    val level by animateFloatAsState(
        targetValue = if (state == VoiceState.LISTENING) audioLevel.coerceIn(0f, 1f) else 0f,
        animationSpec = tween(durationMillis = 120, easing = LinearEasing),
        label = "level"
    )

    val coreColor by animateColor(state)

    Box(
        modifier = modifier
            .size(size)
            .semantics { this.contentDescription = contentDescription }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onTap
            ),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(size).scale(breathe)) {
            val centre = Offset(this.size.width / 2f, this.size.height / 2f)
            val baseRadius = this.size.minDimension / 2f

            // Outer halo, widened by how loudly the user is speaking.
            val haloRadius = baseRadius * (0.72f + 0.28f * level)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        coreColor.copy(alpha = 0.32f),
                        coreColor.copy(alpha = 0.10f),
                        Color.Transparent
                    ),
                    center = centre,
                    radius = baseRadius
                ),
                radius = haloRadius,
                center = centre
            )

            // Core.
            val coreRadius = baseRadius * (0.46f + 0.10f * level)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        lerp(coreColor, Color.White, 0.35f),
                        coreColor,
                        lerp(coreColor, AniPalette.OrbEdge, 0.7f)
                    ),
                    center = centre.copy(y = centre.y - coreRadius * 0.25f),
                    radius = coreRadius * 1.6f
                ),
                radius = coreRadius,
                center = centre
            )

            // Thinking arc.
            if (state == VoiceState.PROCESSING) {
                drawArc(
                    color = coreColor.copy(alpha = 0.85f),
                    startAngle = sweep,
                    sweepAngle = 70f,
                    useCenter = false,
                    topLeft = Offset(
                        centre.x - baseRadius * 0.70f,
                        centre.y - baseRadius * 0.70f
                    ),
                    size = androidx.compose.ui.geometry.Size(
                        baseRadius * 1.40f,
                        baseRadius * 1.40f
                    ),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.dp.toPx())
                )
            }

            // Speaking rings, sized by the same level signal so they stay honest.
            if (state == VoiceState.SPEAKING) {
                for (ring in 1..3) {
                    drawCircle(
                        color = coreColor.copy(alpha = 0.18f / ring),
                        radius = coreRadius + baseRadius * 0.12f * ring * breathe,
                        center = centre,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5.dp.toPx())
                    )
                }
            }
        }
    }
}

@Composable
private fun animateColor(state: VoiceState): androidx.compose.runtime.State<Color> {
    val target = when (state) {
        VoiceState.LISTENING, VoiceState.WAKE_DETECTED -> AniPalette.OrbListening
        VoiceState.ERROR -> AniPalette.OrbError
        VoiceState.SPEAKING -> AniPalette.OrbCore
        VoiceState.PROCESSING -> AniPalette.OrbMid
        VoiceState.WAITING_FOR_WAKE -> MaterialTheme.colorScheme.primary
        VoiceState.IDLE -> MaterialTheme.colorScheme.primary
    }
    return androidx.compose.animation.animateColorAsState(
        targetValue = target,
        animationSpec = tween(durationMillis = 420),
        label = "orbColor"
    )
}
