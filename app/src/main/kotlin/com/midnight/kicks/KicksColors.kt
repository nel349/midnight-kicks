package com.midnight.kicks

import androidx.compose.ui.graphics.Color

/**
 * Named colour tokens for Kicks screens — the start of a shared palette
 * (mirrors BBoard's `Colors` object).
 *
 * Kicks historically inlined `Color(0x…)` hex in every composable. This file
 * collects the tokens that currently have consumers; the rest of the inline
 * hex (backgrounds, white-alpha greys, the blue accent) is pre-existing and
 * still needs migrating — extend this object as that happens rather than
 * adding new inline literals.
 */
object KicksColors {
    /** Amber — actionable notices on the near-black background, e.g. the
     *  menu's "forge your sigil first" warning. */
    val Warning = Color(0xFFFFC107)

    /** Red — errors, the opponent/P2 accent, and destructive actions. */
    val Danger = Color(0xFFE57373)

    /** [Danger] at ~20% alpha — fill behind an armed destructive button. */
    val DangerSurface = Color(0x33E57373)
}
