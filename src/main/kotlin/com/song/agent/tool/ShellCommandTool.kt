package com.song.agent.tool

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.nio.file.Path
import java.util.*
import java.util.concurrent.TimeUnit

class ShellCommandTool : SimpleTool<ShellCommandTool.Args>(
    argsSerializer = Args.serializer(),
    name = "shell_command",
    description = """
Runs a shell command and returns its output.
- Always set the `workdir` param when using the shell function. Do not use `cd` unless absolutely necessary.
    """.trimIndent()
) {

    @Serializable
    data class Args(
        @property:LLMDescription("The shell script to execute in the user's default shell")
        val command: String,
        @property:LLMDescription("The working directory to execute the command in")
        val workdir: String
    )

    override suspend fun execute(args: Args): String {
        require(args.command.isNotBlank()) { "command must not be empty" }

        val workDir = resolveWorkingDir(args.workdir)
        val timeoutMs = DEFAULT_TIMEOUT_MS

        val argv = deriveExecArgs(args.command)

        val process = ProcessBuilder(argv)
            .directory(workDir.toFile())
            .redirectErrorStream(true)
            .start()

        val output = ByteArrayOutputStream()
        val readerThread = Thread {
            process.inputStream.use { input ->
                val buffer = ByteArray(4096)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    val nextTotal = total + read
                    if (nextTotal > MAX_OUTPUT_BYTES) {
                        output.write(buffer, 0, MAX_OUTPUT_BYTES - total)
                        break
                    } else {
                        output.write(buffer, 0, read)
                        total = nextTotal
                    }
                }
            }
        }

        readerThread.start()

        val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)

        if (!finished) {
            process.destroyForcibly()
            return "Command timed out after ${timeoutMs}ms."
        }

        readerThread.join()

        val exitCode = process.exitValue()
        val text = output.toString(Charset.defaultCharset())

        return buildString {
            appendLine("\$ ${argv.joinToString(" ")}")
            appendLine("exit code: $exitCode")
            if (text.isNotBlank()) {
                appendLine("--- output (truncated to $MAX_OUTPUT_BYTES bytes) ---")
                append(text.trimEnd())
            } else {
                appendLine("--- no output ---")
            }
        }
    }

    private fun resolveWorkingDir(workdir: String?): Path {
        val base = if (workdir.isNullOrBlank()) {
            Path.of(System.getProperty("user.dir"))
        } else {
            Path.of(workdir)
        }
        return base.normalize()
    }

    private fun deriveExecArgs(command: String): List<String> {
        val os = System.getProperty("os.name").lowercase(Locale.getDefault())
        return if (os.contains("win")) {
            listOf("pwsh.exe", "-Command", command)
        } else {
            val shellPath = System.getenv("SHELL")
                ?.takeIf { it.isNotBlank() }
                ?: "/bin/bash"

            val shellName = Path.of(shellPath).fileName.toString()

            when (shellName) {
                "bash", "zsh" -> listOf(shellPath, "-lc", command)
                else -> listOf(shellPath, "-c", command)
            }
        }
    }

    companion object {
        private const val DEFAULT_TIMEOUT_MS = 10_000L
        private const val MAX_OUTPUT_BYTES = 16 * 1024
    }
}