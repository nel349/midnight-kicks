package com.midnight.kicks

import android.content.Context

/**
 * The player's local cosmetic identity — display name, chosen national team and
 * kit variant. Never leaves the device or touches the chain; it only drives the
 * customization UI and the appearance handed to Unity at match start.
 */
data class PlayerProfile(
    val name: String,
    val teamCode: String,
    val jersey: Jersey,
) {
    val team: Team get() = teamByCode(teamCode)
    val kit: KitColors get() = team.kit(jersey)

    companion object {
        /** First-run default: a host nation, home strip, blank name. */
        val DEFAULT = PlayerProfile(name = "", teamCode = "MX", jersey = Jersey.HOME)
    }
}

/**
 * SharedPreferences-backed persistence for [PlayerProfile] — the same plain-prefs
 * pattern as the network preference ([NetworkPref]); the data isn't sensitive.
 * Returns [PlayerProfile.DEFAULT] until the player customizes.
 */
object PlayerProfileStore {
    private const val PREFS = "kicks_player_profile"
    private const val KEY_NAME = "name"
    private const val KEY_TEAM = "team_code"
    private const val KEY_JERSEY = "jersey"

    fun load(ctx: Context): PlayerProfile {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return PlayerProfile(
            name = p.getString(KEY_NAME, "").orEmpty(),
            teamCode = p.getString(KEY_TEAM, PlayerProfile.DEFAULT.teamCode) ?: PlayerProfile.DEFAULT.teamCode,
            jersey = runCatching { Jersey.valueOf(p.getString(KEY_JERSEY, "").orEmpty()) }
                .getOrDefault(Jersey.HOME),
        )
    }

    fun save(ctx: Context, profile: PlayerProfile) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_NAME, profile.name)
            .putString(KEY_TEAM, profile.teamCode)
            .putString(KEY_JERSEY, profile.jersey.name)
            .apply()
    }

    /** True once the player has saved a non-blank name — gates the first-time prompt. */
    fun isCustomized(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_NAME, "")?.isNotBlank() == true
}
