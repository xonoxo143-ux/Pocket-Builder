package com.libreseed.pocketbuild.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.time.Duration

class ProcessSupervisorTest {
    @Test
    fun capturesSeparateOutputStreams() {
        val lines = mutableListOf<ProcessLine>()
        val result = ProcessSupervisor().run(
            ProcessSpec(
                command = listOf("/bin/sh", "-c", "echo output; echo error >&2"),
                workingDirectory = Files.createTempDirectory("pb-process").toFile(),
                timeout = Duration.ofSeconds(5),
            ),
            onLine = lines::add,
        )
        assertEquals(0, result.exitCode)
        assertTrue(result.outputDrainComplete)
        assertTrue(lines.any { it.stream == ProcessStream.STDOUT && it.text == "output" })
        assertTrue(lines.any { it.stream == ProcessStream.STDERR && it.text == "error" })
    }

    @Test
    fun terminatesOnTimeout() {
        val result = ProcessSupervisor(Duration.ofMillis(100)).run(
            ProcessSpec(
                command = listOf("/bin/sh", "-c", "exec sleep 3"),
                workingDirectory = Files.createTempDirectory("pb-process-timeout").toFile(),
                timeout = Duration.ofMillis(150),
            ),
        )
        assertTrue(result.timedOut)
        assertTrue(result.runtime < Duration.ofSeconds(2))
    }
}
