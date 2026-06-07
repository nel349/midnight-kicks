package com.midnight.kicks

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * Full-screen, centred "stage" shown during the long blockchain waits
 * (`TX_IN_FLIGHT` while a tx proves/submits, `WAITING_FOR_OPPONENT` while we're
 * blocked on the other player). Rendered above Unity by [KicksMatchActivity].
 *
 * **Why it exists.** Two reasons converge here:
 *  1. It **covers Unity's idle "Waiting for match…" IMGUI label** (small,
 *     centred, awkward) so all in-match copy reads as Compose. Once Unity is
 *     re-exported with that label removed this is purely additive.
 *  2. It **masks the wait** — instead of a frozen pitch, a breathing ball +
 *     calm status + elapsed counter make the 30–120 s commit/reveal feel like
 *     part of the tension, not a hang.
 *
 * The compact top banner ([MatchHudOverlay]) suppresses itself for these two
 * modes (see its `visible` gate) so the status isn't shown twice; this stage is
 * the sole presentation while waiting. PICKING is owned by [MatchPickerOverlay],
 * the replay by [MatchReplayOverlay], so this only ever fires between them.
 */
@Composable
fun MatchStageOverlay() {
    val state by MatchHud.state.collectAsState()
    val reconnecting = state.connectionLost
    val waiting = state.mode == MatchHud.Mode.WAITING_FOR_OPPONENT
    // Show during the long waits, OR whenever the indexer is unreachable (the
    // "Reconnecting…" takeover, which can strike in any wait mode).
    val active = reconnecting || waiting || state.mode == MatchHud.Mode.TX_IN_FLIGHT

    // Elapsed counter — ticks while waiting on the opponent OR while
    // reconnecting (so the user sees how long it's been retrying).
    var elapsed by remember(state.sessionEpochMs, reconnecting) { mutableIntStateOf(0) }
    LaunchedEffect(state.sessionEpochMs, state.mode, reconnecting) {
        if (reconnecting || state.mode == MatchHud.Mode.WAITING_FOR_OPPONENT) {
            while (true) {
                delay(1_000L)
                elapsed += 1
            }
        }
    }

    AnimatedVisibility(
        visible = active && (reconnecting || state.primary != null),
        enter = fadeIn(tween(240)),
        exit = fadeOut(tween(180)),
    ) {
        StageContent(
            primary = if (reconnecting) "Reconnecting to the network…" else state.primary.orEmpty(),
            secondary = state.secondary,
            // Only show the determinate bar for a tx in flight — not while
            // reconnecting (the wait is indeterminate) or waiting on the opponent.
            progress = if (state.mode == MatchHud.Mode.TX_IN_FLIGHT && !reconnecting) state.progress else null,
            progressResetKey = state.sessionEpochMs,
            accentArgb = state.accentArgb,
            waiting = waiting && !reconnecting,
            reconnecting = reconnecting,
            elapsedSeconds = elapsed,
        )
    }
}

@Composable
private fun StageContent(
    primary: String,
    secondary: String?,
    progress: Float?,
    progressResetKey: Long,
    accentArgb: Int?,
    waiting: Boolean,
    reconnecting: Boolean,
    elapsedSeconds: Int,
) {
    val accent = when {
        reconnecting -> KicksColors.Danger    // red — network down
        waiting -> KicksColors.Pending         // amber — opponent's move
        // tx in flight — tint per submission (deploy/commit/reveal) when provided.
        else -> accentArgb?.let { Color(it) } ?: KicksColors.Accent
    }

    // Breathing ball: scale + alpha pulse so the screen is alive during the
    // longest waits (proof generation can sit ~15 s with no text change).
    val pulse = rememberInfiniteTransition(label = "stagePulse")
    val scale by pulse.animateFloat(
        initialValue = 0.82f,
        targetValue = 1.12f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse),
        label = "scale",
    )
    val ringAlpha by pulse.animateFloat(
        initialValue = 0.5f,
        targetValue = 0.12f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse),
        label = "ring",
    )

    val clock = "%02d:%02d".format(elapsedSeconds / 60, elapsedSeconds % 60)
    val sub = when {
        reconnecting ->
            if (elapsedSeconds >= 1) "retrying… $clock — resumes automatically" else "retrying — resumes automatically"
        secondary != null -> secondary
        waiting && elapsedSeconds >= 1 -> clock
        else -> null
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            // Dim the idle pitch toward the centre to focus the status + hide
            // Unity's label; lighter at the very top so the goal still peeks.
            .background(
                Brush.verticalGradient(
                    0f to KicksColors.Background.copy(alpha = 0.30f),
                    0.45f to KicksColors.Background.copy(alpha = 0.80f),
                    1f to KicksColors.Background.copy(alpha = 0.88f),
                ),
            )
            // Modal while waiting — don't leak taps to Unity behind us.
            .pointerInput(Unit) { detectTapGestures { } },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // Pulsing ball with a soft accent ring behind it.
            Box(contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier
                        .graphicsLayer { scaleX = scale * 1.6f; scaleY = scale * 1.6f }
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(accent.copy(alpha = ringAlpha)),
                )
                Text("⚽", fontSize = 52.sp, modifier = Modifier.graphicsLayer { scaleX = scale; scaleY = scale })
            }

            Spacer(Modifier.height(28.dp))
            Text(
                text = primary,
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            if (sub != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = sub,
                    color = accent,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 0.5.sp,
                )
            }
            if (progress != null) {
                Spacer(Modifier.height(18.dp))
                StageProgressBar(target = progress, resetKey = progressResetKey, accent = accent)
            }
        }
    }
}

/**
 * Slim determinate progress bar for a tx in flight, fed by the circuit-call
 * stage stream. Clamped monotonic (never retreats on a retry/recovery stage
 * that reports a lower value) and reset per wait via [resetKey]; the fill eases
 * smoothly between stage jumps so it reads as steady forward motion.
 */
@Composable
private fun StageProgressBar(target: Float, resetKey: Long, accent: Color) {
    var peak by remember(resetKey) { mutableFloatStateOf(0f) }
    LaunchedEffect(target, resetKey) {
        val t = target.coerceIn(0f, 1f)
        if (t > peak) peak = t
    }
    val animated by animateFloatAsState(
        targetValue = peak,
        animationSpec = tween(450, easing = FastOutSlowInEasing),
        label = "stageProgress",
    )
    Box(
        modifier = Modifier
            .fillMaxWidth(0.62f)
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(Color.White.copy(alpha = 0.14f)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(animated.coerceIn(0f, 1f))
                .fillMaxHeight()
                .clip(RoundedCornerShape(3.dp))
                .background(accent),
        )
    }
}
