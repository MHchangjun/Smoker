package com.song.agent.tool

import ai.koog.agents.core.tools.Tool
import com.song.agent.tool.ShellTool.Defaults.defaultHardDenyPrefixes
import com.song.agent.tool.ShellTool.Defaults.defaultHardDenyStandalone
import kotlinx.serialization.Serializable
import java.io.File
import kotlin.math.ceil

class ShellTool(
    private val config: Config = Config()
) : Tool<ShellTool.Args, ShellTool.Result>(
    argsSerializer = Args.serializer(),
    resultSerializer = Result.serializer(),
    name = "run_shell_command",
    description = """
Executes a given shell command (as `bash -c <command>`) in a persistent shell session with timeout, ensuring proper handling and security measures.

IMPORTANT: This tool is for terminal operations like git, npm, docker, etc. DO NOT use it for file operations (reading, writing, editing, searching, finding files) - use the specialized tools for this instead.

**Usage notes**:
- The command argument is required.
- Timeout is fixed by tool config (default 120000ms / 2 minutes).

- Avoid using `run_shell_command` with the \`find\`, \`grep\`, \`cat\`, \`head\`, \`tail\`, \`sed\`, \`awk\`, or \`echo\` commands, unless explicitly instructed or when these commands are truly necessary for the task. Instead, always prefer using the dedicated tools for these commands:
  - File search: Use `glob` (NOT find or ls)
  - Content search: Use `grep` (NOT grep or rg)
  - Read files: Use `read_file` (NOT cat/head/tail)
  - Edit files: Use `edit` (NOT sed/awk)
  - Write files: Use `write_file` (NOT echo >/cat <<EOF)
  - Communication: Output text directly (NOT echo/printf)
- Try to maintain your current working directory throughout the session by using absolute paths and avoiding usage of \`cd\`. You may use \`cd\` if the User explicitly requests it.
  <good-example>
  pytest /foo/bar/tests
  </good-example>
  <bad-example>
  cd /foo/bar && pytest tests
  </bad-example>
""".trimIndent()
) {
    data class Config(
        val workDir: File = File(System.getProperty("user.dir")),
        val max_output_bytes: Int = 16_000,
        val default_timeout_ms: Int = 120_000,
        val max_timeout_ms: Int = 600_000,
        val hard_deny_prefixes: List<String> = defaultHardDenyPrefixes(),
        val hard_deny_standalone: Set<String> = defaultHardDenyStandalone(),
    ) {
        init {
            require(max_output_bytes > 0) { "max_output_bytes must be > 0" }
            require(default_timeout_ms > 0) { "default_timeout_ms must be > 0" }
            require(max_timeout_ms > 0) { "max_timeout_ms must be > 0" }
            require(default_timeout_ms <= max_timeout_ms) { "default_timeout_ms must be <= max_timeout_ms" }
            require(workDir.isDirectory) { "workDir must be a directory: ${workDir.absolutePath}" }
        }
    }

    @Serializable
    data class Args(
        val command: String
    )

    @Serializable
    data class Result(
        val llm_content: String,
        val return_display: String,
        val stdout: String,
        val stderr: String,
        val exit_code: Int? = null,
        val signal: String? = null,
        val was_aborted: Boolean = false,
    )

    override suspend fun execute(args: Args): Result {
        val command = args.command.trim()
        if (command.isEmpty()) {
            throw ToolExecutionException("Empty command provided.")
        }

        if (detectCommandSubstitution(command)) {
            throw ToolExecutionException(
                "Command substitution using \$(), `` ` ``, <(), or >() is not allowed for security reasons."
            )
        }

        if (isHardDenied(command)) {
            throw ToolExecutionException("Command is denied: ${command.quoteForMsg()}")
        }

        val workingDirectory = config.workDir.canonicalFile
        val timeoutMs = config.default_timeout_ms

        val output = try {
            JvmProcessRunner.runShell(
                command = command,
                workDir = workingDirectory,
                env = baseEnv(),
                timeoutSeconds = timeoutSeconds(timeoutMs),
                maxOutputBytes = config.max_output_bytes,
                decodeMode = JvmProcessRunner.DecodeMode.REPLACE,
            )
        } catch (e: ToolExecutionException) {
            if (isTimeoutError(e.message)) {
                val timedOutContent =
                    "Command timed out after ${timeoutMs}ms before it could complete."
                return Result(
                    llm_content = timedOutContent,
                    return_display = "Command timed out after ${timeoutMs}ms.",
                    stdout = "",
                    stderr = "",
                    exit_code = null,
                    signal = null,
                    was_aborted = true,
                )
            }
            throw e
        }

        val llmContent = buildString {
            append("Command: ").append(command).append('\n')
            append("Directory: ").append(workingDirectory.absolutePath).append('\n')
            append("Output: ").append(output.stdout.ifBlank { "(empty)" }).append('\n')
            append("Error: ").append(output.stderr.ifBlank { "(none)" }).append('\n')
            append("Exit Code: ").append(output.exitCode).append('\n')
            append("Signal: (none)")
        }

        val returnDisplay = when {
            output.stdout.isNotBlank() -> output.stdout
            output.exitCode != 0 -> "Command exited with code: ${output.exitCode}"
            output.stderr.isNotBlank() -> "Command failed: ${output.stderr.lines().firstOrNull()?.trim().orEmpty()}"
            else -> ""
        }

        return Result(
            llm_content = llmContent,
            return_display = returnDisplay,
            stdout = output.stdout,
            stderr = output.stderr,
            exit_code = output.exitCode,
            signal = null,
            was_aborted = false,
        )
    }

    private fun timeoutSeconds(timeoutMs: Int): Long =
        ceil(timeoutMs / 1000.0).toLong().coerceAtLeast(1L)

    private fun isTimeoutError(message: String?): Boolean {
        if (message == null) return false
        return message.contains("timed out after", ignoreCase = true)
    }

    private fun detectCommandSubstitution(command: String): Boolean {
        var inSingleQuotes = false
        var inDoubleQuotes = false
        var escaped = false

        var i = 0
        while (i < command.length) {
            val ch = command[i]
            val next = command.getOrNull(i + 1)

            if (escaped) {
                escaped = false
                i++
                continue
            }

            if (ch == '\\' && !inSingleQuotes) {
                escaped = true
                i++
                continue
            }

            if (ch == '\'' && !inDoubleQuotes) {
                inSingleQuotes = !inSingleQuotes
                i++
                continue
            }

            if (ch == '"' && !inSingleQuotes) {
                inDoubleQuotes = !inDoubleQuotes
                i++
                continue
            }

            if (!inSingleQuotes) {
                if (ch == '`') return true
                if (ch == '$' && next == '(') return true
                if (!inDoubleQuotes && (ch == '<' || ch == '>') && next == '(') return true
            }
            i++
        }

        return false
    }

    private fun isHardDenied(fullCommand: String): Boolean {
        val parts = extractCommandParts(fullCommand)
        if (parts.isEmpty()) return false

        for (part in parts) {
            if (isPrefixHardDenied(part)) return true
            if (isStandaloneHardDenied(part)) return true
        }
        return false
    }

    private fun extractCommandParts(command: String): List<String> {
        // Best-effort split (not a full shell parser).
        return command
            .split(Regex("""\s*(?:;|\|\||&&|\|)\s*|\r?\n+"""))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    private fun isPrefixHardDenied(commandPart: String): Boolean =
        config.hard_deny_prefixes.any { prefix -> commandPart.startsWith(prefix, ignoreCase = true) }

    private fun isStandaloneHardDenied(commandPart: String): Boolean {
        val trimmed = commandPart.trim()
        if (trimmed.isEmpty()) return false

        val tokens = trimmed.split(Regex("""\s+"""), limit = 2)
        if (tokens.size > 1) return false

        val cmd = tokens[0]
        val base = cmd.substringAfterLast('/').lowercase()
        val baseNoExt = base.substringBeforeLast('.', base).lowercase()

        return (cmd.lowercase() in config.hard_deny_standalone) ||
            (base in config.hard_deny_standalone) ||
            (baseNoExt in config.hard_deny_standalone)
    }

    private fun baseEnv(): Map<String, String> {
        val env = HashMap(System.getenv())

        env["CI"] = "true"
        env["NONINTERACTIVE"] = "1"
        env["NO_TTY"] = "1"
        env["NO_COLOR"] = "1"
        env["TERM"] = "dumb"
        env["DEBIAN_FRONTEND"] = "noninteractive"
        env["GIT_PAGER"] = "cat"
        env["PAGER"] = "cat"

        return env
    }

    private fun String.quoteForMsg(): String = "'" + this.replace("'", "\\'") + "'"

    private object Defaults {
        fun defaultHardDenyPrefixes(): List<String> =
            listOf("gdb", "pdb", "passwd", "nano", "vim", "vi", "emacs", "bash -i", "sh -i", "zsh -i", "fish -i", "dash -i", "screen", "tmux")

        fun defaultHardDenyStandalone(): Set<String> =
            setOf("python", "python3", "ipython", "bash", "sh", "nohup", "vi", "vim", "emacs", "nano", "su")
    }
}
