package com.midnight.kicks

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The in-match **direction picker**, rendered as a Compose overlay above
 * Unity's 3D surface (hosted by [KicksMatchActivity], same Box stack as
 * [MatchHudOverlay] / [MatchReplayOverlay]).
 *
 * **Why Compose, not Unity IMGUI.** The old picker was `GUI.Button` /
 * `GUI.Label` in `GameController.OnGUI` — flat grey buttons, no motion, and
 * every tweak needed a full Unity re-export. Moving it here gives real
 * game-feel (animated reveal, per-pick role crossfade, pressable buttons,
 * progress pips) and lets us iterate instantly. Unity keeps the 3D; it never
 * enters its own picker because main stops sending `choicePhase` to it.
 *
 * **Data flow.** Driven entirely by [MatchHud.picker] (published by main on a
 * choice phase, relayed across the process boundary). Picks are collected
 * locally here; when all `roles.size` are in, [UnityBridge.submitLocalPicks]
 * feeds them back through the exact `choicesLocked` path Unity's picker used,
 * then [MatchHud.dismissPicker] closes it.
 */
@Composable
fun MatchPickerOverlay() {
    val picker by MatchHud.picker.collectAsState()

    // Hold the last non-null show so content stays rendered through the exit
    // animation (picker goes null the instant the last pick lands).
    var lastShow by remember { mutableStateOf<MatchHud.PickerShow?>(null) }
    LaunchedEffect(picker) { picker?.let { lastShow = it } }

    AnimatedVisibility(
        visible = picker != null,
        enter = fadeIn(tween(220)) + slideInVertically(tween(260)) { it / 6 },
        exit = fadeOut(tween(160)),
    ) {
        lastShow?.let { PickerContent(it) }
    }
}

@Composable
private fun PickerContent(show: MatchHud.PickerShow) {
    val total = show.roles.size
    // Keyed on content (roles + title), not the PickerShow instance, so a re-push
    // of the same round (resendCurrent on :unity rebind) doesn't reset picks/step.
    val picks = remember(show.roles, show.title) { IntArray(total) { -1 } }
    var step by remember(show.roles, show.title) { mutableIntStateOf(0) }

    // Grouped presentation: all SHOOT picks first, then all KEEP — the player
    // stays in one mindset instead of flip-flopping shoot/keep each round.
    // Picks are stored at their CANONICAL round index (order[step]), so the
    // contract bucketing downstream is unchanged — only the on-screen order
    // differs. Stable sort keeps each group's rounds in their natural order.
    val order = remember(show.roles, show.title) {
        show.roles.indices.sortedBy { if (show.roles[it] == "shoot") 0 else 1 }
    }
    val shootCount = remember(show.roles, show.title) { show.roles.count { it == "shoot" } }

    // All picks in → submit through the legacy choicesLocked path + close.
    LaunchedEffect(show, step) {
        if (step >= total && total > 0) {
            UnityBridge.submitLocalPicks(picks.copyOf())
            MatchHud.dismissPicker()
        }
    }

    val safeStep = step.coerceIn(0, (total - 1).coerceAtLeast(0))
    val isShoot = show.roles.getOrElse(order.getOrElse(safeStep) { 0 }) { "shoot" } == "shoot"
    // Position within the current group, e.g. "3 of 5" — shoots come first.
    val groupTotal = if (isShoot) shootCount else total - shootCount
    val groupPos = if (isShoot) safeStep + 1 else safeStep - shootCount + 1
    val accent by animateColorAsState(
        if (isShoot) KicksColors.SuccessBright else KicksColors.Pending,
        tween(220),
        label = "accent",
    )

    val onPick: (Int) -> Unit = { dir ->
        if (step < total) {
            picks[order[step]] = dir
            step++
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            // Vertical scrim: pitch + goal stay visible up top (you see where
            // you're aiming), darkening toward the buttons for contrast. Also
            // covers Unity's idle "Ready" label behind it.
            .background(
                Brush.verticalGradient(
                    listOf(
                        KicksColors.Background.copy(alpha = 0.25f),
                        KicksColors.Background.copy(alpha = 0.62f),
                        KicksColors.Background.copy(alpha = 0.94f),
                    ),
                ),
            )
            // Modal: swallow stray taps so they don't fall through to Unity.
            .pointerInput(Unit) { detectTapGestures { } }
            .statusBarsPadding()
            .displayCutoutPadding(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            // ── Top: round title + group progress + pips ──
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = show.title.uppercase(),
                    color = Color.White.copy(alpha = 0.62f),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 2.sp,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "${if (isShoot) "SHOOTING" else "KEEPING"} · $groupPos of $groupTotal",
                    color = accent,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                )
                Spacer(Modifier.height(12.dp))
                ProgressPips(total = total, done = step, accent = accent)
            }

            // ── Centre: role banner, crossfading only on the shoot↔keep switch
            // (stays put within a group so the player isn't visually nagged). ──
            Crossfade(targetState = isShoot, label = "role") { shoot ->
                RoleBanner(isShoot = shoot)
            }

            // ── Bottom: the three corners ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                DirectionButton(Modifier.weight(1f), "◀", "LEFT", accent) { onPick(0) }
                DirectionButton(Modifier.weight(1f), "▲", "CENTRE", accent) { onPick(1) }
                DirectionButton(Modifier.weight(1f), "▶", "RIGHT", accent) { onPick(2) }
            }
        }
    }
}

@Composable
private fun RoleBanner(isShoot: Boolean) {
    val accent = if (isShoot) KicksColors.SuccessBright else KicksColors.Pending
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = if (isShoot) "YOU SHOOT" else "YOU KEEP",
            color = accent,
            fontSize = 44.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 1.sp,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = if (isShoot) "pick your corner" else "guess their corner",
            color = Color.White.copy(alpha = 0.78f),
            fontSize = 16.sp,
            textAlign = TextAlign.Center,
        )
    }
}

/** A row of [total] dots, the first [done] filled in [accent]. */
@Composable
private fun ProgressPips(total: Int, done: Int, accent: Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(total) { i ->
            val filled = i < done
            val current = i == done
            val color = when {
                filled -> accent
                current -> accent.copy(alpha = 0.45f)
                else -> Color.White.copy(alpha = 0.20f)
            }
            val scale by animateFloatAsState(if (current) 1.25f else 1f, tween(200), label = "pip")
            Box(
                modifier = Modifier
                    .graphicsLayer { scaleX = scale; scaleY = scale }
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(color),
            )
        }
    }
}

@Composable
private fun DirectionButton(
    modifier: Modifier,
    glyph: String,
    label: String,
    accent: Color,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) 0.93f else 1f,
        spring(dampingRatio = 0.55f),
        label = "press",
    )
    val fillAlpha by animateFloatAsState(if (pressed) 0.34f else 0.16f, tween(120), label = "fill")

    Box(
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .height(116.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(accent.copy(alpha = fillAlpha))
            .border(2.dp, accent.copy(alpha = 0.85f), RoundedCornerShape(22.dp))
            .clickable(interaction, indication = null) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(glyph, color = accent, fontSize = 38.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(4.dp))
            Text(
                label,
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
            )
        }
    }
}
