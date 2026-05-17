package com.midnight.kicks

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * Persistent record of an in-flight match. Lets the user create a match,
 * leave the app, and resume from where they were — including a freshly
 * launched process where in-memory [MatchManager] state is gone.
 *
 * Stored fields:
 *  - [address] — penalty contract address, the match's identity
 *  - [role] — which side this device represents (P1 = creator, P2 = joiner)
 *  - [deadline] — unix seconds, mirrors the on-chain commit deadline; lets
 *    the UI mark a session "expired" without an extra chain round-trip
 *
 * The session is local-only today (SharedPrefs). Layer 2 of this design
 * is a Block Store-backed copy so the session also survives uninstall /
 * device hop — see PLAN.md "SDK connector wishlist" item #4 (serializable
 * state snapshot). When that lands, [KicksSessionStore] is the seam:
 * implementations stay drop-in compatible.
 *
 * Single active session for now; a `List<MatchSession>` is a trivial
 * extension when the product supports multiple concurrent matches.
 */
data class MatchSession(
    val address: String,
    val role: Player,
    val deadline: Long,
)

/**
 * Tiny wrapper around the kicks-private prefs file. Synchronous writes are
 * fine here — the blob is ~100 bytes and writes only happen at session
 * transition points (deploy, join, resolve), not in a hot path.
 */
class KicksSessionStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): MatchSession? {
        val address = prefs.getString(KEY_ADDRESS, null) ?: return null
        val roleName = prefs.getString(KEY_ROLE, null) ?: return null
        val deadline = prefs.getLong(KEY_DEADLINE, 0L)
        val role = runCatching { Player.valueOf(roleName) }.getOrNull() ?: return null
        return MatchSession(address = address, role = role, deadline = deadline)
    }

    fun save(session: MatchSession) {
        prefs.edit()
            .putString(KEY_ADDRESS, session.address)
            .putString(KEY_ROLE, session.role.name)
            .putLong(KEY_DEADLINE, session.deadline)
            .apply()
        Log.i(TAG, "Saved session: address=${session.address.take(20)}… role=${session.role}")
    }

    fun clear() {
        prefs.edit().clear().apply()
        Log.i(TAG, "Cleared session")
    }

    companion object {
        private const val TAG = "KicksSessionStore"
        private const val PREFS_NAME = "kicks_active_match"
        private const val KEY_ADDRESS = "address"
        private const val KEY_ROLE = "role"
        private const val KEY_DEADLINE = "deadline"
    }
}
