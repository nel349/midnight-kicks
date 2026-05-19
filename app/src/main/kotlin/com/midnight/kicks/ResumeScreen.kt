package com.midnight.kicks

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

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
) {
    Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF0A0A0A)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
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
                    MatchRow(match = match, onClick = { onMatchSelected(match) })
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
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = Color.White.copy(alpha = 0.06f),
                shape = RoundedCornerShape(12.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Role badge — fixed width so the address column lines up
            // across rows when both P1 and P2 matches coexist.
            Box(
                modifier = Modifier
                    .background(
                        color = when (match.role) {
                            Player.P1 -> Color(0xFF4FB7FF).copy(alpha = 0.18f)
                            Player.P2 -> Color(0xFF8CFF7B).copy(alpha = 0.18f)
                        },
                        shape = RoundedCornerShape(6.dp),
                    )
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(
                    text = match.role.name,
                    color = Color.White,
                    fontSize = 11.sp,
                    letterSpacing = 2.sp,
                    fontWeight = FontWeight.Medium,
                )
            }

            Spacer(modifier = Modifier.fillMaxWidth(fraction = 0.04f))

            Column(modifier = Modifier.fillMaxWidth(fraction = 0.75f)) {
                Text(
                    text = match.address.shortAddress(),
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = deadlineLabel(match.deadline),
                    color = Color.White.copy(alpha = 0.4f),
                    fontSize = 11.sp,
                    letterSpacing = 1.sp,
                )
            }

            Spacer(modifier = Modifier.fillMaxWidth(fraction = 1f))

            Text(
                "›",
                color = Color.White.copy(alpha = 0.4f),
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
internal fun deadlineLabel(deadlineSeconds: Long): String {
    val nowSeconds = System.currentTimeMillis() / 1000
    val deltaSeconds = deadlineSeconds - nowSeconds
    val absSeconds = kotlin.math.abs(deltaSeconds)
    val phrase = when {
        absSeconds < 60 -> "$absSeconds s"
        absSeconds < 3600 -> "${absSeconds / 60} min"
        absSeconds < 86_400 -> "${absSeconds / 3600} h"
        else -> "${absSeconds / 86_400} d"
    }
    return if (deltaSeconds >= 0) "DEADLINE IN $phrase" else "EXPIRED $phrase AGO"
}

/**
 * ISO timestamp helper for tests / debug logs. Not used by the UI today
 * but kept here so a future "show absolute time" affordance reuses the
 * same UTC-anchored format the chain uses.
 */
@Suppress("unused")
internal fun isoTimestamp(unixSeconds: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm 'UTC'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }
        .format(Date(unixSeconds * 1000))
