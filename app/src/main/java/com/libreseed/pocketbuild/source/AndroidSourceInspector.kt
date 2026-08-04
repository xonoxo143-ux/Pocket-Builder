package com.libreseed.pocketbuild.source

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.libreseed.pocketbuild.model.IncomingSource
import com.libreseed.pocketbuild.model.SourceKind
import java.io.BufferedInputStream
import java.util.zip.ZipInputStream

class AndroidSourceInspector(private val context: Context) {
    fun inspect(uri: Uri): IncomingSource {
        val resolver = context.contentResolver
        val metadata = queryMetadata(resolver, uri)
        val initialKind = SourceClassifier.classifyFileName(metadata.displayName, metadata.mimeType)

        val archiveResult = if (initialKind == SourceKind.ZIP_UNKNOWN) {
            inspectArchive(resolver, uri)
        } else {
            null
        }

        return IncomingSource(
            uri = uri,
            displayName = metadata.displayName,
            mimeType = metadata.mimeType,
            sizeBytes = metadata.sizeBytes,
            kind = archiveResult?.kind ?: initialKind,
            projectRootHint = archiveResult?.projectRootHint,
            warnings = archiveResult?.warnings.orEmpty(),
        )
    }

    private fun inspectArchive(resolver: ContentResolver, uri: Uri): ArchiveClassification? {
        return runCatching {
            resolver.openInputStream(uri)?.use { raw ->
                ZipInputStream(BufferedInputStream(raw)).use { zip ->
                    val names = sequence {
                        var entry = zip.nextEntry
                        while (entry != null) {
                            yield(entry.name)
                            zip.closeEntry()
                            entry = zip.nextEntry
                        }
                    }
                    SourceClassifier.classifyArchiveEntries(names)
                }
            }
        }.getOrNull()
    }

    private fun queryMetadata(resolver: ContentResolver, uri: Uri): SourceMetadata {
        var displayName: String? = null
        var size: Long? = null
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIndex >= 0) displayName = cursor.getString(nameIndex)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
                }
            }

        return SourceMetadata(
            displayName = displayName ?: uri.lastPathSegment ?: "untitled-source",
            mimeType = resolver.getType(uri),
            sizeBytes = size,
        )
    }
}

private data class SourceMetadata(
    val displayName: String,
    val mimeType: String?,
    val sizeBytes: Long?,
)
