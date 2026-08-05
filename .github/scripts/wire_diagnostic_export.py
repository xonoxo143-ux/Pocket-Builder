from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    file = Path(path)
    text = file.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected one match, found {count}")
    file.write_text(text.replace(old, new, 1))


replace_once(
    "app/src/main/java/com/libreseed/pocketbuild/PocketBuildViewModel.kt",
    '''    fun clearOperationReport() {
        if (_uiState.value.activeOperation?.status == OperationStatus.RUNNING) return
        _uiState.update { it.copy(activeOperation = null) }
    }

    fun reportInstallerFailure(path: String?, error: Throwable) {
''',
    '''    fun clearOperationReport() {
        if (_uiState.value.activeOperation?.status == OperationStatus.RUNNING) return
        _uiState.update { it.copy(activeOperation = null) }
    }

    fun exportActiveOperation() {
        val operation = _uiState.value.activeOperation
        if (operation == null) {
            _uiState.update { it.copy(notice = "No operation report is available to export.") }
            return
        }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { operationJournal.exportDiagnostic(operation) }
            }
            result.onSuccess { report ->
                _uiState.update {
                    it.copy(
                        completedDiagnosticPath = report.absolutePath,
                        notice = "Diagnostic report created.",
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(notice = error.message ?: "Diagnostic report could not be created.")
                }
            }
        }
    }

    fun reportDiagnosticShareFailure(error: Throwable) {
        _uiState.update {
            it.copy(notice = error.message ?: "Android's share sheet could not be opened.")
        }
    }

    fun reportInstallerFailure(path: String?, error: Throwable) {
''',
)

replace_once(
    "app/src/main/java/com/libreseed/pocketbuild/PocketBuildViewModel.kt",
    '''    fun consumeCompletedOutput() {
        _uiState.update { it.copy(completedOutputPath = null) }
    }

    fun clearNotice() {
''',
    '''    fun consumeCompletedOutput() {
        _uiState.update { it.copy(completedOutputPath = null) }
    }

    fun consumeCompletedDiagnostic() {
        _uiState.update { it.copy(completedDiagnosticPath = null) }
    }

    fun clearNotice() {
''',
)

replace_once(
    "app/src/main/java/com/libreseed/pocketbuild/PocketBuildViewModel.kt",
    '''    val operationHistory: List<OperationHistoryItem> = emptyList(),
    val completedOutputPath: String? = null,
    val notice: String? = null,
''',
    '''    val operationHistory: List<OperationHistoryItem> = emptyList(),
    val completedOutputPath: String? = null,
    val completedDiagnosticPath: String? = null,
    val notice: String? = null,
''',
)

replace_once(
    "app/src/main/java/com/libreseed/pocketbuild/MainActivity.kt",
    "import android.content.Intent\n",
    "import android.content.ClipData\nimport android.content.Intent\n",
)

replace_once(
    "app/src/main/java/com/libreseed/pocketbuild/MainActivity.kt",
    '''            LaunchedEffect(state.completedOutputPath) {
                val outputPath = state.completedOutputPath ?: return@LaunchedEffect
                requestInstallApk(outputPath)
                viewModel.consumeCompletedOutput()
            }
            PocketBuildTheme {
''',
    '''            LaunchedEffect(state.completedOutputPath) {
                val outputPath = state.completedOutputPath ?: return@LaunchedEffect
                requestInstallApk(outputPath)
                viewModel.consumeCompletedOutput()
            }
            LaunchedEffect(state.completedDiagnosticPath) {
                val reportPath = state.completedDiagnosticPath ?: return@LaunchedEffect
                shareDiagnosticReport(reportPath)
                viewModel.consumeCompletedDiagnostic()
            }
            PocketBuildTheme {
''',
)

replace_once(
    "app/src/main/java/com/libreseed/pocketbuild/MainActivity.kt",
    '''                    onCancelOperation = viewModel::cancelActiveOperation,
                    onClearOperation = viewModel::clearOperationReport,
                    onNoticeDismissed = viewModel::clearNotice,
''',
    '''                    onCancelOperation = viewModel::cancelActiveOperation,
                    onClearOperation = viewModel::clearOperationReport,
                    onExportOperation = viewModel::exportActiveOperation,
                    onNoticeDismissed = viewModel::clearNotice,
''',
)

replace_once(
    "app/src/main/java/com/libreseed/pocketbuild/MainActivity.kt",
    '''    private fun launchPackageInstaller(apk: File) {
''',
    '''    private fun shareDiagnosticReport(path: String) {
        val report = File(path)
        if (!report.isFile) {
            viewModel.reportDiagnosticShareFailure(IllegalStateException("The diagnostic report no longer exists."))
            return
        }
        runCatching {
            val uri = FileProvider.getUriForFile(this, "$packageName.files", report)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "PocketBuild diagnostic report")
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri("PocketBuild diagnostic report", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Share diagnostic report"))
        }.onFailure(viewModel::reportDiagnosticShareFailure)
    }

    private fun launchPackageInstaller(apk: File) {
''',
)

replace_once(
    "app/src/main/java/com/libreseed/pocketbuild/ui/PocketBuildApp.kt",
    '''    onCancelOperation: () -> Unit,
    onClearOperation: () -> Unit,
    onNoticeDismissed: () -> Unit,
''',
    '''    onCancelOperation: () -> Unit,
    onClearOperation: () -> Unit,
    onExportOperation: () -> Unit,
    onNoticeDismissed: () -> Unit,
''',
)

ui_path = Path("app/src/main/java/com/libreseed/pocketbuild/ui/PocketBuildApp.kt")
ui_text = ui_path.read_text()
old_callbacks = '''                            onCancelOperation = onCancelOperation,
                            onClearOperation = onClearOperation,
'''
new_callbacks = '''                            onCancelOperation = onCancelOperation,
                            onClearOperation = onClearOperation,
                            onExportOperation = onExportOperation,
'''
callback_count = ui_text.count(old_callbacks)
if callback_count != 2:
    raise SystemExit(f"PocketBuildApp.kt: expected two AppContent callback blocks, found {callback_count}")
ui_path.write_text(ui_text.replace(old_callbacks, new_callbacks))

replace_once(
    "app/src/main/java/com/libreseed/pocketbuild/ui/PocketBuildApp.kt",
    '''                        onCancel = onCancelOperation,
                        onClear = onClearOperation,
                        showTimeline = true,
''',
    '''                        onCancel = onCancelOperation,
                        onClear = onClearOperation,
                        onExport = onExportOperation,
                        showTimeline = true,
''',
)

replace_once(
    "app/src/main/java/com/libreseed/pocketbuild/ui/PocketBuildApp.kt",
    '''    onToolchainAction: (String) -> Unit,
    onCancelOperation: () -> Unit,
    onClearOperation: () -> Unit,
) {
''',
    '''    onToolchainAction: (String) -> Unit,
    onCancelOperation: () -> Unit,
    onClearOperation: () -> Unit,
    onExportOperation: () -> Unit,
) {
''',
)

replace_once(
    "app/src/main/java/com/libreseed/pocketbuild/ui/PocketBuildApp.kt",
    "            Destination.BUILDS -> BuildsScreen(state, compact, onCancelOperation, onClearOperation)\n",
    "            Destination.BUILDS -> BuildsScreen(state, compact, onCancelOperation, onClearOperation, onExportOperation)\n",
)

replace_once(
    "app/src/main/java/com/libreseed/pocketbuild/ui/PocketBuildApp.kt",
    '''    compact: Boolean,
    onCancelOperation: () -> Unit,
    onClearOperation: () -> Unit,
) {
''',
    '''    compact: Boolean,
    onCancelOperation: () -> Unit,
    onClearOperation: () -> Unit,
    onExportOperation: () -> Unit,
) {
''',
)

replace_once(
    "app/src/main/java/com/libreseed/pocketbuild/ui/PocketBuildApp.kt",
    '''                    onCancel = onCancelOperation,
                    onClear = onClearOperation,
                    showTimeline = true,
''',
    '''                    onCancel = onCancelOperation,
                    onClear = onClearOperation,
                    onExport = onExportOperation,
                    showTimeline = true,
''',
)

replace_once(
    "app/src/main/java/com/libreseed/pocketbuild/ui/PocketBuildApp.kt",
    '''    onCancel: () -> Unit,
    onClear: () -> Unit,
    showTimeline: Boolean,
) {
''',
    '''    onCancel: () -> Unit,
    onClear: () -> Unit,
    onExport: () -> Unit,
    showTimeline: Boolean,
) {
''',
)

replace_once(
    "app/src/main/java/com/libreseed/pocketbuild/ui/PocketBuildApp.kt",
    '''            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (operation.status == OperationStatus.RUNNING) {
                    OutlinedButton(onClick = onCancel, enabled = operation.cancellable) {
                        Text(if (operation.cancellable) "Cancel safely" else "Cannot cancel this stage")
                    }
                } else {
                    OutlinedButton(onClick = onClear) { Text("Clear console") }
                }
            }
''',
    '''            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (operation.status == OperationStatus.RUNNING) {
                    OutlinedButton(onClick = onCancel, enabled = operation.cancellable) {
                        Text(if (operation.cancellable) "Cancel safely" else "Cannot cancel this stage")
                    }
                } else {
                    OutlinedButton(onClick = onClear) { Text("Clear console") }
                }
                OutlinedButton(onClick = onExport) { Text("Share diagnostic report") }
            }
''',
)
