package com.libreseed.pocketbuild.toolchain

import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

class ToolchainInstaller(
    private val root: File,
    private val sourcePolicy: TrustedSourcePolicy = TrustedSourcePolicy(),
    downloadClient: DownloadClient? = null,
) {
    private val downloadClient = downloadClient ?: HttpsDownloadClient(sourcePolicy)

    fun install(
        definition: ToolchainPackDefinition,
        cancelled: AtomicBoolean = AtomicBoolean(false),
        onProgress: (InstallProgress) -> Unit = {},
    ): PackAudit {
        root.mkdirs()
        val staging = File(root, ".${definition.id}-${definition.version}.staging")
        staging.deleteRecursively()
        check(staging.mkdirs()) { "Cannot create toolchain staging directory." }
        val store = ToolchainStore(root)

        try {
            val totalKnownBytes = definition.artifacts
                .takeIf { artifacts -> artifacts.all { it.compressedBytes != null } }
                ?.sumOf { it.compressedBytes!! }
            var completedKnownBytes = 0L
            definition.artifacts.forEachIndexed { index, artifact ->
                check(!cancelled.get()) { "Toolchain installation cancelled." }
                val uri = sourcePolicy.validate(artifact.downloadUrl)
                val output = File(staging, artifact.relativePath)
                val parent = output.parentFile
                check(parent == null || parent.mkdirs() || parent.isDirectory) {
                    "Cannot create ${artifact.relativePath}"
                }
                val partial = File(output.path + ".part")
                partial.delete()

                onProgress(
                    InstallProgress(
                        packId = definition.id,
                        artifactPath = artifact.relativePath,
                        artifactIndex = index,
                        artifactCount = definition.artifacts.size,
                        completedBytes = completedKnownBytes,
                        totalBytes = totalKnownBytes,
                        state = InstallState.DOWNLOADING,
                    ),
                )

                downloadClient.download(uri, partial, cancelled) { artifactBytes ->
                    onProgress(
                        InstallProgress(
                            packId = definition.id,
                            artifactPath = artifact.relativePath,
                            artifactIndex = index,
                            artifactCount = definition.artifacts.size,
                            completedBytes = completedKnownBytes + artifactBytes,
                            totalBytes = totalKnownBytes,
                            state = InstallState.DOWNLOADING,
                        ),
                    )
                }
                check(!cancelled.get()) { "Toolchain installation cancelled." }
                artifact.compressedBytes?.let { expectedBytes ->
                    check(partial.length() == expectedBytes) { "Unexpected size: ${artifact.relativePath}" }
                }
                check(sha256(partial) == artifact.sha256.lowercase()) {
                    "Checksum mismatch: ${artifact.relativePath}"
                }
                check(partial.renameTo(output)) { "Cannot finalize ${artifact.relativePath}" }
                completedKnownBytes += artifact.compressedBytes ?: output.length()
            }

            store.writeMetadata(staging, definition)
            onProgress(
                InstallProgress(
                    packId = definition.id,
                    artifactPath = null,
                    artifactIndex = definition.artifacts.size,
                    artifactCount = definition.artifacts.size,
                    completedBytes = completedKnownBytes,
                    totalBytes = totalKnownBytes,
                    state = InstallState.VERIFYING,
                ),
            )
            store.promote(staging, definition)
            return store.fullAudit(definition)
        } catch (error: Throwable) {
            staging.deleteRecursively()
            throw error
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

fun interface DownloadClient {
    fun download(uri: URI, destination: File, cancelled: AtomicBoolean, onBytes: (Long) -> Unit)
}

class HttpsDownloadClient(
    private val sourcePolicy: TrustedSourcePolicy = TrustedSourcePolicy(),
    private val maxRedirects: Int = 5,
) : DownloadClient {
    override fun download(uri: URI, destination: File, cancelled: AtomicBoolean, onBytes: (Long) -> Unit) {
        var current = sourcePolicy.validate(uri.toString())
        repeat(maxRedirects + 1) { redirectCount ->
            check(!cancelled.get()) { "Toolchain installation cancelled." }
            val connection = current.toURL().openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 30_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("User-Agent", "PocketBuild/0.1")

            try {
                connection.connect()
                val code = connection.responseCode
                if (code in 300..399) {
                    check(redirectCount < maxRedirects) { "Too many toolchain download redirects." }
                    val location = connection.getHeaderField("Location") ?: error("Redirect has no Location header.")
                    current = sourcePolicy.validate(current.resolve(location).toString())
                    return@repeat
                }
                check(code in 200..299) { "Download failed with HTTP $code" }
                connection.inputStream.use { input -> copyBounded(input, destination, cancelled, onBytes) }
                return
            } finally {
                connection.disconnect()
            }
        }
        error("Toolchain download did not complete.")
    }

    private fun copyBounded(
        input: InputStream,
        destination: File,
        cancelled: AtomicBoolean,
        onBytes: (Long) -> Unit,
    ) {
        var total = 0L
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        destination.outputStream().buffered().use { output ->
            while (true) {
                check(!cancelled.get()) { "Toolchain installation cancelled." }
                val read = input.read(buffer)
                if (read < 0) break
                output.write(buffer, 0, read)
                total += read
                onBytes(total)
            }
        }
    }
}

enum class InstallState {
    DOWNLOADING,
    VERIFYING,
}

data class InstallProgress(
    val packId: String,
    val artifactPath: String?,
    val artifactIndex: Int,
    val artifactCount: Int,
    val completedBytes: Long,
    val totalBytes: Long?,
    val state: InstallState,
)
