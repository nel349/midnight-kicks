package com.midnight.kicks

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * A typed view of the penalty contract's on-chain ledger.
 *
 * `MidnightConfig.queryState(address)` returns a positional `JSONArray` of
 * SCALE-decoded cells. Each cell is a JSONObject like
 * `{"type":"cell", "number": 1}` for Booleans/Uints/Counters, or
 * `{"type":"cell", "hex": "abc…"}` for Bytes<N>. This class translates that
 * raw shape into named fields matching `penalty.compact`.
 *
 * Cell indices verified against the compiled contract at
 * `assets/runtime/penalty-contract.js:3603+` — every `ledger()` getter
 * encodes its `idx` path explicitly, so the mapping below is not a guess.
 *
 * The SDK does not yet expose a typed ledger wrapper for arbitrary
 * contracts; this parsing pattern is what every Kuira dApp ends up writing.
 * See PLAN.md "SDK connector wishlist" item #9 (codegen typed ledger from
 * `.compact`).
 */
data class ContractStateSnapshot(
    val phase: Int,
    val player1: ByteArray,
    val player2: ByteArray,
    val p1Commitment: ByteArray,
    val p2Commitment: ByteArray,
    val p1Committed: Boolean,
    val p2Committed: Boolean,
    val p1Choices: IntArray,  // 5 elements, all 0 before reveal
    val p2Choices: IntArray,  // 5 elements, all 0 before reveal
    val p1Revealed: Boolean,
    val p2Revealed: Boolean,
    val p1Score: Int,
    val p2Score: Int,
    val winner: ByteArray,
    val isDraw: Boolean,
    val deadline: Long,
    val sdRound: Int,
) {
    /** Both players have committed but neither has revealed yet. */
    val bothCommitted: Boolean get() = p1Committed && p2Committed

    /** Both players have revealed — match is resolved (or in sudden death). */
    val bothRevealed: Boolean get() = p1Revealed && p2Revealed

    /** Has anyone joined yet (P2 commit slot has data). */
    val matchJoined: Boolean get() = !player2.all { it == 0.toByte() }

    /** Concise log/debug summary. */
    fun summary(): String =
        "phase=$phase  p1Committed=$p1Committed  p2Committed=$p2Committed  " +
        "p1Revealed=$p1Revealed  p2Revealed=$p2Revealed  " +
        "score=$p1Score-$p2Score  isDraw=$isDraw  sdRound=$sdRound"

    // Data class equality with arrays needs explicit overrides — IntelliJ-style.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as ContractStateSnapshot
        if (phase != other.phase) return false
        if (!player1.contentEquals(other.player1)) return false
        if (!player2.contentEquals(other.player2)) return false
        if (!p1Commitment.contentEquals(other.p1Commitment)) return false
        if (!p2Commitment.contentEquals(other.p2Commitment)) return false
        if (p1Committed != other.p1Committed) return false
        if (p2Committed != other.p2Committed) return false
        if (!p1Choices.contentEquals(other.p1Choices)) return false
        if (!p2Choices.contentEquals(other.p2Choices)) return false
        if (p1Revealed != other.p1Revealed) return false
        if (p2Revealed != other.p2Revealed) return false
        if (p1Score != other.p1Score) return false
        if (p2Score != other.p2Score) return false
        if (!winner.contentEquals(other.winner)) return false
        if (isDraw != other.isDraw) return false
        if (deadline != other.deadline) return false
        if (sdRound != other.sdRound) return false
        return true
    }

    override fun hashCode(): Int {
        var r = phase
        r = 31 * r + player1.contentHashCode()
        r = 31 * r + player2.contentHashCode()
        r = 31 * r + p1Commitment.contentHashCode()
        r = 31 * r + p2Commitment.contentHashCode()
        r = 31 * r + p1Committed.hashCode()
        r = 31 * r + p2Committed.hashCode()
        r = 31 * r + p1Choices.contentHashCode()
        r = 31 * r + p2Choices.contentHashCode()
        r = 31 * r + p1Revealed.hashCode()
        r = 31 * r + p2Revealed.hashCode()
        r = 31 * r + p1Score
        r = 31 * r + p2Score
        r = 31 * r + winner.contentHashCode()
        r = 31 * r + isDraw.hashCode()
        r = 31 * r + deadline.hashCode()
        r = 31 * r + sdRound
        return r
    }

    companion object {
        // ── Cell indices from penalty-contract.js ledger() getters ──
        // Verified against the idx paths in the compiled contract; do NOT
        // reorder unless the .compact field declarations change.
        private const val CELL_PHASE = 0
        private const val CELL_PLAYER1 = 1
        private const val CELL_PLAYER2 = 2
        private const val CELL_P1_COMMITMENT = 3
        private const val CELL_P2_COMMITMENT = 4
        private const val CELL_P1_COMMITTED = 5
        private const val CELL_P2_COMMITTED = 6
        private const val CELL_P1C_FIRST = 7  // 7..11 inclusive
        private const val CELL_P2C_FIRST = 12 // 12..16 inclusive
        private const val CELL_P1_REVEALED = 17
        private const val CELL_P2_REVEALED = 18
        private const val CELL_P1_SCORE = 19
        private const val CELL_P2_SCORE = 20
        private const val CELL_WINNER = 21
        private const val CELL_IS_DRAW = 22
        private const val CELL_DEADLINE = 23
        private const val CELL_SD_ROUND = 24

        private const val EXPECTED_CELLS = 25
        private const val BYTES32_LEN = 32
        private const val CHOICES_PER_BATCH = 5
        private const val TAG = "ContractStateSnapshot"

        /**
         * Parse a state JSONArray from `MidnightConfig.queryState`. Returns
         * null (and logs at WARN) if the state shape doesn't match what we
         * expect (the contract hasn't deployed yet, or the .compact schema
         * has drifted and our cell-index map is stale).
         *
         * The SDK's state layout depends on the contract. Observed shapes:
         *  - BBoard: flat positional cells at the top, `{"number": N}`.
         *  - Penalty: split storage `[[10 cells], [15 cells]]`, each cell
         *    `{"hex": "…"}` SCALE-encoded.
         *
         * We treat both uniformly: recursively flatten any nested JSONArrays
         * into a single linear cell list, then index by field number.
         */
        fun parse(state: JSONArray): ContractStateSnapshot? {
            val cells = flattenCells(state)
            if (cells.size < EXPECTED_CELLS) {
                Log.w(TAG, "parse: expected $EXPECTED_CELLS cells, got ${cells.size} — dumping: $state")
                return null
            }
            return try {
                ContractStateSnapshot(
                    phase        = cellNumber(cells, CELL_PHASE).toInt(),
                    player1      = cellHex(cells, CELL_PLAYER1, BYTES32_LEN),
                    player2      = cellHex(cells, CELL_PLAYER2, BYTES32_LEN),
                    p1Commitment = cellHex(cells, CELL_P1_COMMITMENT, BYTES32_LEN),
                    p2Commitment = cellHex(cells, CELL_P2_COMMITMENT, BYTES32_LEN),
                    p1Committed  = cellBoolean(cells, CELL_P1_COMMITTED),
                    p2Committed  = cellBoolean(cells, CELL_P2_COMMITTED),
                    p1Choices    = IntArray(CHOICES_PER_BATCH) { cellNumber(cells, CELL_P1C_FIRST + it).toInt() },
                    p2Choices    = IntArray(CHOICES_PER_BATCH) { cellNumber(cells, CELL_P2C_FIRST + it).toInt() },
                    p1Revealed   = cellBoolean(cells, CELL_P1_REVEALED),
                    p2Revealed   = cellBoolean(cells, CELL_P2_REVEALED),
                    p1Score      = cellNumber(cells, CELL_P1_SCORE).toInt(),
                    p2Score      = cellNumber(cells, CELL_P2_SCORE).toInt(),
                    winner       = cellHex(cells, CELL_WINNER, BYTES32_LEN),
                    isDraw       = cellBoolean(cells, CELL_IS_DRAW),
                    deadline     = cellNumber(cells, CELL_DEADLINE),
                    sdRound      = cellNumber(cells, CELL_SD_ROUND).toInt(),
                )
            } catch (e: Exception) {
                Log.w(TAG, "parse failed — cell-index map likely out of sync with .compact", e)
                null
            }
        }

        /**
         * Walk an arbitrarily-nested tree of JSONArrays containing JSONObject
         * cells and return a flat positional list. Handles BBoard's flat
         * layout, penalty's split `[[10], [15]]`, and any other nesting the
         * SDK may surface in the future.
         */
        internal fun flattenCells(state: JSONArray): List<JSONObject> {
            val out = mutableListOf<JSONObject>()
            walk(state, out)
            return out
        }

        private fun walk(node: Any?, out: MutableList<JSONObject>) {
            when (node) {
                is JSONObject -> out.add(node)
                is JSONArray -> for (i in 0 until node.length()) walk(node.opt(i), out)
                else -> {} // ignore primitives / nulls
            }
        }

        /**
         * Booleans encode either as `{"number": 0|1}` (BBoard) or
         * `{"hex": "00"|"01"}` (penalty). Treat any non-empty / non-zero
         * value as true.
         */
        private fun cellBoolean(cells: List<JSONObject>, index: Int): Boolean {
            val cell = cells.getOrNull(index) ?: return false
            cell.opt("number").let { if (it is Number) return it.toInt() != 0 }
            val hex = cell.optString("hex", "")
            return hex.isNotEmpty() && hex.any { it != '0' }
        }

        /**
         * Numbers (Uint<N>, Counter, Phase enum) encode as `{"number": N}`
         * or `{"hex": "…"}` (SCALE little-endian). Empty hex = 0.
         */
        private fun cellNumber(cells: List<JSONObject>, index: Int): Long {
            val cell = cells.getOrNull(index) ?: return 0L
            cell.opt("number").let { if (it is Number) return it.toLong() }
            return parseHexLittleEndian(cell.optString("hex", ""))
        }

        /** Bytes<N> encode as `{"hex": "abcd…"}`. Returns zeros for missing. */
        private fun cellHex(cells: List<JSONObject>, index: Int, expectedLen: Int): ByteArray {
            val hex = cells.getOrNull(index)?.optString("hex", "") ?: ""
            if (hex.isEmpty()) return ByteArray(expectedLen)
            return ByteArray(hex.length / 2) {
                hex.substring(it * 2, it * 2 + 2).toInt(16).toByte()
            }
        }

        /** Parse a little-endian hex string into a Long (SCALE codec convention). */
        private fun parseHexLittleEndian(hex: String): Long {
            if (hex.isEmpty()) return 0L
            // Up to 8 bytes for a Long; any more we just truncate (the
            // affected fields — Counter / Uint<64> — fit a Long).
            val byteCount = (hex.length / 2).coerceAtMost(8)
            var result = 0L
            for (i in 0 until byteCount) {
                val b = hex.substring(i * 2, i * 2 + 2).toInt(16).toLong() and 0xFFL
                result = result or (b shl (i * 8))
            }
            return result
        }
    }
}
