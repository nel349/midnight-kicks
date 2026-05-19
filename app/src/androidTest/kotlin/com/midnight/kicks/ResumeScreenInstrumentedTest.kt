package com.midnight.kicks

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose UI tests for [ResumeScreen] — pin the visual + interaction
 * contract that the Resume picker promises.
 *
 * Uses [createComposeRule] (not [createAndroidComposeRule]) because
 * ResumeScreen is a pure Composable that doesn't need a hosting
 * Activity — every dependency (matches, callbacks) is hoisted to
 * the caller. This keeps the test rig cheap (no Hilt graph, no
 * Activity lifecycle) and the assertions focused on UX behavior.
 *
 * What these tests catch:
 *  - Empty state copy regression — the message users see when they
 *    tap RESUME with no active matches.
 *  - Row count vs. underlying match list — silent off-by-one or
 *    duplicate-render bugs.
 *  - Tap routing — onMatchSelected fires with the exact match
 *    object the user tapped, not a sibling.
 *  - Back button wiring — onBack fires on tap.
 *
 * What these tests don't (and shouldn't) catch:
 *  - The downstream Creating / Joining screen behavior — those are
 *    separate composables with their own tests.
 *  - On-chain phase logic — the picker deliberately doesn't query
 *    the chain; that's the destination screen's job.
 */
@RunWith(AndroidJUnit4::class)
class ResumeScreenInstrumentedTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun empty_state_renders_friendly_message() {
        composeTestRule.setContent {
            ResumeScreen(
                matches = emptyList(),
                onBack = {},
                onMatchSelected = {},
            )
        }

        composeTestRule.onNodeWithText("NO ACTIVE MATCHES").assertIsDisplayed()
        composeTestRule.onNodeWithText("Create or join one from the menu.").assertIsDisplayed()
    }

    @Test
    fun single_match_renders_one_row_with_role_badge() {
        val match = matchFixture(address = "aaaa".repeat(16), role = Player.P1)
        composeTestRule.setContent {
            ResumeScreen(
                matches = listOf(match),
                onBack = {},
                onMatchSelected = {},
            )
        }

        composeTestRule.onNodeWithText("1 ACTIVE").assertIsDisplayed()
        // Role badge — text comes from Player.name (P1 / P2).
        composeTestRule.onNodeWithText("P1").assertIsDisplayed()
    }

    @Test
    fun multi_match_renders_one_row_per_match() {
        val matches = listOf(
            matchFixture(address = "aaaa".repeat(16), role = Player.P1),
            matchFixture(address = "bbbb".repeat(16), role = Player.P2),
            matchFixture(address = "cccc".repeat(16), role = Player.P1),
        )
        composeTestRule.setContent {
            ResumeScreen(
                matches = matches,
                onBack = {},
                onMatchSelected = {},
            )
        }

        composeTestRule.onNodeWithText("3 ACTIVE").assertIsDisplayed()
        // Two P1 badges + one P2 badge — count them via onAllNodesWithText.
        composeTestRule.onAllNodesWithText("P1").assertCountEquals(2)
        composeTestRule.onAllNodesWithText("P2").assertCountEquals(1)
    }

    @Test
    fun tap_on_row_fires_onMatchSelected_with_that_match() {
        // The row clickability assertion: tapping the row for match X
        // routes onMatchSelected(X), not onMatchSelected(neighbouring
        // match Y). Captures the closure-binding bug class where a
        // shared lambda over `match` would surface the last-bound
        // value instead.
        val matches = listOf(
            matchFixture(address = "aaaa".repeat(16), role = Player.P1),
            matchFixture(address = "bbbb".repeat(16), role = Player.P2),
        )
        var selected: MatchStore.Match? = null
        composeTestRule.setContent {
            ResumeScreen(
                matches = matches,
                onBack = {},
                onMatchSelected = { selected = it },
            )
        }

        // Tap the P2 row — short address segment is the most stable
        // visible-text anchor (the role badge is shared between rows
        // when both players are P1, the deadline label is time-sensitive).
        composeTestRule.onNodeWithText("P2").performClick()

        // Verify the right match got routed through. The match object
        // identity is preserved across the lambda — not just role.
        assertTrue("onMatchSelected must fire", selected != null)
        assertEquals(matches[1].address, selected!!.address)
        assertEquals(Player.P2, selected!!.role)
    }

    @Test
    fun back_button_fires_onBack() {
        var backTapped = false
        composeTestRule.setContent {
            ResumeScreen(
                matches = emptyList(),
                onBack = { backTapped = true },
                onMatchSelected = {},
            )
        }

        // TopBackBar renders the "‹  BACK" label; tapping it should
        // trigger onBack.
        composeTestRule.onNodeWithText("‹  BACK").performClick()
        assertTrue("onBack must fire on tapping the back affordance", backTapped)
    }

    private fun matchFixture(
        address: String,
        role: Player = Player.P1,
        deadline: Long = 1_900_000_000L,
    ): MatchStore.Match = MatchStore.Match(
        address = address,
        role = role,
        deadline = deadline,
        secretKey = ByteArray(32) { 0x77.toByte() },
    )
}
