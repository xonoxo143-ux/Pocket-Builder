package com.libreseed.pocketbuild.godot

import java.io.BufferedOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Replaces the payload of a prebuilt Godot template APK while preserving Android resources. */
class ApkTemplatePatcher {
    fun patch(templateApk: File, projectPck: File, outputApk: File) {
        require(templateApk.isFile) { "The bundled Godot template is missing." }
        require(projectPck.isFile) { "The generated project pack is missing." }
        outputApk.parentFile?.mkdirs()
        outputApk.delete()

        ZipFile(templateApk).use { input ->
            ZipOutputStream(BufferedOutputStream(outputApk.outputStream())).use { output ->
                val entries = input.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (shouldReplace(entry.name)) continue
                    copyEntry(input, entry, output)
                }
                addStoredFile(output, "assets/project.pck", projectPck)
                addStoredBytes(output, "assets/_cl_", commandLineBytes())
            }
        }
        check(outputApk.length() > 0) { "The unsigned APK was not created." }
    }

    private fun shouldReplace(name: String): Boolean {
        val normalized = name.replace('\\', '/')
        return normalized.startsWith("META-INF/", ignoreCase = true) ||
            normalized == "assets/project.pck" ||
            normalized == "assets/assets.sparsepck" ||
            normalized == "assets/_cl_"
    }

    private fun copyEntry(input: ZipFile, source: ZipEntry, output: ZipOutputStream) {
        val target = ZipEntry(source.name).apply {
            method = source.method
            time = source.time
            comment = source.comment
            if (source.method == ZipEntry.STORED) {
                size = source.size
                crc = source.crc
            }
        }
        output.putNextEntry(target)
        if (!source.isDirectory) {
            input.getInputStream(source).buffered().use { it.copyTo(output) }
        }
        output.closeEntry()
    }

    private fun addStoredFile(output: ZipOutputStream, name: String, file: File) {
        val crc = CRC32()
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                crc.update(buffer, 0, read)
            }
        }
        val entry = ZipEntry(name).apply {
            method = ZipEntry.STORED
            size = file.length()
            this.crc = crc.value
        }
        output.putNextEntry(entry)
        file.inputStream().buffered().use { it.copyTo(output) }
        output.closeEntry()
    }

    private fun addStoredBytes(output: ZipOutputStream, name: String, bytes: ByteArray) {
        val crc = CRC32().apply { update(bytes) }
        val entry = ZipEntry(name).apply {
            method = ZipEntry.STORED
            size = bytes.size.toLong()
            this.crc = crc.value
        }
        output.putNextEntry(entry)
        output.write(bytes)
        output.closeEntry()
    }

    private fun commandLineBytes(): ByteArray {
        val arguments = listOf(
            "--main-pack",
            "project.pck",
            "--xr_mode_regular",
            "--xr-mode",
            "off",
            "--fullscreen",
            "--background_color",
            "#000000",
        )
        return buildList<Byte> {
            addIntLe(arguments.size)
            arguments.forEach { argument ->
                val encoded = argument.toByteArray(Charsets.UTF_8)
                addIntLe(encoded.size)
                encoded.forEach { add(it) }
            }
        }.toByteArray()
    }

    private fun MutableList<Byte>.addIntLe(value: Int) {
        add((value and 0xff).toByte())
        add(((value ushr 8) and 0xff).toByte())
        add(((value ushr 16) and 0xff).toByte())
        add(((value ushr 24) and 0xff).toByte())
    }
}
