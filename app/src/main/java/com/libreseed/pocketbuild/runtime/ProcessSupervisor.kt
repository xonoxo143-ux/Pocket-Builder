package com.libreseed.pocketbuild.runtime

import java.io.File
import java.time.Duration
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

data class ProcessSpec(
    val command: List<String>,
    val workingDirectory: File,
    val environment: Map<String, String> = emptyMap(),
    val inheritEnvironment: Boolean = true,
    val timeout: Duration? = null,
) {
    init {
        require(command.isNotEmpty()) { "Process command is empty." }
        require(command.none { it.contains('\u0000') }) { "Process arguments may not contain NUL bytes." }
        require(workingDirectory.isDirectory) { "Working directory does not exist." }
        require(environment.keys.none { it.isBlank() || '=' in it || '\u0000' in it })
        require(environment.values.none { '\u0000' in it })
        require(timeout == null || !timeout.isNegative)
    }
}

enum class ProcessStream {
    STDOUT,
    STDERR,
}

data class ProcessLine(
    val stream: ProcessStream,
    val text: String,
)

data class ProcessResult(
    val exitCode: Int?,
    val cancelled: Boolean,
    val timedOut: Boolean,
    val runtime: Duration,
    val outputDrainComplete: Boolean,
)

class ProcessSupervisor(
    private val gracefulStop: Duration = Duration.ofSeconds(2),
) {
    fun run(
        spec: ProcessSpec,
        cancelled: AtomicBoolean = AtomicBoolean(false),
        onLine: (ProcessLine) -> Unit = {},
    ): ProcessResult {
        val startedAt = System.nanoTime()
        val builder = ProcessBuilder(spec.command)
            .directory(spec.workingDirectory)
            .redirectInput(ProcessBuilder.Redirect.PIPE)

        if (!spec.inheritEnvironment) builder.environment().clear()
        builder.environment().putAll(spec.environment)
        val process = builder.start()
        process.outputStream.close()

        val drainFailures = Collections.synchronizedList(mutableListOf<Throwable>())
        val lineLock = Any()
        val serializedLineSink: (ProcessLine) -> Unit = { line -> synchronized(lineLock) { onLine(line) } }
        val stdout = drain(process.inputStream.bufferedReader(), ProcessStream.STDOUT, drainFailures, serializedLineSink)
        val stderr = drain(process.errorStream.bufferedReader(), ProcessStream.STDERR, drainFailures, serializedLineSink)

        var timedOut = false
        while (process.isAlive) {
            if (cancelled.get()) {
                terminate(process)
                break
            }
            val timeout = spec.timeout
            if (timeout != null && elapsed(startedAt) >= timeout) {
                timedOut = true
                terminate(process)
                break
            }
            Thread.sleep(50)
        }

        val exitCode = runCatching {
            process.waitFor()
            process.exitValue()
        }.getOrNull()
        stdout.join(2_000)
        stderr.join(2_000)
        return ProcessResult(
            exitCode = exitCode,
            cancelled = cancelled.get(),
            timedOut = timedOut,
            runtime = elapsed(startedAt),
            outputDrainComplete = !stdout.isAlive && !stderr.isAlive && drainFailures.isEmpty(),
        )
    }

    private fun drain(
        reader: java.io.BufferedReader,
        stream: ProcessStream,
        failures: MutableList<Throwable>,
        onLine: (ProcessLine) -> Unit,
    ): Thread {
        return thread(name = "pocketbuild-${stream.name.lowercase()}", isDaemon = true) {
            runCatching {
                reader.useLines { lines -> lines.forEach { onLine(ProcessLine(stream, it)) } }
            }.onFailure(failures::add)
        }
    }

    private fun terminate(process: Process) {
        process.destroy()
        if (!process.waitFor(gracefulStop.toMillis(), TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            process.waitFor(gracefulStop.toMillis(), TimeUnit.MILLISECONDS)
        }
    }

    private fun elapsed(startedAt: Long): Duration = Duration.ofNanos(System.nanoTime() - startedAt)
}
