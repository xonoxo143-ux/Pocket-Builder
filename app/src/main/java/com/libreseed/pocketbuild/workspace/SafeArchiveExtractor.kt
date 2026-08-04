package com.libreseed.pocketbuild.workspace

import com.libreseed.pocketbuild.source.SourceClassifier
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

class SafeArchiveExtractor(
    private val limits: ArchiveLimits = ArchiveLimits(),
) {
    fun extract(input: InputStream, destination: File, rootHint: String? = null): ExtractionStats {
        require(destination.mkdirs() || destination.isDirectory) { "Cannot create extraction directory." }
        val canonicalRoot = destination.canonicalFile
        val prefix = rootHint?.trim('/')?.takeIf { it.isNotBlank() }
        var entries = 0
        var files = 0
        var totalBytes = 0L
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)

        ZipInputStream(BufferedInputStream(input)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                entries += 1
                check(entries <= limits.maxEntries) { "Archive exceeds ${limits.maxEntries} entries." }
                check(SourceClassifier.isSafeArchivePath(entry.name)) { "Unsafe archive path: ${entry.name}" }

                val normalized = entry.name.replace('\\', '/').trimStart('/')
                val relative = stripRoot(normalized, prefix)
                if (relative.isNotBlank()) {
                    val output = File(canonicalRoot, relative).canonicalFile
                    check(output.path == canonicalRoot.path || output.path.startsWith(canonicalRoot.path + File.separator)) {
                        "Archive entry escapes the workspace: ${entry.name}"
                    }

                    if (entry.isDirectory) {
                        check(output.mkdirs() || output.isDirectory) { "Cannot create directory: $relative" }
                    } else {
                        val parent = output.parentFile
                        check(parent == null || parent.mkdirs() || parent.isDirectory) {
                            "Cannot create parent directory: $relative"
                        }
                        var entryBytes = 0L
                        output.outputStream().buffered().use { out ->
                            while (true) {
                                val read = zip.read(buffer)
                                if (read < 0) break
                                entryBytes += read
                                totalBytes += read
                                check(entryBytes <= limits.maxSingleFileBytes) { "File is too large: $relative" }
                                check(totalBytes <= limits.maxExpandedBytes) { "Archive exceeds expanded-size limit." }
                                out.write(buffer, 0, read)
                            }
                        }
                        files += 1
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return ExtractionStats(entries = entries, files = files, expandedBytes = totalBytes)
    }

    private fun stripRoot(path: String, rootHint: String?): String {
        if (rootHint == null) return path
        if (path == rootHint) return ""
        val prefix = "$rootHint/"
        return if (path.startsWith(prefix)) path.removePrefix(prefix) else path
    }
}

data class ArchiveLimits(
    val maxEntries: Int = 20_000,
    val maxExpandedBytes: Long = 4L * 1024 * 1024 * 1024,
    val maxSingleFileBytes: Long = 1024L * 1024 * 1024,
)

data class ExtractionStats(
    val entries: Int,
    val files: Int,
    val expandedBytes: Long,
)
