package com.midnight.kicks

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.midnight.kuira.core.network.MidnightNetwork
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject

/**
 * Main activity for Midnight Kicks.
 *
 * Hosts the Compose menu UI (match creation, joining, results)
 * and launches Unity (via UaaL) for the 3D game portions.
 *
 * Flow: Menu → launch UnityPlayerGameActivity → Unity shows choice UI →
 * player picks 5 directions → Unity sends choicesLocked → back to Kotlin.
 */
class KicksActivity : ComponentActivity() {

    private val statusMessage = mutableStateOf<String?>(null)
    private val lastChoices = mutableStateOf<String?>(null)
    private var matchManager: MatchManager? = null
    private val scope = CoroutineScope(Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Register for Unity bridge messages
        UnityBridge.onMessageFromUnity = { json ->
            runOnUiThread { handleUnityMessage(json) }
        }

        handleDeepLink(intent)

        setContent {
            KicksApp(
                statusMessage = statusMessage.value,
                lastChoices = lastChoices.value,
                onCreateMatch = { launchUnityChoicePhase() },
                onJoinMatch = { /* TODO */ },
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDeepLink(intent)
    }

    override fun onDestroy() {
        UnityBridge.onMessageFromUnity = null
        super.onDestroy()
    }

    private fun launchUnityChoicePhase() {
        scope.launch {
            try {
                // Init SDK if needed
                if (matchManager == null) {
                    statusMessage.value = "Initializing SDK..."
                    val manager = MatchManager(
                        context = applicationContext,
                        network = MidnightNetwork.UNDEPLOYED,
                        seed = TEST_SEED,
                    )
                    manager.initSdk { statusMessage.value = it }
                    matchManager = manager
                }

                // Deploy contract
                statusMessage.value = "Deploying match contract..."
                val address = matchManager!!.createMatch { statusMessage.value = it }
                statusMessage.value = "Match: ${address.take(16)}..."

                // Launch Unity for choice phase
                Log.i(TAG, "Launching Unity for choice phase...")
                val intent = Intent(this@KicksActivity, com.unity3d.player.UnityPlayerGameActivity::class.java)
                startActivity(intent)

                // Send choice phase message after Unity loads
                window.decorView.postDelayed({
                    UnityBridge.sendChoicePhase(round = "regulation", playerRole = "shooter")
                    statusMessage.value = "Pick your 5 directions!"
                }, 2000)
            } catch (e: Exception) {
                Log.e(TAG, "Create match failed", e)
                statusMessage.value = "Error: ${e.message}"
            }
        }
    }

    private fun handleUnityMessage(jsonString: String) {
        Log.i(TAG, "From Unity: $jsonString")

        try {
            val json = JSONObject(jsonString)
            when (json.getString("type")) {
                "choicesLocked" -> {
                    val choices = json.getJSONArray("choices")
                    val choiceList = (0 until choices.length()).map { choices.getInt(it) }
                    val labels = choiceList.map { when (it) { 0 -> "L"; 1 -> "C"; 2 -> "R"; else -> "?" } }

                    Log.i(TAG, "Player choices: $labels")
                    lastChoices.value = "Choices: ${labels.joinToString(" ")}"
                    statusMessage.value = "Committing to blockchain..."

                    // Commit choices to blockchain
                    scope.launch {
                        try {
                            matchManager?.commitBatch(
                                choices = choiceList.toIntArray(),
                                onProgress = { statusMessage.value = it },
                            )
                            statusMessage.value = "Committed! Waiting for opponent..."
                        } catch (e: Exception) {
                            Log.e(TAG, "Commit failed", e)
                            statusMessage.value = "Commit failed: ${e.message}"
                        }
                    }
                }
                "replayComplete" -> {
                    Log.i(TAG, "Replay finished")
                    statusMessage.value = "Replay complete."
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse Unity message: ${e.message}")
        }
    }

    private fun handleDeepLink(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme == "midnight" && uri.host == "kicks") {
            val matchAddress = uri.getQueryParameter("match")
            val network = uri.getQueryParameter("network") ?: "undeployed"
            if (matchAddress != null) {
                Log.i(TAG, "Deep link: match=$matchAddress, network=$network")
            }
        }
    }

    companion object {
        private const val TAG = "Kicks"
    }
}

@Composable
fun KicksApp(
    statusMessage: String?,
    lastChoices: String?,
    onCreateMatch: () -> Unit,
    onJoinMatch: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFF0A0A0A),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "MIDNIGHT",
                color = Color.White.copy(alpha = 0.4f),
                fontSize = 14.sp,
                letterSpacing = 6.sp,
            )
            Text(
                "KICKS",
                color = Color.White,
                fontSize = 48.sp,
                fontWeight = FontWeight.W200,
                letterSpacing = 8.sp,
            )

            Spacer(modifier = Modifier.height(64.dp))

            MenuButton("CREATE MATCH", onClick = onCreateMatch)
            Spacer(modifier = Modifier.height(16.dp))
            MenuButton("JOIN MATCH", onClick = onJoinMatch)

            // Status area
            if (statusMessage != null || lastChoices != null) {
                Spacer(modifier = Modifier.height(48.dp))
                if (lastChoices != null) {
                    Text(
                        lastChoices,
                        color = Color(0xFF64B5F6),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 4.sp,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                if (statusMessage != null) {
                    Text(
                        statusMessage,
                        color = Color.White.copy(alpha = 0.5f),
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            Spacer(modifier = Modifier.height(48.dp))
            Text(
                "World Cup 2026",
                color = Color.White.copy(alpha = 0.2f),
                fontSize = 12.sp,
                letterSpacing = 4.sp,
            )
        }
    }
}

/** Test seed — same as BBoard's alice wallet. */
private val TEST_SEED = hexToBytes(
    "7dc468f62278cd0c14b6674f31531a90b64599d657d3c7ab2adb63395d647f7a" +
    "505de6428fcf8b0d208873f4d5e2a1340c14688067477542f53c48dfea817da4"
)

private fun hexToBytes(hex: String): ByteArray =
    ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

@Composable
private fun MenuButton(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.08f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 3.sp,
        )
    }
}
