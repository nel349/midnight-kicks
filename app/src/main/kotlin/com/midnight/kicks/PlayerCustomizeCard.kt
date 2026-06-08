package com.midnight.kicks

import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
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

@Composable
private fun KitPreview(team: Team, jersey: Jersey, name: String) {
    val kit = team.kit(jersey)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier.size(width = 124.dp, height = 134.dp),
            contentAlignment = Alignment.Center,
        ) {
            // Platform so dark away kits (navy / black) don't vanish on the
            // near-black screen.
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color.White.copy(alpha = 0.07f)),
            )
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        Brush.radialGradient(
                            colors = listOf(kit.jersey.copy(alpha = 0.22f), Color.Transparent),
                        ),
                    ),
            )
            KitAvatar(kit = kit, modifier = Modifier.size(width = 108.dp, height = 126.dp))
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

@Composable
private fun KitAvatar(kit: KitColors, modifier: Modifier = Modifier) {
    val stroke = Stroke(width = 2.5f)
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val cx = w / 2f

        // ── Jersey: flat-shouldered tee with short sleeves + V-neck ──
        val collarHalf = w * 0.08f
        val bodyHalf = w * 0.19f
        val sleeveOuter = w * 0.46f
        val shoulderY = h * 0.10f
        val sleeveBottomY = h * 0.27f
        val armpitY = h * 0.24f
        val hemY = h * 0.50f
        val collarBottomY = h * 0.17f
        val jersey = Path().apply {
            moveTo(cx - collarHalf, shoulderY)
            lineTo(cx - sleeveOuter, shoulderY)
            lineTo(cx - sleeveOuter, sleeveBottomY)
            lineTo(cx - bodyHalf, armpitY)
            lineTo(cx - bodyHalf, hemY)
            lineTo(cx + bodyHalf, hemY)
            lineTo(cx + bodyHalf, armpitY)
            lineTo(cx + sleeveOuter, sleeveBottomY)
            lineTo(cx + sleeveOuter, shoulderY)
            lineTo(cx + collarHalf, shoulderY)
            lineTo(cx, collarBottomY)
            close()
        }
        drawPath(jersey, kit.jersey)
        drawPath(jersey, outlineFor(kit.jersey), style = stroke)

        // ── Shorts: waistband + two legs with a centre split ──
        val waistY = h * 0.53f
        val shortsBottomY = h * 0.73f
        val crotchY = h * 0.66f
        val shortHalf = w * 0.185f
        val legInner = w * 0.035f
        val shorts = Path().apply {
            moveTo(cx - shortHalf, waistY)
            lineTo(cx + shortHalf, waistY)
            lineTo(cx + shortHalf, shortsBottomY)
            lineTo(cx + legInner, shortsBottomY)
            lineTo(cx, crotchY)
            lineTo(cx - legInner, shortsBottomY)
            lineTo(cx - shortHalf, shortsBottomY)
            close()
        }
        drawPath(shorts, kit.shorts)
        drawPath(shorts, outlineFor(kit.shorts), style = stroke)

        // ── Socks: a pair of rounded columns ──
        val sockTopY = h * 0.77f
        val sockH = h * 0.21f
        val sockW = w * 0.11f
        val sockGap = w * 0.04f
        val corner = CornerRadius(sockW * 0.45f, sockW * 0.45f)
        val sockOutline = outlineFor(kit.socks)
        for (left in listOf(cx - sockGap - sockW, cx + sockGap)) {
            drawRoundRect(kit.socks, topLeft = Offset(left, sockTopY), size = Size(sockW, sockH), cornerRadius = corner)
            drawRoundRect(sockOutline, topLeft = Offset(left, sockTopY), size = Size(sockW, sockH), cornerRadius = corner, style = stroke)
        }
    }
}

/**
 * A silhouette outline that contrasts the piece itself — a dark edge on light
 * kits, a light edge on dark kits — so navy / black / white strips all read on
 * the panel (fixes dark away kits vanishing against the near-black screen).
 */
private fun outlineFor(c: Color): Color {
    val luminance = 0.299f * c.red + 0.587f * c.green + 0.114f * c.blue
    return if (luminance > 0.55f) Color.Black.copy(alpha = 0.40f) else Color.White.copy(alpha = 0.55f)
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
        // Adaptive columns: 4-ish in portrait, more when wider (landscape/tablet);
        // shorter viewport in compact height so it doesn't dominate the scroll.
        columns = GridCells.Adaptive(minSize = 76.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(if (isCompactHeight()) 150.dp else 220.dp),
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
