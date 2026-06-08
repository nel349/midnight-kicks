package com.midnight.kicks

import android.app.Activity
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowHeightSizeClass
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Process-wide access to the current [WindowSizeClass] so any screen/overlay can
 * adapt to orientation + size without recomputing it. Provided once per Compose
 * root via [ProvideWindowSizeClass].
 *
 * It observes the window Configuration, so it recomposes on rotation under the
 * activities' `configChanges` (no recreate, no state loss). Works in both the
 * main process (KicksActivity) and the `:unity` overlay ComposeView
 * (KicksMatchActivity) — both hosts are ComponentActivity-derived.
 */
val LocalWindowSizeClass = staticCompositionLocalOf<WindowSizeClass> {
    error("LocalWindowSizeClass not provided — wrap content in ProvideWindowSizeClass(activity) { … }")
}

@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
@Composable
fun ProvideWindowSizeClass(activity: Activity, content: @Composable () -> Unit) {
    val sizeClass = calculateWindowSizeClass(activity)
    CompositionLocalProvider(LocalWindowSizeClass provides sizeClass, content = content)
}

/**
 * Vertical space is tight — the phone-landscape signal. Screens use it to scroll,
 * shrink spacers, or reflow so fixed-height content never clips.
 */
@Composable
@ReadOnlyComposable
fun isCompactHeight(): Boolean =
    LocalWindowSizeClass.current.heightSizeClass == WindowHeightSizeClass.Compact

/** Enough horizontal room for a side-by-side layout (e.g. the landscape two-pane menu). */
@Composable
@ReadOnlyComposable
fun isWideWidth(): Boolean =
    LocalWindowSizeClass.current.widthSizeClass != WindowWidthSizeClass.Compact

/** Landscape-style layout: short height + room to go wide (phones in landscape, foldables). */
@Composable
@ReadOnlyComposable
fun isLandscapeLayout(): Boolean = isCompactHeight() && isWideWidth()
