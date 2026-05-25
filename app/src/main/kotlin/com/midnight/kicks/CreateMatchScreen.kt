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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.midnight.kuira.dapp.wallet.QrCode

/**
 * P1's matchmaking screen. Two visual states, driven by [address]:
 *
 *  - `address == null` → deploying spinner
 *  - `address != null` → QR + truncated address + tap-to-copy + "waiting
 *    for opponent…" label. The opponent scans / receives the deep link
 *    `midnight://kicks?match=<address>` and lands on [JoinMatchScreen].
 *
 * The "wait for opponent to actually join on chain" step is Phase 4 step 2
 * (`MatchManager.awaitOpponentJoin`). For now the waiting label is purely
 * informational — once that orchestrator lands, this screen will hand off
 * into the Unity choice phase the same way PvAI does today.
 */
@Composable
fun CreateMatchScreen(
    address: String?,
    checking: Boolean = false,
    statusMessage: String? = null,
    onBack: () -> Unit,
    onCheckStatus: () -> Unit = {},
    onCancel: () -> Unit = {},
) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    // Cancel is destructive + terminal (refunds the stake, ends the match),
    // so it's a two-tap confirm rather than a single tap that could fire by
    // accident while the user is fiddling with the QR / copy.
    var confirmingCancel by remember { mutableStateOf(false) }

    Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF0A0A0A)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top,
        ) {
            TopBackBar(label = "CREATE MATCH", onBack = onBack)

            Spacer(modifier = Modifier.height(48.dp))

            if (address == null) {
                CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp)
                Spacer(modifier = Modifier.height(20.dp))
                Text(
                    "Deploying contract…",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 14.sp,
                    letterSpacing = 2.sp,
                )
            } else {
                Text(
                    "SHARE WITH OPPONENT",
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 12.sp,
                    letterSpacing = 4.sp,
                )
                Spacer(modifier = Modifier.height(24.dp))

                // QR encodes the deep link so a scanner app opens kicks
                // directly. The opponent can also paste just the address
                // into JoinMatchScreen.
                QrCode(
                    text = "midnight://kicks?match=$address",
                    size = 240.dp,
                )

                Spacer(modifier = Modifier.height(28.dp))

                Text(
                    text = address.shortAddress(),
                    color = Color.White,
                    fontSize = 14.sp,
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.Center,
                )

                Spacer(modifier = Modifier.height(20.dp))

                Box(
                    modifier = Modifier
                        .background(
                            Color.White.copy(alpha = 0.08f),
                            shape = RoundedCornerShape(10.dp),
                        )
                        .clickable {
                            clipboard.setText(AnnotatedString(address))
                            copied = true
                        }
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                ) {
                    Text(
                        text = if (copied) "✓  COPIED" else "COPY ADDRESS",
                        color = Color.White,
                        fontSize = 13.sp,
                        letterSpacing = 3.sp,
                    )
                }

                Spacer(modifier = Modifier.height(40.dp))

                // Create-and-go pattern: the user shares the link, the
                // opponent joins on their own timeline, and the user comes
                // back to tap CHECK STATUS to see if it's their turn yet.
                // No background coroutine pinned to this Activity's
                // lifecycle — the session is persisted via
                // [MatchStore] so the user can fully leave the app.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .background(
                            Color.White.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(12.dp),
                        )
                        .let { if (!checking) it.clickable(onClick = onCheckStatus) else it },
                    contentAlignment = Alignment.Center,
                ) {
                    if (checking) {
                        CircularProgressIndicator(
                            color = Color.White,
                            strokeWidth = 2.dp,
                            modifier = Modifier.height(24.dp),
                        )
                    } else {
                        Text(
                            "CHECK STATUS",
                            color = Color.White,
                            fontSize = 14.sp,
                            letterSpacing = 4.sp,
                        )
                    }
                }

                if (statusMessage != null) {
                    Spacer(modifier = Modifier.height(20.dp))
                    Text(
                        statusMessage,
                        color = Color.White.copy(alpha = 0.5f),
                        fontSize = 12.sp,
                        letterSpacing = 2.sp,
                    )
                }

                Spacer(modifier = Modifier.height(28.dp))

                // No opponent yet → the creator can cancel and reclaim the
                // stake (contract `cancelMatch`, valid only in WAITING phase).
                // Lives here, not in an in-match menu, because this is exactly
                // where the creator waits for a join — once someone joins, the
                // flow moves on to MatchReady and cancel is no longer valid.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .background(
                            if (confirmingCancel) KicksColors.DangerSurface else Color.Transparent,
                            shape = RoundedCornerShape(12.dp),
                        )
                        .let { if (!checking) it.clickable { if (confirmingCancel) onCancel() else confirmingCancel = true } else it }
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (confirmingCancel) "TAP AGAIN TO CANCEL & REFUND" else "CANCEL MATCH",
                        color = KicksColors.Danger,
                        fontSize = 13.sp,
                        letterSpacing = 3.sp,
                    )
                }
            }
        }
    }
}

@Composable
internal fun TopBackBar(label: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .background(
                    Color.White.copy(alpha = 0.08f),
                    shape = RoundedCornerShape(8.dp),
                )
                .clickable(onClick = onBack)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text("‹  BACK", color = Color.White, fontSize = 12.sp, letterSpacing = 2.sp)
        }
        Spacer(modifier = Modifier.fillMaxWidth(fraction = 0.5f))
        Text(
            label,
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 4.sp,
        )
    }
}

internal fun String.shortAddress(): String =
    if (length > 32) substring(0, 16) + "…" + substring(length - 8) else this
