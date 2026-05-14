package com.midnight.kicks

import android.util.Log
import com.midnight.kuira.core.compact.MidnightConfig
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive

/**
 * Watches a deployed contract's on-chain state by polling the indexer.
 *
 * Why polling, not subscription:
 *   The Kuira SDK exposes `IndexerClient.subscribeToBlocks()` privately
 *   inside `MidnightSdk` — it isn't reachable from app code. Until that's
 *   surfaced (PLAN.md wishlist #2), we poll `MidnightConfig.queryState`.
 *   3s interval gives ~1.5s average detection latency for a state change
 *   on PREPROD's ~6s block time, well under the typical match's commit /
 *   reveal cadence.
 *
 * Lifecycle:
 *   `snapshots()` returns a cold Flow. Collect it from a coroutine scope;
 *   cancelling that scope (or the collection) stops the poll loop. The
 *   flow uses `distinctUntilChanged` so consumers see one emission per
 *   actual state change.
 *
 * Errors:
 *   Individual `queryState` failures are logged and swallowed — the next
 *   poll picks up where we left off. The 3s interval is its own backoff;
 *   no retry loop needed. If you need a hard "indexer is dead" signal,
 *   wrap the flow with a timeout from the caller side.
 *
 * Future:
 *   When the SDK promotes block subscriptions (or adds
 *   `MidnightContract.stateFlow()`), the body of `snapshots()` becomes a
 *   pass-through; the public surface stays.
 */
class StatePoller(
    private val config: MidnightConfig,
    private val address: String,
    private val pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
) {
    init {
        Log.i(TAG, "poller for $address ready (interval=${pollIntervalMs}ms)")
    }

    /**
     * Cold flow of contract-state snapshots. Emits once per change, never
     * emits null. Completes only when the collecting scope is cancelled.
     */
    fun snapshots(): Flow<ContractStateSnapshot> = flow {
        while (currentCoroutineContext().isActive) {
            val snapshot = readOnce()
            if (snapshot != null) emit(snapshot)
            delay(pollIntervalMs)
        }
    }.distinctUntilChanged()

    /**
     * One-shot read — useful from synchronous code paths (e.g. confirming
     * a transaction landed before advancing state) without spinning up a
     * flow collection.
     */
    suspend fun readOnce(): ContractStateSnapshot? {
        return try {
            val state = config.queryState(address) ?: return null
            ContractStateSnapshot.parse(state).also { snap ->
                if (snap != null) Log.d(TAG, "snapshot: ${snap.summary()}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "queryState failed for $address — will retry in ${pollIntervalMs}ms: ${e.message}")
            null
        }
    }

    companion object {
        private const val TAG = "StatePoller"
        /** 3s polling — half a typical PREPROD block, balances latency vs load. */
        const val DEFAULT_POLL_INTERVAL_MS = 3_000L
    }
}
