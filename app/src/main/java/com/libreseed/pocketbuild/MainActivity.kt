package com.libreseed.pocketbuild

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.libreseed.pocketbuild.ui.PocketBuildApp
import com.libreseed.pocketbuild.ui.applyPhoneLandscapePreference
import com.libreseed.pocketbuild.ui.theme.PocketBuildTheme
import java.io.File

class MainActivity : ComponentActivity() {
    private val viewModel: PocketBuildViewModel by viewModels()
    private var pendingInstallPath: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        applyPhoneLandscapePreference()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        viewModel.acceptIntent(intent)

        setContent {
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            LaunchedEffect(state.completedOutputPath) {
                val outputPath = state.completedOutputPath ?: return@LaunchedEffect
                requestInstallApk(outputPath)
                viewModel.consumeCompletedOutput()
            }
            PocketBuildTheme {
                PocketBuildApp(
                    state = state,
                    onSourceSelected = viewModel::inspect,
                    onBuildRequested = viewModel::queueBuild,
                    onFolderSelected = viewModel::importProjectTree,
                    onToolchainAction = viewModel::installOrVerifyToolchain,
                    onBuildConfirmed = viewModel::confirmPendingBuild,
                    onBuildConfirmationDismissed = viewModel::dismissBuildConfirmation,
                    onCancelOperation = viewModel::cancelActiveOperation,
                    onClearOperation = viewModel::clearOperationReport,
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

    override fun onResume() {
        super.onResume()
        val path = pendingInstallPath ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || packageManager.canRequestPackageInstalls()) {
            launchPackageInstaller(File(path))
        }
    }

    private fun requestInstallApk(path: String) {
        val apk = File(path)
        if (!apk.isFile) {
            viewModel.reportInstallerFailure(path, IllegalStateException("The generated APK no longer exists."))
            return
        }
        pendingInstallPath = path
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            runCatching {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:$packageName"),
                    ),
                )
            }.onFailure { error ->
                pendingInstallPath = null
                viewModel.reportInstallerFailure(path, error)
            }
        } else {
            launchPackageInstaller(apk)
        }
    }

    private fun launchPackageInstaller(apk: File) {
        val path = apk.absolutePath
        runCatching {
            val uri = FileProvider.getUriForFile(this, "$packageName.files", apk)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
        }.onSuccess {
            pendingInstallPath = null
        }.onFailure { error ->
            pendingInstallPath = null
            viewModel.reportInstallerFailure(path, error)
        }
    }
}
