package com.song.agent.tool

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.TimeUnit
import kotlin.collections.iterator

/**
 * A small JVM-only process runner with:
 * - timeout support
 * - stdout/stderr capture with a hard byte cap (to avoid huge tool outputs)
 * - best-effort process-tree termination on timeout
 */
internal object JvmProcessRunner {

    enum class DecodeMode { REPLACE, IGNORE }

    data class Output(
        val stdout: String,
        val stderr: String,
        val exitCode: Int
    )

    suspend fun run(
        command: List<String>,
        workDir: File,
        env: Map<String, String>,
        timeoutSeconds: Long,
        maxOutputBytes: Int,
        decodeMode: DecodeMode = DecodeMode.REPLACE,
    ): Output = withContext(Dispatchers.IO) {
        require(timeoutSeconds > 0) { "timeoutSeconds must be > 0" }
        require(maxOutputBytes > 0) { "maxOutputBytes must be > 0" }

        val pb = ProcessBuilder(command)
            .directory(workDir)

        val pbEnv = pb.environment()
        for ((k, v) in env) {
            pbEnv[k] = v
        }

        val process = try {
            pb.start()
        } catch (e: IOException) {
            throw ToolExecutionException("Failed to start process: ${command.joinToString(" ")}", e)
        }

        // We don't support interactive commands (tools should be non-interactive).
        try {
            process.outputStream.close()
        } catch (_: IOException) {
            // ignore
        }

        coroutineScope {
            val stdoutDeferred = async { readStreamLimited(process.inputStream, maxOutputBytes) }
            val stderrDeferred = async { readStreamLimited(process.errorStream, maxOutputBytes) }

            val finished = try {
                process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            } catch (e: InterruptedException) {
                destroyProcessTree(process)
                throw ToolExecutionException("Process wait interrupted: ${command.joinToString(" ")}", e)
            }

            if (!finished) {
                destroyProcessTree(process)
                // Drain whatever we managed to collect so far (best-effort).
                stdoutDeferred.await()
                stderrDeferred.await()
                throw ToolExecutionException("Command timed out after ${timeoutSeconds}s: ${command.joinToString(" ")}")
            }

            val exit = process.exitValue()
            val stdoutBytes = stdoutDeferred.await()
            val stderrBytes = stderrDeferred.await()

            val stdout = decodeUtf8(stdoutBytes, decodeMode)
            val stderr = decodeUtf8(stderrBytes, decodeMode)

            Output(stdout = stdout, stderr = stderr, exitCode = exit)
        }
    }

    suspend fun runShell(
        command: String,
        workDir: File,
        env: Map<String, String>,
        timeoutSeconds: Long,
        maxOutputBytes: Int,
        decodeMode: DecodeMode = DecodeMode.REPLACE,
    ): Output {
        val shellCommand = listOf("bash", "-lc", command)
        return run(
            command = shellCommand,
            workDir = workDir,
            env = env,
            timeoutSeconds = timeoutSeconds,
            maxOutputBytes = maxOutputBytes,
            decodeMode = decodeMode,
        )
    }

    private fun destroyProcessTree(process: Process) {
        // Best-effort: Java 9+ process tree handling.
        try {
            val handle = process.toHandle()
            handle.descendants().forEach { it.destroyForcibly() }
            handle.destroyForcibly()
        } catch (_: Throwable) {
            try {
                process.destroyForcibly()
            } catch (_: Throwable) {
                // ignore
            }
        }
    }

    private fun readStreamLimited(stream: InputStream, maxBytes: Int): ByteArray {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        val out = ByteArrayOutputStream(minOf(maxBytes, DEFAULT_BUFFER_SIZE))

        var stored = 0
        while (true) {
            val n = try {
                stream.read(buffer)
            } catch (_: IOException) {
                break
            }
            if (n <= 0) break

            val remaining = maxBytes - stored
            if (remaining > 0) {
                val toCopy = minOf(remaining, n)
                out.write(buffer, 0, toCopy)
                stored += toCopy
            }
            // Discard any bytes beyond maxBytes but keep draining to avoid deadlocks.
        }
        return out.toByteArray()
    }

    private fun decodeUtf8(bytes: ByteArray, mode: DecodeMode): String {
        if (bytes.isEmpty()) return ""
        val decoder = Charsets.UTF_8.newDecoder()
        when (mode) {
            DecodeMode.REPLACE -> decoder
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE)

            DecodeMode.IGNORE -> decoder
                .onMalformedInput(CodingErrorAction.IGNORE)
                .onUnmappableCharacter(CodingErrorAction.IGNORE)
        }
        return decoder.decode(ByteBuffer.wrap(bytes)).toString()
    }
}
