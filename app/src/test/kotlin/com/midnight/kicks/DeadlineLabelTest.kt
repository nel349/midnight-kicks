package com.midnight.kicks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the format strings emitted by [deadlineLabel] — the small clock
 * label on every [ResumeScreen] row.
 *
 * Two reasons this is worth a unit test:
 *  1. The unit cascade (seconds → minutes → hours → days) is the kind
 *     of off-by-one-prone arithmetic that's easy to misread on review.
 *  2. The UI shipped today is the primary user-facing time signal in
 *     Kicks. A regression that turns "in 5 minutes" into "in 5 seconds"
 *     or vice versa is the difference between a player tapping in time
 *     and the contract refusing their commit. Worth a test.
 */
class DeadlineLabelTest {

    /** Test pins a fixed "now" so deltas are deterministic. */
    private val nowSeconds: Long = 1_700_000_000L  // 2023-11-14 22:13:20 UTC

    @Test
    fun `future deadline within a minute reports seconds`() {
        // System.currentTimeMillis() is read inside deadlineLabel, so
        // anchor each call at runtime by computing target deltas from
        // (currentTimeMillis()/1000) rather than the fixture constant.
        val futureSecs = currentNow() + 30
        assertEquals("DEADLINE IN 30 s", deadlineLabel(futureSecs))
    }

    @Test
    fun `future deadline within an hour reports minutes`() {
        val futureSecs = currentNow() + 5 * 60
        assertEquals("DEADLINE IN 5 min", deadlineLabel(futureSecs))
    }

    @Test
    fun `future deadline within a day reports hours`() {
        val futureSecs = currentNow() + 3 * 3600
        assertEquals("DEADLINE IN 3 h", deadlineLabel(futureSecs))
    }

    @Test
    fun `future deadline beyond a day reports days`() {
        val futureSecs = currentNow() + 2 * 86_400
        assertEquals("DEADLINE IN 2 d", deadlineLabel(futureSecs))
    }

    @Test
    fun `past deadline switches to EXPIRED form`() {
        val pastSecs = currentNow() - 3 * 3600
        // "EXPIRED 3 h AGO" — same magnitude phrasing, just inverted
        // tense + the EXPIRED prefix. Regression-proofing the
        // negative-delta branch.
        assertEquals("EXPIRED 3 h AGO", deadlineLabel(pastSecs))
    }

    @Test
    fun `boundary at exactly one minute reports 1 min not 60 s`() {
        // The cascade is < 60s = seconds, < 3600s = minutes. Exactly
        // 60 should be 1 min so a future code-review doesn't change
        // the inequality and silently flip the boundary case.
        val futureSecs = currentNow() + 60
        assertEquals("DEADLINE IN 1 min", deadlineLabel(futureSecs))
    }

    @Test
    fun `isoTimestamp formats unix seconds as UTC date`() {
        // nowSeconds == 2023-11-14 22:13:20 UTC. Locking down the format
        // so a future relocale doesn't accidentally render local-time
        // (chain time is always UTC; users in PT shouldn't see
        // "2023-11-14 14:13 PST").
        assertEquals("2023-11-14 22:13 UTC", isoTimestamp(nowSeconds))
    }

    @Test
    fun `deadlineLabel handles a zero-second delta`() {
        // 0 seconds is the boundary between "now" and "expired now".
        // The current implementation puts it in the future branch
        // (delta >= 0) → "DEADLINE IN 0 s". Either side is acceptable;
        // pinning the current behavior catches an accidental flip.
        val nowFutureSecs = currentNow()
        val label = deadlineLabel(nowFutureSecs)
        assertTrue(
            "expected zero-delta to be in DEADLINE IN form, got: $label",
            label.startsWith("DEADLINE IN"),
        )
    }

    private fun currentNow(): Long = System.currentTimeMillis() / 1000
}
