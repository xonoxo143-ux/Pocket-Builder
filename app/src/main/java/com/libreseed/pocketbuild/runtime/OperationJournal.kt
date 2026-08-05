package com.libreseed.pocketbuild.runtime

import android.content.Context
import com.libreseed.pocketbuild.model.OperationHistoryItem
import com.libreseed.pocketbuild.model.OperationKind
import com.libreseed.pocketbuild.model.OperationLogLine
import com.libreseed.pocketbuild.model.OperationStatus
import com.libreseed.pocketbuild.model.OperationUiState
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

class OperationJournal(private val context: Context) {
    private val root = File(context.filesDir, "operation-journal").apply { mkdirs() }
    private val logs = File(root, "logs").apply { mkdirs() }
    private val historyFile = File(root, "history.json")

    @Synchronized
    fun append(operationId: String, line: OperationLogLine) {
        val rendered = buildString {
            append(line.timestampMillis)
            append('\t')
            append(line.severity.name)
            append('\t')
            append(singleLine(line.stage))
            append('\t')
            append(singleLine(line.message))
            append('\n')
        }
        val current = logFile(operationId)
        if (current.length() >= MAX_LOG_BYTES) {
            val previous = File(logs, safeId(operationId) + ".previous.log")
            previous.delete()
            check(current.renameTo(previous)) { "Cannot rotate the operation log." }
        }
        current.appendText(rendered)
    }

    fun logFile(operationId: String): File = File(logs, safeId(operationId) + ".log")

    @Synchronized
    fun exportDiagnostic(operation: OperationUiState): File {
        val shared = File(context.cacheDir, "shared").apply { mkdirs() }
        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val destination = File(shared, "pocketbuild-${safeId(operation.title)}-$timestamp.txt")
        val previousLog = File(logs, safeId(operation.id) + ".previous.log")
        val currentLog = logFile(operation.id)
        destination.bufferedWriter().use { writer ->
            writer.appendLine("PocketBuild diagnostic report")
            writer.appendLine("Generated: ${formatTimestamp(System.currentTimeMillis())}")
            writer.appendLine()
            writer.appendLine("Operation ID: ${operation.id}")
            writer.appendLine("Kind: ${operation.kind}")
            writer.appendLine("Title: ${operation.title}")
            writer.appendLine("Status: ${operation.status}")
            writer.appendLine("Started: ${formatTimestamp(operation.startedAtMillis)}")
            writer.appendLine("Finished: ${operation.finishedAtMillis?.let(::formatTimestamp) ?: "Still running"}")
            writer.appendLine("Current stage: ${operation.stageTitle}")
            writer.appendLine("Detail: ${singleLine(operation.detail)}")
            operation.currentItem?.let { writer.appendLine("Current item: ${singleLine(it)}") }
            operation.overallProgress?.let { writer.appendLine("Overall progress: ${(it * 100).toInt().coerceIn(0, 100)}%") }
            operation.completedBytes?.let { writer.appendLine("Transferred bytes: $it") }
            operation.totalBytes?.let { writer.appendLine("Total bytes: $it") }
            operation.bytesPerSecond?.let { writer.appendLine("Bytes per second: $it") }
            operation.outputPath?.let { writer.appendLine("Output: $it") }
            operation.error?.let { error ->
                writer.appendLine()
                writer.appendLine("ERROR")
                writer.appendLine("Summary: ${singleLine(error.summary)}")
                writer.appendLine("Detail: ${singleLine(error.detail)}")
                error.exceptionType?.let { writer.appendLine("Exception: $it") }
                error.logPath?.let { writer.appendLine("Related log: $it") }
                writer.appendLine("Existing data safe: ${error.existingDataSafe}")
                writer.appendLine("Retry recommended: ${error.retryRecommended}")
            }
            writer.appendLine()
            writer.appendLine("STAGE TIMELINE")
            operation.stages.forEach { stage ->
                writer.appendLine(
                    buildString {
                        append(stage.status)
                        append('\t')
                        append(stage.title)
                        stage.startedAtMillis?.let { append("\tstart=").append(formatTimestamp(it)) }
                        stage.finishedAtMillis?.let { append("\tfinish=").append(formatTimestamp(it)) }
                        if (stage.detail.isNotBlank()) append("\t").append(singleLine(stage.detail))
                    },
                )
            }
            writer.appendLine()
            writer.appendLine("IN-MEMORY LOG SNAPSHOT")
            operation.logs.forEach { line ->
                writer.appendLine(
                    "${formatTimestamp(line.timestampMillis)}\t${line.severity}\t${singleLine(line.stage)}\t${singleLine(line.message)}",
                )
            }
            listOf(previousLog, currentLog).filter(File::isFile).forEach { source ->
                writer.appendLine()
                writer.appendLine("PERSISTED LOG: ${source.name}")
                source.bufferedReader().useLines { lines ->
                    lines.forEach(writer::appendLine)
                }
            }
        }
        check(destination.isFile && destination.length() > 0) { "Diagnostic report was not created." }
        return destination
    }

    @Synchronized
    fun loadHistory(limit: Int = 30): List<OperationHistoryItem> {
        if (!historyFile.isFile) return emptyList()
        return runCatching {
            val array = JSONArray(historyFile.readText())
            buildList {
                for (index in 0 until minOf(array.length(), limit)) {
                    val item = array.optJSONObject(index) ?: continue
                    val kind = runCatching { OperationKind.valueOf(item.getString("kind")) }.getOrNull() ?: continue
                    val status = runCatching { OperationStatus.valueOf(item.getString("status")) }.getOrNull() ?: continue
                    add(
                        OperationHistoryItem(
                            id = item.getString("id"),
                            kind = kind,
                            title = item.getString("title"),
                            status = status,
                            startedAtMillis = item.getLong("startedAtMillis"),
                            finishedAtMillis = item.getLong("finishedAtMillis"),
                            finalStage = item.getString("finalStage"),
                            detail = item.getString("detail"),
                            outputPath = item.optString("outputPath").takeIf { it.isNotBlank() },
                            errorSummary = item.optString("errorSummary").takeIf { it.isNotBlank() },
                        ),
                    )
                }
            }
        }.getOrElse { emptyList() }
    }

    @Synchronized
    fun saveHistory(items: List<OperationHistoryItem>) {
        val array = JSONArray()
        items.take(30).forEach { item ->
            array.put(
                JSONObject().apply {
                    put("id", item.id)
                    put("kind", item.kind.name)
                    put("title", item.title)
                    put("status", item.status.name)
                    put("startedAtMillis", item.startedAtMillis)
                    put("finishedAtMillis", item.finishedAtMillis)
                    put("finalStage", item.finalStage)
                    put("detail", item.detail)
                    put("outputPath", item.outputPath ?: "")
                    put("errorSummary", item.errorSummary ?: "")
                },
            )
        }
        val temporary = File(root, "history.json.tmp")
        val backup = File(root, "history.json.backup")
        temporary.writeText(array.toString(2))
        backup.delete()
        if (historyFile.exists()) check(historyFile.renameTo(backup)) { "Cannot preserve operation history." }
        if (!temporary.renameTo(historyFile)) {
            if (backup.exists()) backup.renameTo(historyFile)
            error("Cannot persist operation history.")
        }
        backup.delete()
    }

    private fun safeId(value: String): String = value.replace(Regex("[^A-Za-z0-9._-]"), "_").take(96)

    private fun singleLine(value: String): String = value.replace('\r', ' ').replace('\n', ' ')

    private fun formatTimestamp(value: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US).format(Date(value))

    companion object {
        private const val MAX_LOG_BYTES = 5L * 1024 * 1024
    }
}
