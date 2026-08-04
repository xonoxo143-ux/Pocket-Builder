package com.libreseed.pocketbuild.gradle

import android.content.Context
import com.android.apksig.ApkVerifier
import com.libreseed.pocketbuild.godot.ApkSigningService
import com.libreseed.pocketbuild.model.SourceKind
import com.libreseed.pocketbuild.model.WorkspaceSummary
import com.libreseed.pocketbuild.runtime.ProcessLine
import com.libreseed.pocketbuild.runtime.ProcessSpec
import com.libreseed.pocketbuild.runtime.ProcessSupervisor
import java.io.File
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean

class AndroidGradleProjectBuilder(private val context: Context) {
    data class Progress(val stage: String, val detail: String, val fraction: Float?)
    data class Artifact(val file: File, val sourceApk: File, val logFile: File, val gradleVersion: String?)

    private val inspector = GradleProjectInspector()
    private val toolchain = MobileGradleToolchain(context)
    private val supervisor = ProcessSupervisor()
    private val signer = ApkSigningService()

    fun isToolchainReady(): Boolean = toolchain.isReady()

    fun build(
        workspace: WorkspaceSummary,
        cancelled: AtomicBoolean = AtomicBoolean(false),
        onProgress: (Progress) -> Unit = {},
    ): Artifact {
        require(workspace.sourceKind == SourceKind.ANDROID_GRADLE) { "This builder requires an Android Gradle project." }
        val root = File(workspace.rootPath)
        onProgress(Progress("Inspecting Gradle project", "Reading wrapper, plugins and Android SDK requirements.", 0.01f))
        val requirements = inspector.inspect(root)
        val environment = toolchain.ensureInstalled(requirements.compileSdks) { progress ->
            onProgress(Progress(progress.stage, progress.detail, progress.fraction?.times(0.72f)))
        }
        check(!cancelled.get()) { "Build cancelled." }

        val logs = File(context.filesDir, "build-logs").apply { mkdirs() }
        val logFile = File(logs, "${safeName(workspace.displayName)}-${System.currentTimeMillis()}.log")
        val outputDirectory = File(context.filesDir, "builds").apply { mkdirs() }
        val command = gradleCommand(requirements, environment)
        val processEnvironment = gradleEnvironment(environment)

        onProgress(
            Progress(
                "Running Gradle",
                "${requirements.gradleVersion?.let { "Gradle $it · " }.orEmpty()}${requirements.task}",
                0.74f,
            ),
        )
        logFile.bufferedWriter().use { log ->
            log.appendLine("PocketHost local Gradle build")
            log.appendLine("Project: ${root.absolutePath}")
            log.appendLine("Command: ${command.joinToString(" ")}")
            val result = supervisor.run(
                ProcessSpec(
                    command = command,
                    workingDirectory = root,
                    environment = processEnvironment,
                    inheritEnvironment = true,
                    timeout = Duration.ofMinutes(90),
                ),
                cancelled = cancelled,
            ) { line: ProcessLine ->
                val prefix = line.stream.name.lowercase()
                log.appendLine("[$prefix] ${line.text}")
                log.flush()
                if (line.text.isNotBlank()) {
                    onProgress(Progress("Running Gradle", line.text.takeLast(500), 0.80f))
                }
            }
            check(!result.cancelled) { "Gradle build cancelled." }
            check(!result.timedOut) { "Gradle build exceeded the 90-minute limit." }
            check(result.exitCode == 0) {
                "Gradle exited with code ${result.exitCode}. Build log: ${logFile.absolutePath}"
            }
            check(result.outputDrainComplete) { "Gradle finished, but its complete output could not be captured." }
        }

        onProgress(Progress("Finding APK", "Inspecting Gradle output directories.", 0.94f))
        val sourceApk = findBestApk(root)
            ?: error("Gradle succeeded but no installable APK was found under build/outputs/apk.")
        val verified = ApkVerifier.Builder(sourceApk).build().verify().isVerified
        val destination = File(outputDirectory, "${safeName(workspace.displayName)}-gradle-debug.apk")
        destination.delete()
        if (verified) {
            sourceApk.copyTo(destination, overwrite = true)
        } else {
            onProgress(Progress("Signing APK", "Applying the device-local PocketHost signing key.", 0.97f))
            signer.sign(sourceApk, destination)
        }
        val verification = ApkVerifier.Builder(destination).build().verify()
        check(verification.isVerified) {
            "The generated APK failed signature verification: ${verification.errors.joinToString()}"
        }
        onProgress(Progress("APK ready", destination.name, 1f))
        return Artifact(destination, sourceApk, logFile, requirements.gradleVersion)
    }

    private fun gradleCommand(requirements: GradleProjectRequirements, env: MobileGradleEnvironment): List<String> {
        return listOf(
            env.java.absolutePath,
            "-Dorg.gradle.appname=gradlew",
            "-Dorg.gradle.native=false",
            "-Dorg.gradle.vfs.watch=false",
            "-Djava.io.tmpdir=${env.temp.absolutePath}",
            "-Duser.home=${env.home.absolutePath}",
            "-Djavax.net.ssl.trustStore=${env.trustStore.absolutePath}",
            "-Djavax.net.ssl.trustStorePassword=changeit",
            "-classpath",
            requirements.wrapperJar.absolutePath,
            "org.gradle.wrapper.GradleWrapperMain",
            "--no-daemon",
            "--stacktrace",
            "--console=plain",
            "--warning-mode=all",
            "-Pandroid.aapt2FromMavenOverride=${env.aapt2.absolutePath}",
            requirements.task,
        )
    }

    private fun gradleEnvironment(env: MobileGradleEnvironment): Map<String, String> {
        val libraries = listOf(
            File(env.prefix, "lib").absolutePath,
            File(env.javaHome, "lib").absolutePath,
            File(env.javaHome, "lib/server").absolutePath,
        ).joinToString(":")
        return mapOf(
            "PREFIX" to env.prefix.absolutePath,
            "HOME" to env.home.absolutePath,
            "TMPDIR" to env.temp.absolutePath,
            "JAVA_HOME" to env.javaHome.absolutePath,
            "ANDROID_HOME" to env.sdk.absolutePath,
            "ANDROID_SDK_ROOT" to env.sdk.absolutePath,
            "GRADLE_USER_HOME" to env.gradleHome.absolutePath,
            "LD_LIBRARY_PATH" to libraries,
            "PATH" to listOf(
                File(env.javaHome, "bin").absolutePath,
                File(env.sdk, "build-tools/36.0.0").absolutePath,
                File(env.prefix, "bin").absolutePath,
                "/system/bin",
                "/system/xbin",
            ).joinToString(":"),
            "GRADLE_OPTS" to listOf(
                "-Dorg.gradle.native=false",
                "-Dorg.gradle.vfs.watch=false",
                "-Djava.io.tmpdir=${env.temp.absolutePath}",
                "-Djavax.net.ssl.trustStore=${env.trustStore.absolutePath}",
                "-Djavax.net.ssl.trustStorePassword=changeit",
            ).joinToString(" "),
        )
    }

    private fun findBestApk(root: File): File? {
        return root.walkTopDown()
            .filter { file ->
                file.isFile && file.extension.equals("apk", true) &&
                    file.invariantSeparatorsPath.contains("/build/outputs/apk/") &&
                    !file.name.contains("androidTest", true) && !file.name.contains("unaligned", true)
            }
            .sortedWith(
                compareByDescending<File> { it.name.contains("debug", true) }
                    .thenByDescending { it.lastModified() }
                    .thenByDescending { it.length() },
            )
            .firstOrNull()
    }

    private fun safeName(value: String): String = value.lowercase()
        .replace(Regex("[^a-z0-9._-]+"), "-")
        .trim('-', '.')
        .take(48)
        .ifBlank { "android-project" }
}
