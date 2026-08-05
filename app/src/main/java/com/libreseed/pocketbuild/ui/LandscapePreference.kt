package com.libreseed.pocketbuild.ui

import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity

/**
 * PocketBuild is designed around a three-pane landscape workspace on phones.
 * Large screens and multi-window sessions remain freely resizable.
 */
fun ComponentActivity.applyPhoneLandscapePreference() {
    val isPhoneSized = resources.configuration.smallestScreenWidthDp < 600
    if (isPhoneSized && !isInMultiWindowMode) {
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }
}
