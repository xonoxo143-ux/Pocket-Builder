package com.libreseed.pocketbuild.runtime

import android.content.Context
import com.libreseed.pocketbuild.model.OperationHistoryItem
import com.libreseed.pocketbuild.model.OperationKind
import com.libreseed.pocketbuild.model.OperationLogLine
import com.libreseed.pocketbuild.model.OperationStatus
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

class OperationJournal(context: Context) {
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

    companion object {
        private const val MAX_LOG_BYTES = 5L * 1024 * 1024
    }
}
