package com.midnight.kicks

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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

    Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF0A0A0A)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top,
        ) {
            TopBackBar(label = "JOIN MATCH", onBack = onBack)

            Spacer(modifier = Modifier.height(64.dp))

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
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .background(
                        if (enabled) Color.White.copy(alpha = 0.15f)
                        else Color.White.copy(alpha = 0.05f),
                        shape = RoundedCornerShape(12.dp),
                    )
                    .let { if (enabled) it.clickable { onJoin(address) } else it },
                contentAlignment = Alignment.Center,
            ) {
                if (inFlight) {
                    CircularProgressIndicator(
                        color = Color.White,
                        strokeWidth = 2.dp,
                        modifier = Modifier.height(24.dp),
                    )
                } else {
                    Text(
                        "JOIN MATCH",
                        color = if (enabled) Color.White else Color.White.copy(alpha = 0.3f),
                        fontSize = 14.sp,
                        letterSpacing = 4.sp,
                    )
                }
            }

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
}

/** 64 hex chars, lowercase. Matches the contract-address output of `MidnightContract.deploy`. */
private val CONTRACT_ADDRESS_REGEX = Regex("^[0-9a-f]{64}$")
