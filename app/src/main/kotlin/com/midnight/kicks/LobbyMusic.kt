package com.midnight.kicks

import android.content.Context
import android.media.MediaPlayer
import android.util.Log

/**
 * Lobby/menu theme music — the looping intro theme that plays while the Kicks
 * menu is on screen.
 *
 * **Why a plain MediaPlayer (not the Unity AudioManager):** the match audio
 * (La Bombonera drums + reactions) lives in Unity's `AudioManager`, which only
 * runs in the `:unity` process during a match. The lobby is the Compose
 * [KicksActivity] in the **main** process, so its music is ordinary Android
 * playback.
 *
 * [KicksActivity] drives the lifecycle: [resume] on `onResume`, [pause] on
 * `onPause`. Because launching a match backgrounds the menu (→ `onPause`), the
 * theme automatically ducks out when the Unity match audio takes over, and
 * [resume] brings it back when the user returns to the menu. [release] on
 * `onDestroy`.
 *
 * Object (not per-Activity) because the menu is `singleTask` — one instance —
 * and the player should survive a config change without restarting the track.
 */
object LobbyMusic {
    private const val TAG = "LobbyMusic"

    /** Soft bed level — present but never competes with the user reading the menu. */
    private const val VOLUME = 0.5f

    private var player: MediaPlayer? = null

    /** Start (lazily creating) or resume the looping theme. Safe to call repeatedly. */
    fun resume(context: Context) {
        val existing = player
        if (existing != null) {
            if (!existing.isPlaying) runCatching { existing.start() }
            return
        }
        val created = runCatching {
            MediaPlayer.create(context.applicationContext, R.raw.intro_theme)?.apply {
                isLooping = true
                setVolume(VOLUME, VOLUME)
            }
        }.getOrNull()
        if (created == null) {
            Log.w(TAG, "could not create intro-theme player (missing res/raw/intro_theme?)")
            return
        }
        player = created
        runCatching { created.start() }
    }

    /** Pause without releasing — keeps the playback position for the next [resume]. */
    fun pause() {
        player?.let { if (it.isPlaying) runCatching { it.pause() } }
    }

    /** Release the player entirely (call from the menu's `onDestroy`). */
    fun release() {
        player?.let { runCatching { it.release() } }
        player = null
    }
}
