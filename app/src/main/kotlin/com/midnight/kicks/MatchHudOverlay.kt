package com.midnight.kicks

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * In-match status banner rendered ON TOP of Unity's surface by
 * [KicksMatchActivity]. Subscribes to [MatchHud.state] for everything;
 * the orchestrator (MatchManager) owns the source of truth.
 *
 * Why this exists (problem statement): the user submits picks, Unity
 * sits at the same frame for the next 30–120 seconds while the tx is
 * proved + balanced + submitted + indexed + the opponent acts. Without
 * a status surface they see "submitted" and a frozen 3D scene — feels
 * indistinguishable from a hang. The HUD overlay closes that gap with:
 *
 *   - **Primary line** mirrors [MatchState.label] so the user always
 *     sees what the state machine just transitioned to.
 *   - **Secondary line** carries per-tx-stage detail (Proving / Balancing
 *     / Submitting) so the longest single wait — proof generation — has
 *     visible progress.
 *   - **Elapsed timer** ticks during opponent-wait phases so the user
 *     has a sense of how long they've been waiting and the wait feels
 *     like part of the game tension instead of dead time.
 *   - **Accent stripe** color-codes the mode at a glance.
 *
 * Render policy: `Mode.IDLE` hides the banner entirely (clean home
 * screen / clean SD picker). Everything else shows it.
 */
@Composable
fun MatchHudOverlay() {
    val state by MatchHud.state.collectAsState()
    val visible = state.mode != MatchHud.Mode.IDLE && state.primary != null

    // sessionEpochMs bumps on every primary-state change. Resetting
    // the timer on that key gives the user "00:01… 00:02…" starting
    // when the NEW wait begins (not when the match started), which is
    // what they actually care about ("how long since I committed?",
    // not "how long since I tapped CREATE MATCH").
    var elapsedSeconds by remember(state.sessionEpochMs) { mutableIntStateOf(0) }
    LaunchedEffect(state.sessionEpochMs, state.mode) {
        // Only tick when we'd render an elapsed line — saves the
        // recomposition churn from a 1-Hz update when the banner is
        // hidden or in a mode that doesn't show the timer.
        if (state.mode == MatchHud.Mode.WAITING_FOR_OPPONENT) {
            while (true) {
                delay(1_000L)
                elapsedSeconds += 1
            }
        }
    }

    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically { -it } + fadeIn(),
        exit = slideOutVertically { -it } + fadeOut(),
    ) {
        HudBanner(
            primary = state.primary.orEmpty(),
            secondary = state.secondary,
            mode = state.mode,
            elapsedSeconds = elapsedSeconds,
        )
    }
}

@Composable
private fun HudBanner(
    primary: String,
    secondary: String?,
    mode: MatchHud.Mode,
    elapsedSeconds: Int,
) {
    val accent = accentColorFor(mode)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .background(
                color = Color(0xCC0A0A0A),     // 80% opaque near-black
                shape = RoundedCornerShape(12.dp),
            )
            .padding(12.dp),
    ) {
        // Accent stripe + mode indicator + primary text in one row.
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Mode indicator: small filled circle that pulses for active
            // modes (TX_IN_FLIGHT and WAITING_FOR_OPPONENT). Static for
            // DONE / ERROR.
            ModeIndicator(mode = mode, accent = accent)

            Spacer(modifier = Modifier.width(10.dp))

            Text(
                text = primary,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.5.sp,
                modifier = Modifier.alpha(0.95f),
            )
        }

        // Sub-line: tx stage detail when the SDK is mid-call, OR
        // "00:23 — waiting" while we're blocked on the opponent. Only
        // one of these can be set at a time (the state machine has
        // either an in-flight tx or it's waiting, never both).
        val subline = secondary
            ?: elapsedLine(mode, elapsedSeconds)
        if (subline != null) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subline,
                color = accent,
                fontSize = 11.sp,
                letterSpacing = 0.3.sp,
                modifier = Modifier
                    .padding(start = 18.dp)   // align under the primary text
                    .alpha(0.85f),
            )
        }
    }
}

/**
 * Solid dot that pulses for active states. Cheap visual confirmation
 * the UI is alive — even more so during the long Proving wait where
 * the secondary text doesn't change for ~15 s.
 */
@Composable
private fun ModeIndicator(mode: MatchHud.Mode, accent: Color) {
    val pulsing = mode == MatchHud.Mode.TX_IN_FLIGHT ||
        mode == MatchHud.Mode.WAITING_FOR_OPPONENT
    val alpha = if (pulsing) {
        // Slow pulse — fast enough to feel alive, slow enough not to
        // distract from the 3D scene underneath.
        val infinite = rememberInfiniteTransition(label = "hudPulse")
        val animated by infinite.animateFloat(
            initialValue = 0.4f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 900, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "hudPulseAlpha",
        )
        animated
    } else {
        1f
    }
    Box(
        modifier = Modifier
            .size(8.dp)
            .alpha(alpha)
            .background(accent, shape = RoundedCornerShape(50)),
    )
}

/** Accent color per mode — drives both the pulse dot and the sub-line. */
private fun accentColorFor(mode: MatchHud.Mode): Color = when (mode) {
    MatchHud.Mode.PICKING -> Color(0xFFB2DFDB)                   // teal — "your turn"
    MatchHud.Mode.TX_IN_FLIGHT -> Color(0xFF64B5F6)              // light blue
    MatchHud.Mode.WAITING_FOR_OPPONENT -> Color(0xFFFFB74D)      // amber
    MatchHud.Mode.DONE -> Color(0xFF81C784)                      // green
    MatchHud.Mode.ERROR -> KicksColors.Danger                    // red
    MatchHud.Mode.IDLE -> Color.Transparent
}

/**
 * Format the elapsed sub-line, but ONLY for the wait modes. Showing it
 * during TX_IN_FLIGHT would conflict with the stage detail; showing it
 * for DONE / ERROR is pointless.
 *
 * Suppressed for the first second so a fast opponent ("they committed
 * 0.5 s after we did") doesn't briefly flash "00:00 — waiting" before
 * the state advances.
 */
private fun elapsedLine(mode: MatchHud.Mode, elapsedSeconds: Int): String? {
    if (mode != MatchHud.Mode.WAITING_FOR_OPPONENT) return null
    if (elapsedSeconds < 1) return null
    val mm = elapsedSeconds / 60
    val ss = elapsedSeconds % 60
    val time = "%02d:%02d".format(mm, ss)
    return "$time — opponent's move"
}
