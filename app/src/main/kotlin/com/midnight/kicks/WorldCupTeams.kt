package com.midnight.kicks

import androidx.compose.ui.graphics.Color
import kotlin.math.roundToInt

/**
 * Local-only World Cup team roster + kits. Drives the player-customization UI
 * (the "pick your kit while the contract deploys" flow) and the appearance sent
 * to Unity at match start. None of this is on-chain — it's pure local cosmetics
 * mapped to the game's [ShooterAppearance].
 *
 * Kit colours are best-effort approximations of each nation's real home/away
 * strips — close enough to be recognisable on the pitch, not official Pantone.
 */

/** A jersey/shorts/socks colour triple — one strip (home or away). */
data class KitColors(val jersey: Color, val shorts: Color, val socks: Color)

/** Which strip the player wears; the opponent is auto-assigned a contrasting one. */
enum class Jersey { HOME, AWAY }

/**
 * One national team. [code] is the ISO-3166 alpha-2 used to render the flag
 * emoji; [home]/[away] are the two strips the player can pick between.
 */
data class Team(
    val code: String,
    val name: String,
    val home: KitColors,
    val away: KitColors,
) {
    /** Flag emoji from the ISO-2 code (regional-indicator pair). */
    val flag: String
        get() = buildString { code.uppercase().forEach { appendCodePoint(0x1F1E6 + (it - 'A')) } }

    /** The strip for the requested [jersey] variant. */
    fun kit(jersey: Jersey): KitColors = if (jersey == Jersey.AWAY) away else home
}

/** `#RRGGBB` for the Unity bridge (JsonUtility-friendly). */
fun Color.toHex(): String =
    "#%02X%02X%02X".format((red * 255).roundToInt(), (green * 255).roundToInt(), (blue * 255).roundToInt())

private fun kit(jersey: Long, shorts: Long, socks: Long) =
    KitColors(Color(jersey), Color(shorts), Color(socks))

/**
 * The 48-team field for World Cup 2026. Hosts + a representative spread across
 * every confederation (qualification isn't final, so this is a curated 48 of
 * the strongest/most-recognisable sides rather than the literal bracket).
 */
val WORLD_CUP_TEAMS: List<Team> = listOf(
    // ── Hosts (CONCACAF) ──
    Team("MX", "Mexico", kit(0xFF006847, 0xFFF0F0F0, 0xFFCE1126), kit(0xFF101010, 0xFF101010, 0xFF101010)),
    Team("US", "United States", kit(0xFFFFFFFF, 0xFF0A1F44, 0xFFFFFFFF), kit(0xFF0A1F44, 0xFF0A1F44, 0xFFB31942)),
    Team("CA", "Canada", kit(0xFFD52B1E, 0xFFD52B1E, 0xFFD52B1E), kit(0xFFFFFFFF, 0xFFFFFFFF, 0xFFD52B1E)),

    // ── UEFA ──
    Team("FR", "France", kit(0xFF22368E, 0xFFFFFFFF, 0xFFB31942), kit(0xFFFFFFFF, 0xFFFFFFFF, 0xFF22368E)),
    Team("GB", "England", kit(0xFFFFFFFF, 0xFF1A2B5E, 0xFFFFFFFF), kit(0xFFC8102E, 0xFFC8102E, 0xFFC8102E)),
    Team("ES", "Spain", kit(0xFFC60B1E, 0xFF1A2B5E, 0xFF1A2B5E), kit(0xFF101010, 0xFF101010, 0xFF101010)),
    Team("DE", "Germany", kit(0xFFFFFFFF, 0xFF101010, 0xFFFFFFFF), kit(0xFF101010, 0xFF101010, 0xFF101010)),
    Team("IT", "Italy", kit(0xFF0066A8, 0xFFFFFFFF, 0xFF0066A8), kit(0xFFFFFFFF, 0xFFFFFFFF, 0xFF0066A8)),
    Team("PT", "Portugal", kit(0xFFA50021, 0xFFA50021, 0xFF006847), kit(0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF)),
    Team("NL", "Netherlands", kit(0xFFFF6A13, 0xFF101010, 0xFFFF6A13), kit(0xFFFFFFFF, 0xFF101010, 0xFFFFFFFF)),
    Team("BE", "Belgium", kit(0xFFCE1126, 0xFFCE1126, 0xFFCE1126), kit(0xFFFDDA24, 0xFFFDDA24, 0xFF101010)),
    Team("HR", "Croatia", kit(0xFFFF0000, 0xFFFFFFFF, 0xFF1A2B5E), kit(0xFFFFFFFF, 0xFF1A2B5E, 0xFFFFFFFF)),
    Team("DK", "Denmark", kit(0xFFC8102E, 0xFFFFFFFF, 0xFFC8102E), kit(0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF)),
    Team("CH", "Switzerland", kit(0xFFD52B1E, 0xFFFFFFFF, 0xFFD52B1E), kit(0xFFFFFFFF, 0xFFFFFFFF, 0xFFD52B1E)),
    Team("PL", "Poland", kit(0xFFFFFFFF, 0xFFDC143C, 0xFFFFFFFF), kit(0xFFDC143C, 0xFFDC143C, 0xFFDC143C)),
    Team("RS", "Serbia", kit(0xFFC6363C, 0xFF1A2B5E, 0xFFFFFFFF), kit(0xFFFFFFFF, 0xFFFFFFFF, 0xFF1A2B5E)),
    Team("UA", "Ukraine", kit(0xFFFFD500, 0xFFFFD500, 0xFFFFD500), kit(0xFF0057B7, 0xFF0057B7, 0xFF0057B7)),
    Team("SE", "Sweden", kit(0xFFFECC00, 0xFF006AA7, 0xFFFECC00), kit(0xFF0A1F44, 0xFF0A1F44, 0xFF0A1F44)),
    Team("AT", "Austria", kit(0xFFED2939, 0xFFFFFFFF, 0xFFED2939), kit(0xFFFFFFFF, 0xFF101010, 0xFFFFFFFF)),

    // ── CONMEBOL ──
    Team("BR", "Brazil", kit(0xFFFFDF00, 0xFF002776, 0xFFFFFFFF), kit(0xFF002776, 0xFFFFFFFF, 0xFF002776)),
    Team("AR", "Argentina", kit(0xFF75AADB, 0xFF101010, 0xFFFFFFFF), kit(0xFF0A1F44, 0xFF0A1F44, 0xFF0A1F44)),
    Team("UY", "Uruguay", kit(0xFF5CB8E6, 0xFF101010, 0xFF101010), kit(0xFFFFFFFF, 0xFF101010, 0xFFFFFFFF)),
    Team("CO", "Colombia", kit(0xFFFCD116, 0xFF003893, 0xFFCE1126), kit(0xFF003893, 0xFF003893, 0xFF003893)),
    Team("CL", "Chile", kit(0xFFD52B1E, 0xFF0033A0, 0xFFFFFFFF), kit(0xFFFFFFFF, 0xFFFFFFFF, 0xFFD52B1E)),
    Team("EC", "Ecuador", kit(0xFFFFDD00, 0xFF034EA2, 0xFFCE1126), kit(0xFF0A1F44, 0xFF0A1F44, 0xFF0A1F44)),
    Team("PE", "Peru", kit(0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF), kit(0xFFD91023, 0xFFD91023, 0xFFD91023)),
    Team("PY", "Paraguay", kit(0xFFD52B1E, 0xFF0038A8, 0xFF0038A8), kit(0xFF0038A8, 0xFF0038A8, 0xFF0038A8)),

    // ── CONCACAF ──
    Team("CR", "Costa Rica", kit(0xFFCE1126, 0xFF002B7F, 0xFFFFFFFF), kit(0xFFFFFFFF, 0xFFFFFFFF, 0xFF002B7F)),
    Team("JM", "Jamaica", kit(0xFFFFB915, 0xFF101010, 0xFF009B3A), kit(0xFF009B3A, 0xFF101010, 0xFFFFB915)),
    Team("PA", "Panama", kit(0xFFD21034, 0xFFD21034, 0xFFD21034), kit(0xFFFFFFFF, 0xFFFFFFFF, 0xFF005293)),
    Team("HN", "Honduras", kit(0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF), kit(0xFF0073CF, 0xFF0073CF, 0xFF0073CF)),

    // ── CAF ──
    Team("SN", "Senegal", kit(0xFFFFFFFF, 0xFFFFFFFF, 0xFF00853F), kit(0xFF00853F, 0xFF00853F, 0xFF00853F)),
    Team("MA", "Morocco", kit(0xFFC1272D, 0xFFC1272D, 0xFF006233), kit(0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF)),
    Team("NG", "Nigeria", kit(0xFF008751, 0xFF008751, 0xFF008751), kit(0xFFFFFFFF, 0xFF008751, 0xFFFFFFFF)),
    Team("CM", "Cameroon", kit(0xFF007A5E, 0xFFCE1126, 0xFFFCD116), kit(0xFFCE1126, 0xFFCE1126, 0xFFCE1126)),
    Team("EG", "Egypt", kit(0xFFCE1126, 0xFFFFFFFF, 0xFF101010), kit(0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF)),
    Team("GH", "Ghana", kit(0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF), kit(0xFFCE1126, 0xFFFCD116, 0xFF006B3F)),
    Team("DZ", "Algeria", kit(0xFFFFFFFF, 0xFFFFFFFF, 0xFF006233), kit(0xFF006233, 0xFFFFFFFF, 0xFF006233)),
    Team("CI", "Ivory Coast", kit(0xFFFF8200, 0xFFFF8200, 0xFFFF8200), kit(0xFF006233, 0xFFFFFFFF, 0xFFFFFFFF)),
    Team("TN", "Tunisia", kit(0xFFE70013, 0xFFFFFFFF, 0xFFE70013), kit(0xFFFFFFFF, 0xFFFFFFFF, 0xFFE70013)),
    Team("ZA", "South Africa", kit(0xFFFFB81C, 0xFF007749, 0xFFFFFFFF), kit(0xFF007749, 0xFFFFB81C, 0xFF007749)),

    // ── AFC ──
    Team("JP", "Japan", kit(0xFF0033A0, 0xFF0033A0, 0xFF0033A0), kit(0xFFFFFFFF, 0xFFFFFFFF, 0xFF0033A0)),
    Team("KR", "South Korea", kit(0xFFCD2E3A, 0xFF101010, 0xFFCD2E3A), kit(0xFF003478, 0xFF003478, 0xFF003478)),
    Team("AU", "Australia", kit(0xFFFFCD00, 0xFF00843D, 0xFFFFCD00), kit(0xFF00843D, 0xFFFFCD00, 0xFF00843D)),
    Team("SA", "Saudi Arabia", kit(0xFF006C35, 0xFFFFFFFF, 0xFF006C35), kit(0xFFFFFFFF, 0xFF006C35, 0xFFFFFFFF)),
    Team("IR", "Iran", kit(0xFFFFFFFF, 0xFFFFFFFF, 0xFFDA0000), kit(0xFFDA0000, 0xFFDA0000, 0xFFDA0000)),
    Team("QA", "Qatar", kit(0xFF8A1538, 0xFFFFFFFF, 0xFF8A1538), kit(0xFFFFFFFF, 0xFF8A1538, 0xFFFFFFFF)),

    // ── OFC ──
    Team("NZ", "New Zealand", kit(0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF), kit(0xFF101010, 0xFF101010, 0xFF101010)),
)

/** Lookup by ISO-2 code; falls back to the first team (host Mexico) if unknown. */
fun teamByCode(code: String?): Team =
    WORLD_CUP_TEAMS.firstOrNull { it.code == code } ?: WORLD_CUP_TEAMS.first()

/**
 * A kit that visually contrasts [playerKit] for the opponent, so the two sides
 * never blend. PvP can't see the opponent's local choice (no on-chain sync), so
 * the device picks the contrast itself: a near-white strip against a dark player
 * jersey, a charcoal strip against a light one.
 */
fun contrastingOpponentKit(playerKit: KitColors): KitColors {
    val light = playerKit.jersey.luminance() > 0.5f
    return if (light) {
        KitColors(Color(0xFF1A1A1A), Color(0xFF1A1A1A), Color(0xFF1A1A1A))
    } else {
        KitColors(Color(0xFFF0F0F0), Color(0xFFF0F0F0), Color(0xFFF0F0F0))
    }
}

/** Rec. 601 relative luminance, 0 (black) … 1 (white). */
private fun Color.luminance(): Float = 0.299f * red + 0.587f * green + 0.114f * blue
