package com.libreseed.pocketbuild.workspace

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.libreseed.pocketbuild.model.IncomingSource
import com.libreseed.pocketbuild.model.SourceKind
import com.libreseed.pocketbuild.model.WorkspaceSummary
import java.io.File
import java.util.Properties
import java.util.UUID

class WorkspaceManager(private val context: Context) {
    private val workspacesRoot = File(context.filesDir, "workspaces")
    private val extractor = SafeArchiveExtractor()

    fun importArchive(source: IncomingSource): WorkspaceSummary {
        val resolver = context.contentResolver
        val id = createWorkspaceId(source.displayName)
        val staging = File(workspacesRoot, ".$id.staging")
        val destination = File(workspacesRoot, id)
        staging.deleteRecursively()
        check(staging.mkdirs()) { "Cannot create workspace staging directory." }

        try {
            val stats = resolver.openInputStream(source.uri)?.use { input ->
                extractor.extract(input, staging, source.projectRootHint)
            } ?: error("Cannot open the selected archive.")
            validateProject(staging, source.kind)
            writeMetadata(staging, source, stats.files, stats.expandedBytes)
            promote(staging, destination)
            return WorkspaceSummary(
                id = id,
                displayName = source.displayName.substringBeforeLast('.'),
                sourceKind = source.kind,
                rootPath = destination.absolutePath,
                importedFiles = stats.files,
                importedBytes = stats.expandedBytes,
            )
        } catch (error: Throwable) {
            staging.deleteRecursively()
            throw error
        }
    }

    fun importTree(treeUri: Uri, displayName: String, sourceKind: SourceKind): WorkspaceSummary {
        persistTreePermission(treeUri)
        val rootDocument = DocumentFile.fromTreeUri(context, treeUri) ?: error("Cannot open the selected folder.")
        check(rootDocument.isDirectory) { "The selected document is not a folder." }

        val id = createWorkspaceId(displayName)
        val staging = File(workspacesRoot, ".$id.staging")
        val destination = File(workspacesRoot, id)
        staging.deleteRecursively()
        check(staging.mkdirs()) { "Cannot create workspace staging directory." }

        try {
            val stats = TreeCopyStats()
            copyTree(rootDocument, staging, stats, depth = 0)
            validateProject(staging, sourceKind)
            writeMetadata(
                staging,
                IncomingSource(
                    uri = treeUri,
                    displayName = displayName,
                    mimeType = null,
                    sizeBytes = null,
                    kind = sourceKind,
                ),
                stats.files,
                stats.bytes,
            )
            promote(staging, destination)
            return WorkspaceSummary(
                id = id,
                displayName = displayName.substringBeforeLast('.'),
                sourceKind = sourceKind,
                rootPath = destination.absolutePath,
                importedFiles = stats.files,
                importedBytes = stats.bytes,
            )
        } catch (error: Throwable) {
            staging.deleteRecursively()
            throw error
        }
    }

    private fun copyTree(document: DocumentFile, destination: File, stats: TreeCopyStats, depth: Int) {
        check(depth <= 64) { "Folder nesting is too deep." }
        for (child in document.listFiles()) {
            val name = child.name ?: continue
            check(isSafeDocumentName(name)) { "Unsafe document name: $name" }
            stats.entries += 1
            check(stats.entries <= 20_000) { "Project contains too many files." }
            val output = File(destination, name)
            if (child.isDirectory) {
                check(output.mkdirs() || output.isDirectory) { "Cannot create directory: $name" }
                copyTree(child, output, stats, depth + 1)
            } else if (child.isFile) {
                val parent = output.parentFile
                check(parent == null || parent.mkdirs() || parent.isDirectory) {
                    "Cannot create parent directory: $name"
                }
                context.contentResolver.openInputStream(child.uri)?.use { input ->
                    output.outputStream().buffered().use { out ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var fileBytes = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            fileBytes += read
                            stats.bytes += read
                            check(fileBytes <= 1024L * 1024 * 1024) { "File is too large: $name" }
                            check(stats.bytes <= 4L * 1024 * 1024 * 1024) { "Project exceeds import-size limit." }
                            out.write(buffer, 0, read)
                        }
                    }
                } ?: error("Cannot read $name")
                stats.files += 1
            }
        }
    }

    private fun validateProject(root: File, kind: SourceKind) {
        val valid = when (kind) {
            SourceKind.GODOT -> File(root, "project.godot").isFile
            SourceKind.ANDROID_GRADLE ->
                File(root, "settings.gradle").isFile || File(root, "settings.gradle.kts").isFile
            SourceKind.POCKETBUILD_RECIPE -> File(root, "pocketbuild.json").isFile
            else -> false
        }
        check(valid) { "The imported folder does not contain the expected project marker." }
    }

    private fun writeMetadata(root: File, source: IncomingSource, files: Int, bytes: Long) {
        val properties = Properties().apply {
            setProperty("sourceName", source.displayName)
            setProperty("sourceKind", source.kind.name)
            setProperty("importedFiles", files.toString())
            setProperty("importedBytes", bytes.toString())
        }
        File(root, ".pocketbuild-workspace").outputStream().use { properties.store(it, null) }
    }

    private fun promote(staging: File, destination: File) {
        workspacesRoot.mkdirs()
        destination.deleteRecursively()
        check(staging.renameTo(destination)) { "Cannot finalize the imported workspace." }
    }

    private fun persistTreePermission(uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun createWorkspaceId(name: String): String {
        val slug = name.lowercase()
            .replace(Regex("[^a-z0-9._-]+"), "-")
            .trim('-')
            .take(40)
            .ifBlank { "project" }
        return "$slug-${UUID.randomUUID().toString().take(8)}"
    }

    private fun isSafeDocumentName(name: String): Boolean {
        return name.isNotBlank() && name != "." && name != ".." && '/' !in name && '\\' !in name
    }
}

private data class TreeCopyStats(
    var entries: Int = 0,
    var files: Int = 0,
    var bytes: Long = 0,
)
