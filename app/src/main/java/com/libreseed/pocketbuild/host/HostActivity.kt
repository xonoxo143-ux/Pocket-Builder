package com.libreseed.pocketbuild.host

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.libreseed.pocketbuild.MainActivity
import com.libreseed.pocketbuild.host.ui.HostScreen
import com.libreseed.pocketbuild.ui.applyPhoneLandscapePreference
import com.libreseed.pocketbuild.ui.theme.PocketBuildTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HostActivity : ComponentActivity() {
    private lateinit var store: HostStore
    private var apps by mutableStateOf<List<HostedApp>>(emptyList())
    private var busy by mutableStateOf(false)
    private var status by mutableStateOf<String?>(null)

    private val bundlePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(::installBundle)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        applyPhoneLandscapePreference()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        store = HostStore(this)
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { store.ensureBuiltInDemo() }
            withContext(Dispatchers.Main) {
                refreshApps()
                acceptIntent(intent)
            }
        }

        setContent {
            PocketBuildTheme {
                HostScreen(
                    apps = apps,
                    busy = busy,
                    status = status,
                    onDismissStatus = { status = null },
                    onImport = { bundlePicker.launch(arrayOf("application/zip", "application/json", "text/json", "*/*")) },
                    onRun = ::runApp,
                    onRollback = ::rollback,
                    onDelete = ::deleteApp,
                    onOpenBuilder = { startActivity(Intent(this, MainActivity::class.java)) },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        acceptIntent(intent)
    }

    private fun acceptIntent(intent: Intent?) {
        val uri = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> sharedStreamUri(intent)
            else -> null
        }
        uri?.let(::installBundle)
    }

    @Suppress("DEPRECATION")
    private fun sharedStreamUri(intent: Intent): Uri? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
    }

    private fun installBundle(uri: Uri) {
        if (busy) return
        busy = true
        status = "Validating and staging bundle…"
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { store.install(uri) } }
            result.onSuccess { installed ->
                refreshApps()
                status = when (installed.kind) {
                    InstallKind.INSTALLED -> "Installed ${installed.app.name} ${installed.app.version}."
                    InstallKind.REPLACED -> "Replaced ${installed.app.name}; previous version kept for rollback."
                    InstallKind.PATCHED -> "Patched ${installed.app.name} to ${installed.app.version}."
                }
            }.onFailure { error ->
                status = error.message ?: "The bundle could not be installed."
            }
            busy = false
        }
    }

    private fun runApp(app: HostedApp) {
        startActivity(
            Intent(this, RuntimeActivity::class.java)
                .putExtra(RuntimeActivity.EXTRA_APP_ID, app.id),
        )
    }

    private fun rollback(app: HostedApp) {
        if (busy) return
        busy = true
        status = "Rolling back ${app.name}…"
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { store.rollback(app.id) } }
            result.onSuccess {
                refreshApps()
                status = "Rolled ${it.name} back to ${it.version}."
            }.onFailure { status = it.message ?: "Rollback failed." }
            busy = false
        }
    }

    private fun deleteApp(app: HostedApp) {
        if (busy) return
        android.app.AlertDialog.Builder(this)
            .setTitle("Delete ${app.name}?")
            .setMessage("The hosted project, its rollback version, and its shared files will be removed. The PocketBuild APK is unaffected.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    busy = true
                    status = "Deleting ${app.name}…"
                    val result = withContext(Dispatchers.IO) { runCatching { store.delete(app.id) } }
                    result.onSuccess {
                        refreshApps()
                        status = "Deleted ${app.name}."
                    }.onFailure {
                        status = it.message ?: "Delete failed."
                        Toast.makeText(this@HostActivity, status, Toast.LENGTH_LONG).show()
                    }
                    busy = false
                }
            }
            .show()
    }

    private fun refreshApps() {
        apps = store.listApps()
    }
}
