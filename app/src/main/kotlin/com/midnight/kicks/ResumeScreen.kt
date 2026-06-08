package com.midnight.kicks

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs

/**
 * Resume picker — lists every match in [MatchStore], tap a row to land
 * on the right screen for that match's role.
 *
 * **Why a list instead of single-match auto-resume:** [MatchStore] is
 * multi-match (the user can have several PvP matches in flight against
 * different friends). Auto-routing to the "first" match would silently
 * pick one, which is fine for the single-match common case but actively
 * hostile to power users juggling several at once. The list is correct
 * for both cases — the single-match user still taps once.
 *
 * Each row shows what the user needs to recognize the match in a glance:
 *  - **role** (P1/P2) — large, leading. Tells you "I'm the creator" vs
 *    "I'm the joiner".
 *  - **shortened address** — monospace, in the middle. Enough to
 *    distinguish two open matches, full address survives a tap into the
 *    next screen.
 *  - **deadline countdown** — relative to now. Surfaces the time-pressure
 *    cue: "joinMatch's 24h commit deadline is in 3h" vs "still tomorrow".
 *
 * Phase column (on-chain match phase) is intentionally not shown here —
 * it would require a chain round-trip per row at render time, which
 * shouldn't block the Resume UI from appearing. The destination screen
 * for each match does its own state query via `MatchManager`.
 *
 * Empty-state shouldn't be reachable in practice — the Resume button on
 * the main menu only appears when `MatchStore.loadAll().isNotEmpty()` —
 * but we render a friendly message anyway as a defensive guard against
 * a race (Backup-button-then-tap-Resume between loads, e.g.).
 */
@Composable
fun ResumeScreen(
    matches: List<MatchStore.Match>,
    onBack: () -> Unit,
    onMatchSelected: (MatchStore.Match) -> Unit,
    /**
     * Invoked when the user taps the trailing X on a row. Callers
     * should confirm with the user before [MatchStore.delete] — this
     * is destructive (the local witness key is wiped, so any further
     * action on the match address from this device will fail with
     * "Not a player in this match").
     */
    onAbandon: (MatchStore.Match) -> Unit = {},
) {
    Surface(modifier = Modifier.fillMaxSize(), color = KicksColors.Background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top,
        ) {
            TopBackBar(label = "RESUME", onBack = onBack)

            Spacer(modifier = Modifier.height(48.dp))

            if (matches.isEmpty()) {
                Text(
                    "NO ACTIVE MATCHES",
                    color = Color.White.copy(alpha = 0.4f),
                    fontSize = 12.sp,
                    letterSpacing = 4.sp,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Create or join one from the menu.",
                    color = Color.White.copy(alpha = 0.3f),
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                )
                return@Column
            }

            Text(
                "${matches.size} ACTIVE",
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 12.sp,
                letterSpacing = 4.sp,
            )

            Spacer(modifier = Modifier.height(20.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Render every match. A `LazyColumn` would be marginally
                // more efficient for very large lists, but realistic
                // active-match counts are 1-5 (a human can't juggle
                // many concurrent PvP games) and verticalScroll keeps
                // the implementation simpler.
                matches.forEach { match ->
                    MatchRow(
                        match = match,
                        onClick = { onMatchSelected(match) },
                        onAbandon = { onAbandon(match) },
                    )
                }
            }
        }
    }
}

/**
 * One row in the resume list. Three columns:
 *  - role badge (P1 / P2)
 *  - address (monospace, ellipsized)
 *  - deadline relative time (e.g. "in 3h", "in 2d", "expired 12h ago")
 */
@Composable
private fun MatchRow(
    match: MatchStore.Match,
    onClick: () -> Unit,
    onAbandon: () -> Unit,
) {
    // A PvAI match persists an [ai] slot; PvP doesn't. Use it to badge the row
    // "VS AI" in a distinct colour so practice-vs-AI is obvious next to PvP
    // rows, which keep their P1/P2 creator-vs-joiner badge.
    val isAi = match.ai != null
    val badgeColor = when {
        isAi -> KicksColors.Pending
        match.role == Player.P1 -> KicksColors.AccentBright
        else -> KicksColors.SuccessBright
    }
    val badgeLabel = if (isAi) "VS AI" else match.role.name
    Box(
        modifier = Modifier
            .fillMaxWidth()
            // Abandon ✕ is a child with its own kicksPressable, so it doesn't
            // propagate to this outer onClick. Tap → resume.
            .kicksPressable(onClick = onClick)
            .background(
                color = Color.White.copy(alpha = 0.09f),
                shape = RoundedCornerShape(14.dp),
            )
            .border(
                width = 1.dp,
                color = Color.White.copy(alpha = 0.10f),
                shape = RoundedCornerShape(14.dp),
            )
            .padding(horizontal = 18.dp, vertical = 16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Role badge — fixed width so the address column lines up
            // across rows when both P1 and P2 matches coexist.
            Box(
                modifier = Modifier
                    .background(badgeColor.copy(alpha = 0.20f), shape = RoundedCornerShape(7.dp))
                    .border(1.dp, badgeColor.copy(alpha = 0.45f), RoundedCornerShape(7.dp))
                    .padding(horizontal = 9.dp, vertical = 5.dp),
            ) {
                Text(
                    text = badgeLabel,
                    color = badgeColor,
                    fontSize = 11.sp,
                    letterSpacing = 2.sp,
                    fontWeight = FontWeight.Bold,
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Address + deadline take the remaining row width via
            // weight(1f); the chevron at the trailing edge gets its
            // intrinsic width. This is the right Row idiom — the
            // previous `fillMaxWidth(fraction = 0.75f)` was a brittle
            // hardcode that competed with the badge + spacer layout.
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = match.address.shortAddress(),
                    color = Color.White.copy(alpha = 0.92f),
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = deadlineLabel(match.deadline),
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 11.sp,
                    letterSpacing = 1.sp,
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Trailing abandon button. Fires [onAbandon] up the tree;
            // the activity confirms via dialog before deleting from
            // MatchStore. Small "✕" target so a fat-finger tap on the
            // row doesn't accidentally trigger abandon.
            Box(
                modifier = Modifier
                    .kicksPressable(shape = RoundedCornerShape(6.dp), onClick = onAbandon)
                    .background(
                        color = Color.White.copy(alpha = 0.08f),
                        shape = RoundedCornerShape(6.dp),
                    )
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(
                    "✕",
                    color = Color.White.copy(alpha = 0.55f),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Light,
                )
            }

            Spacer(modifier = Modifier.width(6.dp))

            Text(
                "›",
                color = Color.White.copy(alpha = 0.55f),
                fontSize = 24.sp,
                fontWeight = FontWeight.Light,
            )
        }
    }
}

/**
 * Human-readable countdown for the row's secondary line. The deadline
 * stored in [MatchStore.Match] is unix seconds — what the contract sees
 * + asserts against. We don't fetch chain block time here because
 * doing so on every row render would lag the Resume UI behind a
 * network round-trip; the wall-clock approximation is close enough
 * for "should I tap this row" UX (the contract is still the source
 * of truth on actually-expired-or-not when the user re-engages).
 *
 * `internal` so [ResumeScreenTest] can pin the format strings without
 * hand-rolling the seconds-to-label math.
 */
/**
 * Confirm-before-deleting dialog for the abandon-match flow. The
 * local secret key for the match is wiped from [MatchStore] — the
 * chain contract is untouched, but this device can no longer commit /
 * reveal on it.
 */
@Composable
fun AbandonMatchDialog(
    match: MatchStore.Match,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = {
            Text("Abandon match?")
        },
        text = {
            Text(
                "This wipes your local key for match ${match.address.shortAddress()} " +
                    "(role: ${match.role.name}). The contract on chain stays put, but " +
                    "this device can no longer commit or reveal on it. " +
                    "Use only when you're sure the match is stuck or you no longer want to play.",
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("ABANDON") }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text("CANCEL") }
        },
    )
}

internal fun deadlineLabel(deadlineSeconds: Long): String {
    val nowSeconds = System.currentTimeMillis() / 1000
    val deltaSeconds = deadlineSeconds - nowSeconds
    val absSeconds = abs(deltaSeconds)
    val phrase = when {
        absSeconds < 60 -> "$absSeconds s"
        absSeconds < 3600 -> "${absSeconds / 60} min"
        absSeconds < 86_400 -> "${absSeconds / 3600} h"
        else -> "${absSeconds / 86_400} d"
    }
    return if (deltaSeconds >= 0) "DEADLINE IN $phrase" else "EXPIRED $phrase AGO"
}
