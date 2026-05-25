package com.midnight.kicks

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * Full-screen replay overlay rendered above Unity by [KicksMatchActivity].
 *
 * Observes [MatchHud.replay]. When non-null, renders a row-by-row
 * scoreboard of the regulation kicks (10 rows for regulation, 2 per SD
 * round for SD kinds). Each row reveals after a short delay so the user
 * watches the score build, mimicking the build-up tension of a real
 * shootout. After the last row, a Continue button appears; tapping it
 * calls [MatchHud.dismissReplay] which clears the overlay — orchestrators
 * awaiting `MatchHud.replay.first { it == null }` (in KicksActivity)
 * wake up and dispatch the next phase (SD picker or winner beat).
 *
 * **Why this exists as a stop-gap:** GAME_DESIGN.md §4 specifies a full
 * Unity cinematic via `UnityBridge.sendReplay(...)`. Until that's
 * built, this Compose overlay closes the most painful UX gap: after
 * regulation reveal, players need to see what happened with the kicks
 * they committed to before the game continues. Without it, "submit
 * picks → suddenly looking at another picker" reads as a restart.
 *
 * Layout choices:
 *  - Full-screen Box with 88%-opaque near-black background — Unity is
 *    visible underneath but heavily dimmed, focusing attention on the
 *    overlay without forcing a hard cut.
 *  - Big top scoreboard that updates as rows reveal — the climbing
 *    score is the payoff.
 *  - One row per regulation pairing (10 rows). Shooter, direction,
 *    keeper direction, outcome.
 *  - Continue button only after all rows have animated in, so users
 *    can't tap-through before they see the result.
 */
@Composable
fun MatchReplayOverlay() {
    val replay by MatchHud.replay.collectAsState()
    val hud by MatchHud.state.collectAsState()
    val show = replay
    AnimatedVisibility(
        visible = show != null,
        enter = fadeIn(animationSpec = tween(durationMillis = 200)),
        exit = fadeOut(animationSpec = tween(durationMillis = 200)),
    ) {
        if (show != null) {
            ReplayBody(hudReplay = show, localRole = hud.role)
        }
    }
}

@Composable
private fun ReplayBody(hudReplay: MatchHud.HudReplay, localRole: Player?) {
    val replay = hudReplay.show
    // How many rows have animated in so far. Driven by a LaunchedEffect
    // that ticks every ROW_REVEAL_INTERVAL_MS. Keyed on the publish
    // timestamp (not the ReplayShow itself) so two structurally
    // identical replays — e.g. an SD round that publishes the same
    // outcome twice through a corner case — restart the animation.
    // Without this, data-class equality would silently re-use the
    // previous animation state and the second show would skip straight
    // to "all revealed".
    var revealedRows by remember(hudReplay.publishedAtMs) { mutableIntStateOf(0) }
    LaunchedEffect(hudReplay.publishedAtMs) {
        // Brief delay before the first row so the user has time to
        // read the header. Then reveal one row at a time.
        delay(INITIAL_PAUSE_MS)
        while (revealedRows < replay.rounds.size) {
            revealedRows += 1
            delay(ROW_REVEAL_INTERVAL_MS)
        }
    }

    // Running score, updated as rows reveal. Only count goals up to
    // revealedRows to mimic a scoreboard climbing in real time. The
    // final score (replay.p1Score/p2Score) is chain-authoritative,
    // not derived from rounds — but the running view is just from
    // RoundResult.result, which encodes the goal/save outcome
    // computed by MatchResult.toRoundResults. Single fold over the
    // revealed prefix tallies both sides in one pass.
    var runningP1 = 0
    var runningP2 = 0
    for (idx in 0 until revealedRows) {
        val r = replay.rounds[idx]
        if (r.result == "goal") {
            if (r.shooter == "P1") runningP1 += 1 else runningP2 += 1
        }
    }

    val allRevealed = revealedRows >= replay.rounds.size

    Box(
        modifier = Modifier
            .fillMaxSize()
            // Absorb every touch while the replay is showing — without
            // this, taps OUTSIDE the Continue button (i.e. the dimmed
            // backdrop) fall through to Unity's surface below and can
            // accidentally trigger Unity input the user can't see.
            // `detectTapGestures {}` with an empty body consumes the
            // event without doing anything. The Continue button's own
            // Modifier.clickable wins over this in its own bounds.
            .pointerInput(Unit) { detectTapGestures {} }
            .background(Color(0xE6050505))   // ~90% opaque
            .statusBarsPadding()
            .padding(20.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Header(replay.kind, replay.sdRoundNumber)
            Spacer(modifier = Modifier.height(8.dp))
            Scoreboard(p1Score = runningP1, p2Score = runningP2, localRole = localRole)
            Spacer(modifier = Modifier.height(12.dp))
            // Round rows. Each animates in via AnimatedVisibility based
            // on whether its index has been reached by revealedRows.
            replay.rounds.forEachIndexed { idx, round ->
                AnimatedVisibility(
                    visible = idx < revealedRows,
                    enter = slideInVertically(
                        initialOffsetY = { it / 2 },
                        animationSpec = tween(durationMillis = 250),
                    ) + fadeIn(animationSpec = tween(durationMillis = 250)),
                ) {
                    RoundRow(round = round)
                }
            }
            Spacer(modifier = Modifier.weight(1f))
            if (allRevealed) {
                ContinueButton(
                    finalP1Score = replay.p1Score,
                    finalP2Score = replay.p2Score,
                    kind = replay.kind,
                )
            }
        }
    }
}

@Composable
private fun Header(kind: MatchHud.ReplayKind, sdRoundNumber: Int?) {
    val (title, subtitle) = when (kind) {
        MatchHud.ReplayKind.REGULATION -> "REGULATION" to "5 vs 5 — let's see how it played"
        MatchHud.ReplayKind.SUDDEN_DEATH_ROUND ->
            "SUDDEN DEATH" to "Round ${sdRoundNumber ?: "?"} — one shot each"
    }
    Text(
        text = title,
        color = Color(0xFFFFB74D),
        fontSize = 20.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 4.sp,
    )
    Text(
        text = subtitle,
        color = Color.White.copy(alpha = 0.55f),
        fontSize = 12.sp,
        letterSpacing = 1.sp,
    )
}

@Composable
private fun Scoreboard(p1Score: Int, p2Score: Int, localRole: Player?) {
    // Render the user's column with the "YOU / X" prefix so they can
    // tell at a glance which side they are. [localRole] = null in PvAI.
    val isP2 = localRole == Player.P2
    val p1Label = if (isP2) "P1" else "YOU / P1"
    val p2Label = if (isP2) "YOU / P2" else "P2"
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ScoreCell(label = p1Label, score = p1Score, accent = Color(0xFF64B5F6))
        Text(
            text = "—",
            color = Color.White.copy(alpha = 0.3f),
            fontSize = 24.sp,
        )
        ScoreCell(label = p2Label, score = p2Score, accent = KicksColors.Danger)
    }
}

@Composable
private fun ScoreCell(label: String, score: Int, accent: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            color = accent,
            fontSize = 10.sp,
            letterSpacing = 2.sp,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = score.toString(),
            color = Color.White,
            fontSize = 42.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun RoundRow(round: RoundResult) {
    val isGoal = round.result == "goal"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = Color.White.copy(alpha = 0.04f),
                shape = RoundedCornerShape(8.dp),
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Round number
        Text(
            text = "${round.round}",
            color = Color.White.copy(alpha = 0.4f),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.width(24.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        // Shooter chip
        Text(
            text = "${round.shooter} ${dirArrow(round.shootDir)}",
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        // Keeper move
        Text(
            text = "vs ${dirArrow(round.keepDir)}",
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 13.sp,
            modifier = Modifier.weight(1f),
        )
        // Outcome pill
        Box(
            modifier = Modifier
                .background(
                    color = if (isGoal) Color(0xFF2E7D32) else Color(0xFF4E342E),
                    shape = RoundedCornerShape(50),
                )
                .padding(horizontal = 12.dp, vertical = 4.dp),
        ) {
            Text(
                text = if (isGoal) "GOAL" else "SAVE",
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
            )
        }
    }
}

@Composable
private fun ContinueButton(
    finalP1Score: Int,
    finalP2Score: Int,
    kind: MatchHud.ReplayKind,
) {
    // Decide the continuation copy based on what's next. For regulation,
    // a tie means "headed to SD"; decisive means "winner ahead." For SD
    // rounds, similar logic. Same button affordance either way —
    // tap-to-dismiss fires MatchHud.dismissReplay() and the orchestrator
    // awaiting `replayDismissed` wakes up.
    val nextCopy = when {
        finalP1Score == finalP2Score && kind == MatchHud.ReplayKind.REGULATION ->
            "TIED $finalP1Score-$finalP2Score — TAP FOR SUDDEN DEATH"
        finalP1Score == finalP2Score && kind == MatchHud.ReplayKind.SUDDEN_DEATH_ROUND ->
            "$finalP1Score-$finalP2Score — TAP FOR NEXT ROUND"
        else ->
            "FINAL $finalP1Score-$finalP2Score — TAP TO CONTINUE"
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { MatchHud.dismissReplay() }
            .background(
                color = Color(0xFFFFB74D),
                shape = RoundedCornerShape(12.dp),
            )
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = nextCopy,
            color = Color(0xFF222222),
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.5.sp,
            textAlign = TextAlign.Center,
        )
    }
}

/** L/C/R direction code → arrow glyph for compact row display. */
private fun dirArrow(d: Int): String = when (d) {
    0 -> "←"   // LEFT
    1 -> "↑"   // CENTER
    2 -> "→"   // RIGHT
    else -> "?"
}

/** Pause before the first row reveals — lets the user read the header. */
private const val INITIAL_PAUSE_MS: Long = 600L

/** Time between consecutive row reveals — fast enough to keep momentum,
 * slow enough to read each outcome. 10 rounds * 350ms = ~3.5s total. */
private const val ROW_REVEAL_INTERVAL_MS: Long = 350L
