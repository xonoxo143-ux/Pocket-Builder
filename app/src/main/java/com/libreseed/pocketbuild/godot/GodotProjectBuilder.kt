package com.libreseed.pocketbuild.godot

import android.content.Context
import android.os.Build
import com.libreseed.pocketbuild.model.SourceKind
import com.libreseed.pocketbuild.model.WorkspaceSummary
import java.io.File

class GodotProjectBuilder(private val context: Context) {
    data class Progress(val stage: String, val detail: String, val fraction: Float)
    data class Artifact(val file: File, val packedFiles: Int, val sourceBytes: Long)

    private val pckWriter = GodotPckWriter()
    private val templatePatcher = ApkTemplatePatcher()
    private val signingService = ApkSigningService()

    fun build(workspace: WorkspaceSummary, onProgress: (Progress) -> Unit): Artifact {
        require(workspace.sourceKind == SourceKind.GODOT) { "Only Godot projects are supported by this builder." }
        require(Build.SUPPORTED_ABIS.contains("arm64-v8a")) {
            "This build contains the ARM64 Godot template, but this device does not support ARM64 apps."
        }

        val projectRoot = File(workspace.rootPath)
        require(File(projectRoot, "project.godot").isFile) { "The workspace no longer contains project.godot." }

        val temporaryRoot = File(context.cacheDir, "godot-build/${workspace.id}")
        temporaryRoot.deleteRecursively()
        check(temporaryRoot.mkdirs()) { "Cannot create the temporary build directory." }

        val templateApk = File(temporaryRoot, "template.apk")
        val projectPck = File(temporaryRoot, "project.pck")
        val unsignedApk = File(temporaryRoot, "unsigned.apk")
        val outputDirectory = File(context.filesDir, "builds").apply { mkdirs() }
        val outputApk = File(outputDirectory, "${safeName(workspace.displayName)}-godot-4.7-arm64.apk")

        try {
            onProgress(Progress("Preparing template", "Loading the bundled Godot 4.7 runtime.", 0.03f))
            context.assets.open(TEMPLATE_ASSET).use { input ->
                templateApk.outputStream().buffered().use { output -> input.copyTo(output) }
            }
            check(templateApk.length() > 0) { "The bundled Godot runtime template is empty." }

            val packResult = pckWriter.write(projectRoot, projectPck) { index, total, path ->
                val fraction = if (total == 0) 0.1f else 0.08f + (index.toFloat() / total) * 0.57f
                onProgress(Progress("Packing project", path, fraction.coerceIn(0.08f, 0.65f)))
            }

            onProgress(Progress("Creating APK", "Combining the project with the Godot runtime.", 0.72f))
            templatePatcher.patch(templateApk, projectPck, unsignedApk)

            onProgress(Progress("Signing APK", "Applying the device-local PocketBuild signature.", 0.9f))
            outputApk.delete()
            signingService.sign(unsignedApk, outputApk)
            check(outputApk.isFile && outputApk.length() > 0) { "The signed APK was not created." }

            onProgress(Progress("APK ready", outputApk.name, 1f))
            return Artifact(outputApk, packResult.files, packResult.sourceBytes)
        } finally {
            temporaryRoot.deleteRecursively()
        }
    }

    private fun safeName(value: String): String {
        return value.lowercase()
            .replace(Regex("[^a-z0-9._-]+"), "-")
            .trim('-', '.')
            .take(48)
            .ifBlank { "godot-project" }
    }

    companion object {
        const val TEMPLATE_ASSET = "templates/godot-4.7-arm64-template.apk"
    }
}
