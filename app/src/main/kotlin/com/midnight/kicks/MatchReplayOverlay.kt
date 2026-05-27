package com.midnight.kicks

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.first

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
fun MatchReplayOverlay(onRematch: () -> Unit, onMenu: () -> Unit) {
    val replay by MatchHud.replay.collectAsState()
    val hud by MatchHud.state.collectAsState()
    val show = replay
    AnimatedVisibility(
        visible = show != null,
        enter = fadeIn(animationSpec = tween(durationMillis = 200)),
        exit = fadeOut(animationSpec = tween(durationMillis = 200)),
    ) {
        if (show != null) {
            ReplayBody(hudReplay = show, localRole = hud.role, onRematch = onRematch, onMenu = onMenu)
        }
    }
}

@Composable
private fun ReplayBody(
    hudReplay: MatchHud.HudReplay,
    localRole: Player?,
    onRematch: () -> Unit,
    onMenu: () -> Unit,
) {
    val replay = hudReplay.show
    // Two phases, gated on the ACTUAL cinematic finishing (not a guessed
    // timer): while the kicks play, draw nothing over them; once Unity reports
    // the cinematic done, bring up the result HUD. Keyed on the publish
    // timestamp so each replay (even two with identical outcomes) restarts
    // clean rather than re-using the previous show's "done" state.
    var cinematicDone by remember(hudReplay.publishedAtMs) { mutableStateOf(false) }
    LaunchedEffect(hudReplay.publishedAtMs) {
        cinematicDone = false
        val firedAt = System.currentTimeMillis()
        // Kick off the 3D cinematic the instant the replay appears. The kicks
        // are the whole show here — nothing is drawn on top of them.
        val winner = when {
            replay.p1Score > replay.p2Score -> "P1"
            replay.p2Score > replay.p1Score -> "P2"
            else -> null
        }
        UnityBridge.playReplayCinematic(replay.rounds, replay.p1Score, replay.p2Score, winner)
        // Hold the HUD back until Unity says the kicks are done. Gating on the
        // real completion (not an estimated duration) is what guarantees the
        // result never lands on top of a kick still in flight.
        UnityBridge.replayCinematicDoneAt.first { it >= firedAt }
        cinematicDone = true
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (!cinematicDone) {
            // Cinematic playing: draw NOTHING. Just swallow stray taps so they
            // don't poke the 3D scene — the kicks are fully visible underneath.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) { detectTapGestures {} },
            )
        }
        // Result HUD — fades in the moment the kicks finish.
        AnimatedVisibility(
            visible = cinematicDone,
            enter = fadeIn(animationSpec = tween(durationMillis = 250)),
        ) {
            ResultHud(replay = replay, localRole = localRole, onRematch = onRematch, onMenu = onMenu)
        }
    }
}

/**
 * The post-cinematic result screen, shown after the kicks have played out. Owns
 * the screen with a near-opaque scrim so Unity's idle label can't bleed through.
 *
 * Two faces, decided by the score:
 *  - **Decisive** (`p1Score != p2Score`) → the match is OVER (regulation decided,
 *    or an SD round broke the tie): the celebration end screen — verdict, score,
 *    shoot-out recap, and REMATCH / MENU. It does NOT dismiss the replay; the two
 *    buttons are the only ways out (see KicksActivity, which also suppresses the
 *    auto-advance for a decisive replay so this screen stays put).
 *  - **Tied** → another beat is coming (sudden death, or the next SD round): the
 *    intermediate "tap to continue", which dismisses the replay so the
 *    orchestrator advances.
 */
@Composable
private fun ResultHud(
    replay: MatchHud.ReplayShow,
    localRole: Player?,
    onRematch: () -> Unit,
    onMenu: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) { detectTapGestures {} }
            .background(
                Brush.verticalGradient(
                    0f to KicksColors.Background.copy(alpha = 0.92f),
                    1f to KicksColors.Background.copy(alpha = 0.96f),
                ),
            )
            .statusBarsPadding()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (replay.p1Score != replay.p2Score) {
            EndScreen(replay = replay, localRole = localRole, onRematch = onRematch, onMenu = onMenu)
        } else {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Header(replay.kind, replay.sdRoundNumber)
                Scoreboard(p1Score = replay.p1Score, p2Score = replay.p2Score, localRole = localRole)
                IntermediateContinue(kind = replay.kind, p1Score = replay.p1Score, p2Score = replay.p2Score)
            }
        }
    }
}

/**
 * Match-over celebration: verdict + final score + the shoot-out recap + the two
 * exits. Outcome is framed from THIS device ([localRole]; null = PvAI, human is
 * P1), so each player sees their own win/loss.
 */
@Composable
private fun EndScreen(
    replay: MatchHud.ReplayShow,
    localRole: Player?,
    onRematch: () -> Unit,
    onMenu: () -> Unit,
) {
    val isP2 = localRole == Player.P2
    val mine = if (isP2) replay.p2Score else replay.p1Score
    val theirs = if (isP2) replay.p1Score else replay.p2Score
    val won = mine > theirs
    val opponentName = if (localRole == null) "AI" else "OPPONENT"

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text(text = if (won) "🏆" else "💔", fontSize = 56.sp)
        Text(
            text = if (won) "YOU WIN!" else "$opponentName WINS",
            color = if (won) KicksColors.Success else KicksColors.Danger,
            fontSize = 34.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 2.sp,
        )
        Text(
            text = "$mine – $theirs",
            color = Color.White,
            fontSize = 52.sp,
            fontWeight = FontWeight.Bold,
        )
        ShootoutRecap(rounds = replay.rounds, localRole = localRole, opponentName = opponentName)
        EndActions(onRematch = onRematch, onMenu = onMenu)
    }
}

/**
 * Classic penalty-board recap: one row of goal/save marks per player, in kicking
 * order, with regulation and sudden-death visually split. ● = goal, ○ = saved.
 */
@Composable
private fun ShootoutRecap(rounds: List<RoundResult>, localRole: Player?, opponentName: String) {
    val isP2 = localRole == Player.P2
    val mineTag = if (isP2) "P2" else "P1"
    val theirsTag = if (isP2) "P1" else "P2"
    // Each player's kicks in order; first 5 are regulation, the rest sudden death.
    val mineMarks = rounds.filter { it.shooter == mineTag }.map { it.result == "goal" }
    val theirMarks = rounds.filter { it.shooter == theirsTag }.map { it.result == "goal" }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RecapRow(label = "YOU", marks = mineMarks)
        RecapRow(label = opponentName, marks = theirMarks)
        Text(
            text = "● goal   ○ saved",
            color = Color.White.copy(alpha = 0.45f),
            fontSize = 11.sp,
            letterSpacing = 1.sp,
        )
    }
}

private const val REGULATION_KICKS_PER_PLAYER = 5

@Composable
private fun RecapRow(label: String, marks: List<Boolean>) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
            modifier = Modifier.width(34.dp),
        )
        marks.forEachIndexed { idx, goal ->
            // Visually separate regulation from sudden death.
            if (idx == REGULATION_KICKS_PER_PLAYER) {
                Text(text = "|", color = Color.White.copy(alpha = 0.3f), fontSize = 16.sp)
            }
            Text(
                text = if (goal) "●" else "○",
                color = if (goal) KicksColors.Success else Color.White.copy(alpha = 0.4f),
                fontSize = 18.sp,
            )
        }
    }
}

@Composable
private fun EndActions(onRematch: () -> Unit, onMenu: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        EndButton(text = "REMATCH", fill = KicksColors.Picking, textColor = KicksColors.SurfaceMuted, onClick = onRematch)
        EndButton(text = "MENU", fill = KicksColors.SurfaceMuted, textColor = Color.White, onClick = onMenu)
    }
}

@Composable
private fun EndButton(text: String, fill: Color, textColor: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clickable { onClick() }
            .background(color = fill, shape = RoundedCornerShape(12.dp))
            .padding(horizontal = 28.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = textColor,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.5.sp,
        )
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
        color = KicksColors.Pending,
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
        ScoreCell(label = p1Label, score = p1Score, accent = KicksColors.Accent)
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
private fun IntermediateContinue(
    kind: MatchHud.ReplayKind,
    p1Score: Int,
    p2Score: Int,
) {
    // Only reached when the score is TIED — there's another beat coming, so this
    // dismisses the replay (MatchHud.dismissReplay → the orchestrator awaiting
    // `replay == null` advances). A decisive score never lands here; that's the
    // EndScreen. Regulation tie → sudden death; SD-round tie → next round.
    val nextCopy = when (kind) {
        MatchHud.ReplayKind.REGULATION -> "TIED $p1Score-$p2Score — TAP FOR SUDDEN DEATH"
        MatchHud.ReplayKind.SUDDEN_DEATH_ROUND -> "$p1Score-$p2Score — TAP FOR NEXT ROUND"
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { MatchHud.dismissReplay() }
            .background(
                color = KicksColors.Pending,
                shape = RoundedCornerShape(12.dp),
            )
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = nextCopy,
            color = KicksColors.SurfaceMuted,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.5.sp,
            textAlign = TextAlign.Center,
        )
    }
}
