package com.midnight.kicks

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * P2's matchmaking screen. Accepts a `mn_addr_…` contract address either:
 *  - prefilled via the `midnight://kicks?match=…` deep link, OR
 *  - pasted by the user from a chat/QR-decoder
 *
 * Tap **JOIN MATCH** → calls [onJoin] with the trimmed address. The host
 * (`KicksActivity`) is responsible for plumbing this into the chain-side
 * `MatchManager.joinAsP2` once that lands (Phase 4 step 2). Today the
 * callback just logs.
 *
 * QR scanner is Phase 5 polish — Google Code Scanner (no camera permission)
 * is the planned implementation.
 */
@Composable
fun JoinMatchScreen(
    prefilledAddress: String?,
    inFlight: Boolean = false,
    onBack: () -> Unit,
    onJoin: (String) -> Unit,
) {
    var address by remember { mutableStateOf(prefilledAddress.orEmpty()) }

    KicksScreenScaffold {
            TopBackBar(label = "JOIN MATCH", onBack = onBack)

            Spacer(modifier = Modifier.height(if (isCompactHeight()) 24.dp else 64.dp))

            Text(
                "PASTE OPPONENT'S MATCH ADDRESS",
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 12.sp,
                letterSpacing = 4.sp,
            )

            Spacer(modifier = Modifier.height(20.dp))

            OutlinedTextField(
                value = address,
                onValueChange = { address = it.trim() },
                modifier = Modifier.fillMaxWidth(),
                placeholder = {
                    Text(
                        "64-char hex contract address",
                        color = Color.White.copy(alpha = 0.3f),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                    )
                },
                singleLine = false,
                maxLines = 3,
                textStyle = androidx.compose.ui.text.TextStyle(
                    color = Color.White,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                ),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color.White.copy(alpha = 0.5f),
                    unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                    cursorColor = Color.White,
                ),
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Contract addresses on Midnight are 64-char lowercase hex
            // (the contract hash). Wallet addresses are bech32m
            // `mn_addr_…` — different concept; matchmaking uses the
            // contract address.
            val enabled = !inFlight && address.matches(CONTRACT_ADDRESS_REGEX)
            KicksButton(
                label = "JOIN MATCH",
                onClick = { onJoin(address) },
                enabled = enabled,
                loading = inFlight,
            )

            if (inFlight) {
                Spacer(modifier = Modifier.height(20.dp))
                Text(
                    "Submitting joinMatch on chain…",
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 12.sp,
                    letterSpacing = 2.sp,
                )
            } else if (prefilledAddress != null) {
                Spacer(modifier = Modifier.height(28.dp))
                Text(
                    "↑ filled from deep link",
                    color = Color.White.copy(alpha = 0.4f),
                    fontSize = 11.sp,
                    letterSpacing = 2.sp,
                )
            }
        }
}

/** 64 hex chars, lowercase. Matches the contract-address output of `MidnightContract.deploy`. */
private val CONTRACT_ADDRESS_REGEX = Regex("^[0-9a-f]{64}$")
