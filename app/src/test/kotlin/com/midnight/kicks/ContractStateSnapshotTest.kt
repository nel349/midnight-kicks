package com.midnight.kicks

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [ContractStateSnapshot] parsing.
 *
 * These tests guard the cell-index map against drift from
 * `penalty-contract.js`. If the .compact field order changes, exactly
 * these tests will start failing — far better than the silent "Match
 * complete" with garbage scores you'd see if the parser kept rolling.
 */
class ContractStateSnapshotTest {

    @Test
    fun `parse returns null for a too-short array`() {
        val short = JSONArray().apply { put(numberCell(0)) }
        assertNull(ContractStateSnapshot.parse(short))
    }

    @Test
    fun `parse returns null for an empty array`() {
        assertNull(ContractStateSnapshot.parse(JSONArray()))
    }

    @Test
    fun `parse unwraps nested state (cells inside outer container)`() {
        // The penalty contract's queryState returns the 25 ledger cells
        // nested at state[0] (idx path [0, N] in the compiled JS), not at
        // the top level. The parser must unwrap automatically.
        val nested = JSONArray().apply { put(defaultStateArray()) }
        val snap = ContractStateSnapshot.parse(nested)
        requireNotNull(snap)
        assertEquals(0, snap.phase)
        assertFalse(snap.p1Committed)
    }

    @Test
    fun `parse unwraps nested state with two outer elements`() {
        // Real on-chain payload had 2 top-level elements (e.g. ledger
        // record + metadata cell). The unwrap should still pick up the
        // inner ledger from state[0].
        val nested = JSONArray()
            .put(defaultStateArray())
            .put(numberCell(0))
        val snap = ContractStateSnapshot.parse(nested)
        requireNotNull(snap)
        assertEquals(0, snap.phase)
    }

    @Test
    fun `parse returns null when neither flat nor nested layout matches`() {
        // 2 number-cells at the top, no nested array — the diagnostic
        // path that originally fired in the live app with "got 2 cells".
        val malformed = JSONArray()
            .put(numberCell(0))
            .put(numberCell(1))
        assertNull(ContractStateSnapshot.parse(malformed))
    }

    // ── Real on-chain shape: split storage with hex-encoded cells ───────

    @Test
    fun `parse handles real split-storage layout (10 plus 15 cells, all hex)`() {
        // What MidnightConfig.queryState actually returns for the penalty
        // contract right after deploy: two sub-arrays of 10 and 15 hex
        // cells. Every field defaults to empty hex.
        val first = JSONArray().apply { repeat(10) { put(hexCell("")) } }
        val second = JSONArray().apply { repeat(15) { put(hexCell("")) } }
        val state = JSONArray().put(first).put(second)

        val snap = ContractStateSnapshot.parse(state)
        requireNotNull(snap)
        assertEquals(0, snap.phase)
        assertFalse(snap.p1Committed)
        assertFalse(snap.p2Committed)
        assertEquals(0, snap.p1Score)
    }

    @Test
    fun `parse reads player1 from real on-chain shape (only field set after deploy)`() {
        val player1Hex = "e8fde5e25c2d4c589f181c10042304f1e9badca11baabafeac4d53812a7b7c07"
        val first = JSONArray().apply {
            put(hexCell(""))        // phase
            put(hexCell(player1Hex)) // player1 ← the deploying P1
            repeat(8) { put(hexCell("")) }
        }
        val second = JSONArray().apply { repeat(15) { put(hexCell("")) } }
        val state = JSONArray().put(first).put(second)

        val snap = ContractStateSnapshot.parse(state)
        requireNotNull(snap)
        assertEquals(player1Hex.length / 2, snap.player1.size)
        assertEquals(0xe8.toByte(), snap.player1[0])
    }

    @Test
    fun `cellBoolean reads hex 01 as true`() {
        // After P1 commits, p1Committed (cell 5) flips to "01".
        val first = JSONArray().apply {
            repeat(5) { put(hexCell("")) }
            put(hexCell("01")) // p1Committed = true
            repeat(4) { put(hexCell("")) }
        }
        val second = JSONArray().apply { repeat(15) { put(hexCell("")) } }
        val snap = ContractStateSnapshot.parse(JSONArray().put(first).put(second))
        requireNotNull(snap)
        assertTrue(snap.p1Committed)
        assertFalse(snap.p2Committed)
    }

    @Test
    fun `cellNumber decodes little-endian hex`() {
        // p1Score is at flat index 19 = second[9]. Encode 3 as little-
        // endian hex "03".
        val first = JSONArray().apply { repeat(10) { put(hexCell("")) } }
        val second = JSONArray().apply {
            repeat(9) { put(hexCell("")) }
            put(hexCell("03"))           // p1Score = 3
            put(hexCell("02"))           // p2Score = 2
            repeat(5) { put(hexCell("")) }
        }
        val snap = ContractStateSnapshot.parse(JSONArray().put(first).put(second))
        requireNotNull(snap)
        assertEquals(3, snap.p1Score)
        assertEquals(2, snap.p2Score)
    }

    @Test
    fun `cellNumber handles multi-byte little-endian (deadline as Uint64)`() {
        // deadline = 0x12345678 = 305419896, little-endian "78563412".
        val first = JSONArray().apply { repeat(10) { put(hexCell("")) } }
        val second = JSONArray().apply {
            repeat(13) { put(hexCell("")) }
            put(hexCell("78563412"))  // deadline at flat index 23 = second[13]
            put(hexCell(""))
        }
        val snap = ContractStateSnapshot.parse(JSONArray().put(first).put(second))
        requireNotNull(snap)
        assertEquals(0x12345678L, snap.deadline)
    }

    private fun hexCellWithoutHexField(): JSONObject =
        JSONObject().put("type", "cell") // no hex, no number — pure default

    @Test
    fun `cellBoolean returns false for empty hex`() {
        val flat = JSONArray().apply { repeat(25) { put(hexCell("")) } }
        val snap = ContractStateSnapshot.parse(flat)
        requireNotNull(snap)
        assertFalse(snap.p1Committed)
        assertFalse(snap.isDraw)
    }

    @Test
    fun `parse decodes a fresh post-deploy state (all defaults)`() {
        val snap = ContractStateSnapshot.parse(defaultStateArray())
        requireNotNull(snap)

        assertEquals(0, snap.phase)
        assertFalse(snap.p1Committed)
        assertFalse(snap.p2Committed)
        assertFalse(snap.p1Revealed)
        assertFalse(snap.p2Revealed)
        assertFalse(snap.isDraw)
        assertEquals(0, snap.p1Score)
        assertEquals(0, snap.p2Score)
        assertEquals(0L, snap.deadline)
        assertEquals(0, snap.sdRound)
        assertEquals(5, snap.p1Choices.size)
        assertEquals(5, snap.p2Choices.size)
        assertArrayEquals(IntArray(5), snap.p1Choices)
        assertArrayEquals(IntArray(5), snap.p2Choices)
        assertEquals(32, snap.player1.size)
        assertEquals(32, snap.player2.size)
    }

    @Test
    fun `parse reads p1Committed flag from cell 5`() {
        val state = defaultStateArray().apply {
            put(CELL_P1_COMMITTED, numberCell(1))
        }
        val snap = ContractStateSnapshot.parse(state)
        requireNotNull(snap)
        assertTrue(snap.p1Committed)
        assertFalse(snap.p2Committed) // verify we read the right cell
    }

    @Test
    fun `parse reads p2Committed flag from cell 6`() {
        val state = defaultStateArray().apply {
            put(CELL_P2_COMMITTED, numberCell(1))
        }
        val snap = ContractStateSnapshot.parse(state)
        requireNotNull(snap)
        assertTrue(snap.p2Committed)
        assertFalse(snap.p1Committed)
    }

    @Test
    fun `parse reads p1Revealed and p2Revealed from cells 17-18`() {
        val state = defaultStateArray().apply {
            put(CELL_P1_REVEALED, numberCell(1))
            put(CELL_P2_REVEALED, numberCell(1))
        }
        val snap = ContractStateSnapshot.parse(state)
        requireNotNull(snap)
        assertTrue(snap.p1Revealed)
        assertTrue(snap.p2Revealed)
        assertTrue(snap.bothRevealed)
    }

    @Test
    fun `parse reads p1c0-p1c4 from cells 7 through 11`() {
        val choices = intArrayOf(0, 1, 2, 1, 0)
        val state = defaultStateArray().apply {
            choices.forEachIndexed { i, v -> put(7 + i, numberCell(v)) }
        }
        val snap = ContractStateSnapshot.parse(state)
        requireNotNull(snap)
        assertArrayEquals(choices, snap.p1Choices)
        // p2 should still be zeroed — verify our index math doesn't bleed.
        assertArrayEquals(IntArray(5), snap.p2Choices)
    }

    @Test
    fun `parse reads p2c0-p2c4 from cells 12 through 16`() {
        val choices = intArrayOf(2, 2, 0, 1, 2)
        val state = defaultStateArray().apply {
            choices.forEachIndexed { i, v -> put(12 + i, numberCell(v)) }
        }
        val snap = ContractStateSnapshot.parse(state)
        requireNotNull(snap)
        assertArrayEquals(choices, snap.p2Choices)
        assertArrayEquals(IntArray(5), snap.p1Choices)
    }

    @Test
    fun `parse decodes winner bytes from cell 21`() {
        val winnerHex = "ab".repeat(32)
        val state = defaultStateArray().apply {
            put(21, hexCell(winnerHex))
        }
        val snap = ContractStateSnapshot.parse(state)
        requireNotNull(snap)
        assertEquals(32, snap.winner.size)
        assertEquals(0xab.toByte(), snap.winner[0])
        assertEquals(0xab.toByte(), snap.winner[31])
    }

    @Test
    fun `parse reads scores from cells 19-20`() {
        val state = defaultStateArray().apply {
            put(19, numberCell(3))
            put(20, numberCell(2))
        }
        val snap = ContractStateSnapshot.parse(state)
        requireNotNull(snap)
        assertEquals(3, snap.p1Score)
        assertEquals(2, snap.p2Score)
    }

    @Test
    fun `parse reads deadline from cell 23 as Long`() {
        val state = defaultStateArray().apply {
            put(23, numberCell(1_780_000_000L))
        }
        val snap = ContractStateSnapshot.parse(state)
        requireNotNull(snap)
        assertEquals(1_780_000_000L, snap.deadline)
    }

    @Test
    fun `bothCommitted is true only when both flags are set`() {
        val onlyP1 = ContractStateSnapshot.parse(defaultStateArray().apply {
            put(CELL_P1_COMMITTED, numberCell(1))
        })!!
        assertFalse(onlyP1.bothCommitted)

        val both = ContractStateSnapshot.parse(defaultStateArray().apply {
            put(CELL_P1_COMMITTED, numberCell(1))
            put(CELL_P2_COMMITTED, numberCell(1))
        })!!
        assertTrue(both.bothCommitted)
    }

    @Test
    fun `matchJoined is false when player2 is all zeros`() {
        val snap = ContractStateSnapshot.parse(defaultStateArray())!!
        assertFalse(snap.matchJoined)
    }

    @Test
    fun `matchJoined is true when player2 has any non-zero byte`() {
        val state = defaultStateArray().apply {
            put(2, hexCell("01" + "00".repeat(31))) // first byte set
        }
        val snap = ContractStateSnapshot.parse(state)!!
        assertTrue(snap.matchJoined)
    }

    @Test
    fun `equals returns true for two identical snapshots`() {
        val a = ContractStateSnapshot.parse(defaultStateArray())!!
        val b = ContractStateSnapshot.parse(defaultStateArray())!!
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `equals returns false when one boolean flag differs`() {
        val a = ContractStateSnapshot.parse(defaultStateArray())!!
        val b = ContractStateSnapshot.parse(defaultStateArray().apply {
            put(CELL_P1_COMMITTED, numberCell(1))
        })!!
        assertNotEquals(a, b)
    }

    @Test
    fun `equals returns false when winner bytes differ`() {
        val a = ContractStateSnapshot.parse(defaultStateArray())!!
        val b = ContractStateSnapshot.parse(defaultStateArray().apply {
            put(21, hexCell("ff" + "00".repeat(31)))
        })!!
        assertNotEquals(a, b)
    }

    @Test
    fun `summary string mentions all critical flags`() {
        val snap = ContractStateSnapshot.parse(defaultStateArray().apply {
            put(CELL_P1_COMMITTED, numberCell(1))
            put(19, numberCell(2))
            put(20, numberCell(1))
        })!!
        val s = snap.summary()
        assertTrue(s, s.contains("p1Committed=true"))
        assertTrue(s, s.contains("score=2-1"))
        assertTrue(s, s.contains("phase=0"))
    }

    // ── Test helpers ────────────────────────────────────────────────────

    /**
     * Returns a 25-cell JSONArray representing a freshly-deployed contract
     * with every field at its zero value. Tests override specific cells.
     */
    private fun defaultStateArray(): JSONArray {
        val arr = JSONArray()
        for (i in 0 until EXPECTED_CELLS) {
            arr.put(numberCell(0))
        }
        // Hex cells (Bytes<32>) overwrite the zero number cells where
        // applicable. We always overwrite all of them so the shape is
        // realistic.
        arr.put(1, hexCell("00".repeat(32)))     // player1
        arr.put(2, hexCell("00".repeat(32)))     // player2
        arr.put(3, hexCell("00".repeat(32)))     // p1Commitment
        arr.put(4, hexCell("00".repeat(32)))     // p2Commitment
        arr.put(21, hexCell("00".repeat(32)))    // winner
        return arr
    }

    private fun numberCell(value: Number): JSONObject =
        JSONObject().put("type", "cell").put("number", value)

    private fun hexCell(hex: String): JSONObject =
        JSONObject().put("type", "cell").put("hex", hex)

    companion object {
        // Mirrors private constants in ContractStateSnapshot; duplicated
        // intentionally so tests catch drift in the index map.
        private const val EXPECTED_CELLS = 25
        private const val CELL_P1_COMMITTED = 5
        private const val CELL_P2_COMMITTED = 6
        private const val CELL_P1_REVEALED = 17
        private const val CELL_P2_REVEALED = 18
    }
}
