package com.libreseed.pocketbuild.godot

import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/** Writes an unencrypted Godot 4 PCK without requiring the editor executable. */
class GodotPckWriter {
    data class Result(val files: Int, val sourceBytes: Long, val packedBytes: Long)

    fun write(projectRoot: File, output: File, onProgress: (Int, Int, String) -> Unit = { _, _, _ -> }): Result {
        require(File(projectRoot, "project.godot").isFile) { "project.godot is missing from the workspace." }
        val files = projectRoot.walkTopDown()
            .filter { it.isFile }
            .map { file -> file to file.relativeTo(projectRoot).invariantSeparatorsPath }
            .filter { (_, path) -> includePath(path) }
            .sortedBy { (_, path) -> path }
            .toList()
        require(files.isNotEmpty()) { "The Godot project contains no exportable files." }

        output.parentFile?.mkdirs()
        output.delete()
        val entries = ArrayList<PckEntry>(files.size)
        var sourceBytes = 0L

        RandomAccessFile(output, "rw").use { file ->
            file.setLength(0)
            file.writeIntLe(PACK_HEADER_MAGIC)
            file.writeIntLe(PACK_FORMAT_VERSION)
            file.writeIntLe(4)
            file.writeIntLe(7)
            file.writeIntLe(0)
            file.writeIntLe(PACK_REL_FILEBASE)

            val fileBasePointer = file.filePointer
            file.writeLongLe(0)
            val directoryPointer = file.filePointer
            file.writeLongLe(0)
            repeat(16) { file.writeIntLe(0) }
            file.align(PCK_ALIGNMENT)

            val fileBase = file.filePointer
            file.patchLongLe(fileBasePointer, fileBase)

            files.forEachIndexed { index, (source, path) ->
                onProgress(index, files.size, path)
                val offset = file.filePointer
                val digest = MessageDigest.getInstance("MD5")
                var size = 0L
                source.inputStream().buffered().use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        file.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        size += read
                    }
                }
                sourceBytes += size
                entries += PckEntry(path, offset - fileBase, size, digest.digest())
                file.align(PCK_ALIGNMENT)
            }

            entries.sortBy { it.path }
            file.align(PCK_ALIGNMENT)
            val directoryOffset = file.filePointer
            file.patchLongLe(directoryPointer, directoryOffset)
            file.seek(directoryOffset)
            file.writeIntLe(entries.size)

            entries.forEach { entry ->
                val pathBytes = entry.path.toByteArray(Charsets.UTF_8)
                val pathPadding = padding(4, pathBytes.size.toLong()).toInt()
                file.writeIntLe(pathBytes.size + pathPadding)
                file.write(pathBytes)
                repeat(pathPadding) { file.write(0) }
                file.writeLongLe(entry.relativeOffset)
                file.writeLongLe(entry.size)
                file.write(entry.md5)
                file.writeIntLe(0)
            }
        }

        onProgress(files.size, files.size, "Complete")
        return Result(files.size, sourceBytes, output.length())
    }

    private fun includePath(path: String): Boolean {
        if (path.isBlank()) return false
        if (path == ".pocketbuild-workspace" || path == "export_presets.cfg") return false
        if (path.endsWith(".apk") || path.endsWith(".aab") || path.endsWith(".jks") || path.endsWith(".keystore")) return false
        val excludedPrefixes = listOf(
            ".git/",
            ".github/",
            ".gradle/",
            "build/",
            ".godot/editor/",
            ".godot/exported/",
            ".godot/mono/temp/",
        )
        return excludedPrefixes.none(path::startsWith)
    }

    private data class PckEntry(
        val path: String,
        val relativeOffset: Long,
        val size: Long,
        val md5: ByteArray,
    )

    private fun RandomAccessFile.align(alignment: Int) {
        val count = padding(alignment, filePointer)
        repeat(count.toInt()) { write(0) }
    }

    private fun RandomAccessFile.patchLongLe(pointer: Long, value: Long) {
        val current = filePointer
        seek(pointer)
        writeLongLe(value)
        seek(current)
    }

    private fun RandomAccessFile.writeIntLe(value: Int) {
        write(value and 0xff)
        write((value ushr 8) and 0xff)
        write((value ushr 16) and 0xff)
        write((value ushr 24) and 0xff)
    }

    private fun RandomAccessFile.writeLongLe(value: Long) {
        repeat(8) { shift -> write(((value ushr (shift * 8)) and 0xff).toInt()) }
    }

    private fun padding(alignment: Int, position: Long): Long {
        val remainder = position % alignment
        return if (remainder == 0L) 0 else alignment - remainder
    }

    companion object {
        private const val PACK_HEADER_MAGIC = 0x43504447
        private const val PACK_FORMAT_VERSION = 4
        private const val PACK_REL_FILEBASE = 1 shl 1
        private const val PCK_ALIGNMENT = 16
    }
}
