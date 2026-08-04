package com.libreseed.pocketbuild

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.libreseed.pocketbuild.ui.PocketBuildApp
import com.libreseed.pocketbuild.ui.theme.PocketBuildTheme

class MainActivity : ComponentActivity() {
    private val viewModel: PocketBuildViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        viewModel.acceptIntent(intent)

        setContent {
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            PocketBuildTheme {
                PocketBuildApp(
                    state = state,
                    onSourceSelected = viewModel::inspect,
                    onBuildRequested = viewModel::queueBuild,
                    onNoticeDismissed = viewModel::clearNotice,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        viewModel.acceptIntent(intent)
    }
}
