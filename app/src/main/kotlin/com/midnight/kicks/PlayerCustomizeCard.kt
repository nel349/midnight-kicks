package com.midnight.kicks

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * "Customize your player" — name, national team and home/away kit. Designed to
 * fill the dead time while a match contract deploys (see [CreateMatchScreen]):
 * the player builds their look while the chain catches up, and the choice is
 * persisted locally + handed to Unity at kickoff. Nothing here is on-chain.
 *
 * Stateless: the host owns the [profile] and persists each [onProfileChange].
 */
@Composable
fun PlayerCustomizeCard(
    profile: PlayerProfile,
    onProfileChange: (PlayerProfile) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "CUSTOMIZE YOUR PLAYER",
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 12.sp,
            letterSpacing = 4.sp,
        )
        Spacer(Modifier.height(20.dp))

        KitPreview(team = profile.team, jersey = profile.jersey, name = profile.name)

        Spacer(Modifier.height(20.dp))

        OutlinedTextField(
            value = profile.name,
            onValueChange = { onProfileChange(profile.copy(name = it.take(16))) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = {
                Text("YOUR NAME", color = Color.White.copy(alpha = 0.3f), letterSpacing = 2.sp)
            },
            textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, letterSpacing = 2.sp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color.White.copy(alpha = 0.5f),
                unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                cursorColor = Color.White,
            ),
        )

        Spacer(Modifier.height(16.dp))

        JerseySegmented(
            selected = profile.jersey,
            onSelect = { onProfileChange(profile.copy(jersey = it)) },
        )

        Spacer(Modifier.height(20.dp))

        Text(
            "SELECT NATION",
            color = Color.White.copy(alpha = 0.5f),
            fontSize = 11.sp,
            letterSpacing = 4.sp,
        )
        Spacer(Modifier.height(12.dp))

        CountryGrid(
            selectedCode = profile.teamCode,
            onSelect = { onProfileChange(profile.copy(teamCode = it)) },
        )
    }
}

/** Shirt + shorts + socks swatch beside the player's name and nation. */
@Composable
private fun KitPreview(team: Team, jersey: Jersey, name: String) {
    val kit = team.kit(jersey)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            KitPiece(kit.jersey, width = 64.dp, height = 54.dp)
            Spacer(Modifier.height(3.dp))
            KitPiece(kit.shorts, width = 50.dp, height = 22.dp)
            Spacer(Modifier.height(3.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                KitPiece(kit.socks, width = 11.dp, height = 18.dp)
                KitPiece(kit.socks, width = 11.dp, height = 18.dp)
            }
        }
        Spacer(Modifier.width(20.dp))
        Column {
            Text(
                name.ifBlank { "YOUR NAME" },
                color = if (name.isBlank()) Color.White.copy(alpha = 0.35f) else Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "${team.flag}  ${team.name}",
                color = Color.White.copy(alpha = 0.75f),
                fontSize = 14.sp,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                if (jersey == Jersey.AWAY) "AWAY KIT" else "HOME KIT",
                color = Color.White.copy(alpha = 0.4f),
                fontSize = 11.sp,
                letterSpacing = 2.sp,
            )
        }
    }
}

/** One coloured kit element, with a hairline so white strips read on dark. */
@Composable
private fun KitPiece(color: Color, width: androidx.compose.ui.unit.Dp, height: androidx.compose.ui.unit.Dp) {
    Box(
        Modifier
            .size(width = width, height = height)
            .clip(RoundedCornerShape(4.dp))
            .background(color)
            .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(4.dp)),
    )
}

/** HOME / AWAY segmented toggle. */
@Composable
private fun JerseySegmented(selected: Jersey, onSelect: (Jersey) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.06f))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Jersey.entries.forEach { option ->
            val active = option == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .kicksPressable(shape = RoundedCornerShape(9.dp)) { onSelect(option) }
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (active) Color.White.copy(alpha = 0.16f) else Color.Transparent)
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (option == Jersey.AWAY) "AWAY" else "HOME",
                    color = if (active) Color.White else Color.White.copy(alpha = 0.45f),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 3.sp,
                )
            }
        }
    }
}

/** Scrollable 48-nation grid of flag tiles; the selected nation is ringed. */
@Composable
private fun CountryGrid(selectedCode: String, onSelect: (String) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(4),
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(WORLD_CUP_TEAMS, key = { it.code }) { team ->
            val active = team.code == selectedCode
            Column(
                modifier = Modifier
                    .kicksPressable(shape = RoundedCornerShape(10.dp)) { onSelect(team.code) }
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.White.copy(alpha = if (active) 0.18f else 0.05f))
                    .border(
                        width = if (active) 1.5.dp else 1.dp,
                        color = if (active) Color.White.copy(alpha = 0.6f) else Color.White.copy(alpha = 0.08f),
                        shape = RoundedCornerShape(10.dp),
                    )
                    .padding(vertical = 8.dp, horizontal = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(team.flag, fontSize = 24.sp)
                Spacer(Modifier.height(3.dp))
                Text(
                    team.code,
                    color = if (active) Color.White else Color.White.copy(alpha = 0.6f),
                    fontSize = 10.sp,
                    letterSpacing = 1.sp,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
    }
}
