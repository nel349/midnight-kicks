package com.midnight.kicks

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Both players are in the contract — match is ready to start. Today this
 * is a terminal screen with a stub CONTINUE that launches the Unity choice
 * phase only for [Player.P1] via the existing PvAI orchestrator (P2's
 * gameplay path is Phase 4 step 3). Once `playAsP1` / `playAsP2`
 * orchestrators land in `MatchManager`, both roles route through
 * `launchUnityChoicePhase` and the bridge.
 */
@Composable
fun MatchReadyScreen(
    address: String,
    role: Player,
    onBack: () -> Unit,
    onContinue: () -> Unit,
    // True once the on-chain deadline passed and the opponent owes a move →
    // enables CLAIM POT (forfeit).
    claimable: Boolean = false,
    onClaimForfeit: () -> Unit = {},
) {
    KicksScreenScaffold {
        TopBackBar(label = "MATCH READY", onBack = onBack)

        Spacer(modifier = Modifier.height(if (isCompactHeight()) 24.dp else 80.dp))

            Text(
                "✓",
                color = KicksColors.SuccessBright,
                fontSize = 64.sp,
                fontWeight = FontWeight.W200,
            )
            Spacer(modifier = Modifier.height(20.dp))
            Text(
                "BOTH PLAYERS IN",
                color = Color.White,
                fontSize = 14.sp,
                letterSpacing = 6.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                when (role) {
                    Player.P1 -> "You are P1"
                    Player.P2 -> "You are P2"
                },
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 13.sp,
                letterSpacing = 2.sp,
            )
            Spacer(modifier = Modifier.height(40.dp))
            Text(
                text = address.shortAddress(),
                color = Color.White.copy(alpha = 0.4f),
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(if (isCompactHeight()) 24.dp else 72.dp))

            KicksButton(label = "CONTINUE", onClick = onContinue)

            if (claimable) {
                Spacer(modifier = Modifier.height(16.dp))
                KicksButton(
                    label = "CLAIM POT",
                    onClick = onClaimForfeit,
                    style = KicksButtonStyle.Danger,
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    "Opponent missed the deadline — claim the pot.",
                    color = Color.White.copy(alpha = 0.45f),
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                )
            }
        }
}
