package com.midnight.kicks

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Always-on "leave the match" affordance, rendered as the **top-most** Compose
 * layer in [KicksMatchActivity] so it's tappable over every other overlay.
 *
 * **Why Compose.** The picker / stage / replay overlays are modal (they absorb
 * touches), so Unity's old top-right IMGUI "LEAVE" button sat *behind* them —
 * visible but untappable. Moving the affordance to Compose, on top of the
 * stack, makes leaving work in every phase. The Unity IMGUI button is removed
 * in `GameController.cs` (lands on the next Unity re-export); this replaces it.
 *
 * Touch routing: only the pill consumes taps (its `clickable`). The enclosing
 * full-screen Box has no gesture modifier, so taps anywhere else fall through
 * to the overlays / Unity beneath — this layer doesn't block gameplay.
 *
 * @param onLeave invoked on tap — see [KicksMatchActivity.leaveMatch] (notify
 *   main so it cancels the orchestrator + updates the menu, then kill `:unity`).
 */
@Composable
fun MatchLeaveButton(onLeave: () -> Unit) {
    // Hide on the end screen: a decisive (match-over) replay shows REMATCH /
    // MENU as the exits, so the LEAVE pill is redundant there. Intermediate
    // replays + live play still get it (a way to bail mid-match).
    val replay by MatchHud.replay.collectAsState()
    val onEndScreen = replay?.show?.let { it.p1Score != it.p2Score } == true
    if (onEndScreen) return

    Box(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .displayCutoutPadding()
            .padding(14.dp),
        contentAlignment = Alignment.TopEnd,
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(percent = 50))
                .background(KicksColors.BannerScrim)
                .border(1.dp, KicksColors.Danger.copy(alpha = 0.55f), RoundedCornerShape(percent = 50))
                .clickable { onLeave() }
                .padding(horizontal = 16.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("✕", color = KicksColors.Danger, fontSize = 15.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.width(7.dp))
            Text(
                text = "LEAVE",
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp,
            )
        }
    }
}
