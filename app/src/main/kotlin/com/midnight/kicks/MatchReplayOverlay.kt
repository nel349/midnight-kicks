package com.midnight.kicks

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
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
    var firedAtMs by remember(hudReplay.publishedAtMs) { mutableLongStateOf(0L) }
    LaunchedEffect(hudReplay.publishedAtMs) {
        cinematicDone = false
        firedAtMs = System.currentTimeMillis()
        // Kick off the 3D cinematic the instant the replay appears. The kicks
        // are the whole show — only a small live score chip + a brief per-kick
        // GOAL!/SAVED! flash sit over them (see below), never the result itself.
        val winner = when {
            replay.p1Score > replay.p2Score -> "P1"
            replay.p2Score > replay.p1Score -> "P2"
            else -> null
        }
        UnityBridge.playReplayCinematic(
            replay.rounds, replay.p1Score, replay.p2Score, winner,
            // Which side this device is, so Unity dresses the shooter in the
            // local vs opponent kit per round. PvAI (null) = you're P1.
            localSide = (localRole ?: Player.P1).name,
        )
        // Hold the result HUD back until Unity says the kicks are done. Gating on
        // the real completion (not an estimated duration) is what guarantees the
        // result never lands on top of a kick still in flight.
        UnityBridge.replayCinematicDoneAt.first { it >= firedAtMs }
        cinematicDone = true
    }

    // How many kicks have landed so far, driven by Unity's per-kick events
    // (correlated against firedAtMs so a stale kick from a prior replay can't
    // bleed in). Once the cinematic is done, all of them are in.
    val kick by UnityBridge.replayKick.collectAsState()
    val revealedKicks = when {
        cinematicDone -> replay.rounds.size
        firedAtMs > 0L && kick.atMs >= firedAtMs -> (kick.index + 1).coerceIn(0, replay.rounds.size)
        else -> 0
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (!cinematicDone) {
            // Cinematic playing: the kicks stay fully visible. Over them sit only
            // a corner score chip + a transient per-kick flash — the suspense
            // beat — and a full-screen tap-swallow so stray taps don't poke the
            // 3D scene.
            Box(modifier = Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures {} }) {
                // Both the chip and the flash are driven by per-kick events, so
                // they only appear once kicks start landing. That also means a
                // binary without the per-kick emit (pre-re-export) shows nothing
                // here rather than a frozen 0-0 — clean degradation either way.
                if (revealedKicks > 0) {
                    LiveScoreboard(
                        rounds = replay.rounds,
                        revealed = revealedKicks,
                        localRole = localRole,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .windowInsetsPadding(WindowInsets.safeDrawing)
                            .padding(16.dp),
                    )
                    val lastKick = replay.rounds[revealedKicks - 1]
                    KickFlash(
                        tick = revealedKicks,
                        isGoal = lastKick.result == "goal",
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
            }
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
 * Live match-style scoreboard shown over the cinematic: one row per side, each
 * with the running tally plus a strip of per-shot boxes — green ✓ for a scored
 * penalty, red ✕ for a saved/missed one, dim/empty for kicks not yet taken.
 * Boxes fill in as kicks land ([revealed] = how many of [rounds] have resolved),
 * mirroring how a real broadcast tracks a shootout. Framed from THIS device's
 * side ([localRole]; null = PvAI, you're P1).
 */
@Composable
private fun LiveScoreboard(
    rounds: List<RoundResult>,
    revealed: Int,
    localRole: Player?,
    modifier: Modifier,
) {
    val isP2 = localRole == Player.P2
    val mineTag = if (isP2) "P2" else "P1"
    val theirsTag = if (isP2) "P1" else "P2"
    val themLabel = if (localRole == null) "AI" else "OPP"

    // Each side's kicks in kicking order, tagged with whether they've been
    // revealed yet (original index < revealed) and whether they scored.
    fun marksFor(tag: String): List<ShotMark> =
        rounds.withIndex()
            .filter { it.value.shooter == tag }
            .map { (i, r) -> ShotMark(revealed = i < revealed, goal = r.result == "goal") }

    val mine = marksFor(mineTag)
    val theirs = marksFor(theirsTag)

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(KicksColors.BannerScrim)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ScoreboardRow(label = "YOU", marks = mine, accent = KicksColors.SuccessBright)
        ScoreboardRow(label = themLabel, marks = theirs, accent = KicksColors.Danger)
    }
}

private data class ShotMark(val revealed: Boolean, val goal: Boolean)

@Composable
private fun ScoreboardRow(label: String, marks: List<ShotMark>, accent: Color) {
    val score = marks.count { it.revealed && it.goal }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = label,
            color = accent,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
            modifier = Modifier.width(30.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            marks.forEachIndexed { idx, m ->
                // Visual gap between regulation (first 5) and sudden death.
                if (idx == REGULATION_KICKS_PER_PLAYER) Spacer(Modifier.width(4.dp))
                ShotPip(m)
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(text = "$score", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Black)
    }
}

@Composable
private fun ShotPip(mark: ShotMark) {
    val box = Modifier.size(15.dp).clip(RoundedCornerShape(4.dp))
    when {
        !mark.revealed -> Box(box.border(1.dp, Color.White.copy(alpha = 0.22f), RoundedCornerShape(4.dp)))
        mark.goal -> Box(box.background(KicksColors.Success), contentAlignment = Alignment.Center) {
            Text("✓", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Black)
        }
        else -> Box(box.background(KicksColors.Danger), contentAlignment = Alignment.Center) {
            Text("✕", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Black)
        }
    }
}

/**
 * The per-kick announcement: a big GOAL! / SAVED! that stamps in as each kick
 * resolves — a spring "punch" (scale overshoot) over a radial accent burst, with
 * a drop shadow so it reads over the bright pitch/crowd. Keyed on [tick] (the
 * running kick count) so it re-fires for every kick. Transient (~1s) and unbacked
 * so the 3D action stays visible. The cinematic is the one moment Unity is
 * actively rendering, so these animations get frames (no idle throttle).
 */
@Composable
private fun KickFlash(tick: Int, isGoal: Boolean, modifier: Modifier) {
    var visible by remember(tick) { mutableStateOf(false) }
    // Punch-in scale with overshoot: snaps from small → past 1 → settles, so the
    // call lands like a stamp rather than a plain fade.
    val punch = remember(tick) { Animatable(0.55f) }
    LaunchedEffect(tick) {
        visible = true
        punch.snapTo(0.55f)
        punch.animateTo(1f, spring(dampingRatio = 0.42f, stiffness = Spring.StiffnessMediumLow))
        delay(900)
        visible = false
    }
    val accent = if (isGoal) KicksColors.SuccessBright else KicksColors.Pending
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(90)),
        exit = fadeOut(tween(320)),
        modifier = modifier,
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.graphicsLayer { scaleX = punch.value; scaleY = punch.value },
        ) {
            // Radial accent burst behind the text — reads as an impact, not label.
            Box(
                modifier = Modifier
                    .size(260.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(listOf(accent.copy(alpha = 0.38f), Color.Transparent)),
                    ),
            )
            Text(
                text = if (isGoal) "GOAL!" else "SAVED!",
                color = accent,
                fontSize = 66.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 2.sp,
                style = TextStyle(
                    shadow = Shadow(Color.Black.copy(alpha = 0.65f), Offset(0f, 4f), blurRadius = 12f),
                ),
            )
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
    // Landscape (short height) keeps a scroll as a safety net. The decisive end
    // screen reflows to a two-pane that fits, so it stays centered; the tied
    // "continue" beat can be taller than a landscape viewport, so it top-aligns.
    val compact = isCompactHeight()
    val decisive = replay.p1Score != replay.p2Score
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
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(if (compact) 16.dp else 24.dp)
            .then(if (compact) Modifier.verticalScroll(rememberScrollState()) else Modifier),
        contentAlignment = if (compact && !decisive) Alignment.TopCenter else Alignment.Center,
    ) {
        if (decisive) {
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
/** The local player's name from their profile, upper-cased for label use; falls
 *  back to "YOU" when unset. The opponent's name is unknown to this device, so
 *  it stays "AI" / "OPPONENT". */
@Composable
private fun rememberPlayerLabel(): String {
    val context = LocalContext.current
    return remember { PlayerProfileStore.load(context).name.ifBlank { "YOU" }.uppercase() }
}

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
    val playerLabel = rememberPlayerLabel()
    val opponentName = if (localRole == null) "AI" else "OPPONENT"

    val accent = if (won) KicksColors.Warning else KicksColors.Danger

    if (isCompactHeight()) {
        // Landscape: two panes — the emotional summary (verdict + score) on the
        // left, the detail + exits (recap + REMATCH/MENU) on the right — so the
        // celebration fits the short height without scrolling or clipping.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(40.dp),
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(text = if (won) "🏆" else "💔", fontSize = 36.sp)
                VerdictBadge(won = won, accent = accent)
                ResultScore(playerLabel = playerLabel, mine = mine, theirs = theirs, won = won, opponentName = opponentName, accent = accent)
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                ShootoutRecap(playerLabel = playerLabel, rounds = replay.rounds, localRole = localRole, opponentName = opponentName)
                EndActions(onRematch = onRematch, onMenu = onMenu)
            }
        }
    } else {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(text = if (won) "🏆" else "💔", fontSize = 52.sp)
            VerdictBadge(won = won, accent = accent)
            ResultScore(playerLabel = playerLabel, mine = mine, theirs = theirs, won = won, opponentName = opponentName, accent = accent)
            ShootoutRecap(playerLabel = playerLabel, rounds = replay.rounds, localRole = localRole, opponentName = opponentName)
            EndActions(onRematch = onRematch, onMenu = onMenu)
        }
    }
}

@Composable
private fun VerdictBadge(won: Boolean, accent: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(accent.copy(alpha = 0.14f))
            .border(1.dp, accent.copy(alpha = 0.5f), RoundedCornerShape(percent = 50))
            .padding(horizontal = 30.dp, vertical = 10.dp),
    ) {
        Text(
            text = if (won) "VICTORY" else "DEFEAT",
            color = accent,
            fontSize = 30.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 5.sp,
        )
    }
}

@Composable
private fun ResultScore(playerLabel: String, mine: Int, theirs: Int, won: Boolean, opponentName: String, accent: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        ScoreSide(label = playerLabel, score = mine, color = if (won) accent else Color.White.copy(alpha = 0.85f))
        Text("–", color = Color.White.copy(alpha = 0.3f), fontSize = 40.sp, fontWeight = FontWeight.Light)
        ScoreSide(
            label = opponentName,
            score = theirs,
            color = if (!won) accent else Color.White.copy(alpha = 0.85f),
        )
    }
}

@Composable
private fun ScoreSide(label: String, score: Int, color: Color) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            label,
            color = Color.White.copy(alpha = 0.5f),
            fontSize = 11.sp,
            letterSpacing = 2.sp,
            fontWeight = FontWeight.Medium,
        )
        Text("$score", color = color, fontSize = 60.sp, fontWeight = FontWeight.Black)
    }
}

/**
 * Classic penalty-board recap: one row of goal/save marks per player, in kicking
 * order, with regulation and sudden-death visually split. Reuses the live
 * scoreboard's pips — green ✓ scored, red ✕ saved — so both reads match.
 */
@Composable
private fun ShootoutRecap(playerLabel: String, rounds: List<RoundResult>, localRole: Player?, opponentName: String) {
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
        RecapRow(label = playerLabel, marks = mineMarks)
        RecapRow(label = opponentName, marks = theirMarks)
        Text(
            text = "✓ scored   ✕ saved",
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
            maxLines = 1,
            modifier = Modifier.width(72.dp),
        )
        marks.forEachIndexed { idx, goal ->
            // Visually separate regulation from sudden death.
            if (idx == REGULATION_KICKS_PER_PLAYER) {
                Text(text = "|", color = Color.White.copy(alpha = 0.3f), fontSize = 16.sp)
            }
            // Reuse the live scoreboard pip (all recap marks are final/revealed).
            ShotPip(ShotMark(revealed = true, goal = goal))
        }
    }
}

@Composable
private fun EndActions(onRematch: () -> Unit, onMenu: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        EndButton(text = "REMATCH", fill = Color.White, textColor = KicksColors.Background, onClick = onRematch)
        EndButton(text = "MENU", fill = Color.White.copy(alpha = 0.10f), textColor = Color.White, onClick = onMenu)
    }
}

@Composable
private fun EndButton(text: String, fill: Color, textColor: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .kicksPressable { onClick() }
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
    val isP2 = localRole == Player.P2
    val playerLabel = rememberPlayerLabel()
    val p1Label = if (isP2) "P1" else playerLabel
    val p2Label = if (isP2) playerLabel else "P2"
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
            .kicksPressable { MatchHud.dismissReplay() }
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
