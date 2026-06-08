package com.midnight.kicks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Standard full-screen scaffold for Kicks menu screens — replaces the duplicated
 * `Surface(Background) { Column(fillMaxSize, padding) }` each screen used to build.
 *
 * It does three adaptive things so screens behave in any orientation:
 *  - paints the [KicksColors.Background],
 *  - keeps content inside [WindowInsets.safeDrawing] (status + nav on ALL edges +
 *    cutout + IME), so landscape side nav bars / side cutouts and the keyboard
 *    never overlap content, and
 *  - scrolls when [scrollable] (default) OR the window height is compact
 *    (landscape), so fixed-height content can never clip.
 *
 * Insets are applied OUTSIDE the scroll (they don't scroll); content padding is
 * inside it (so the last item's bottom margin stays reachable).
 */
@Composable
fun KicksScreenScaffold(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 24.dp, vertical = 32.dp),
    horizontalAlignment: Alignment.Horizontal = Alignment.CenterHorizontally,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    scrollable: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize().then(modifier), color = KicksColors.Background) {
        val scroll = if (scrollable || isCompactHeight()) {
            Modifier.verticalScroll(rememberScrollState())
        } else {
            Modifier
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .then(scroll)
                .padding(contentPadding),
            horizontalAlignment = horizontalAlignment,
            verticalArrangement = verticalArrangement,
            content = content,
        )
    }
}
