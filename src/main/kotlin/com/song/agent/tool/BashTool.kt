package com.song.agent.tool

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription
import com.song.agent.tool.BashTool.Defaults.defaultAllowlist
import com.song.agent.tool.BashTool.Defaults.defaultDenylist
import com.song.agent.tool.BashTool.Defaults.defaultDenylistStandalone
import kotlinx.serialization.Serializable
import java.io.File

/**
 * Koog migration of the original "bash" tool.
 *
 * - Tool name: bash
 * - Args: { command, timeout? }
 * - Result: { stdout, stderr, returncode }
 *
 * The tool description is loaded from bash.md (resources) to keep the prompt unchanged.
 */
class BashTool(
    private val config: Config = Config()
) : Tool<BashTool.Args, BashTool.Result>(
    argsSerializer = Args.serializer(),
    resultSerializer = Result.serializer(),
    name = "bash",
    description = "Run a one-off bash command and capture its output."
) {

    data class Config(
        val workDir: File = File(System.getProperty("user.dir")),
        val max_output_bytes: Int = 16_000,
        val default_timeout: Long = 30,
        val allowlist: List<String> = defaultAllowlist(),
        val denylist: List<String> = defaultDenylist(),
        val denylist_standalone: Set<String> = defaultDenylistStandalone(),
        val confirmationHandler: ShellCommandConfirmationHandler = defaultConfirmationHandlerFromEnv(),
    ) {
        init {
            require(max_output_bytes > 0) { "max_output_bytes must be > 0" }
            require(default_timeout > 0) { "default_timeout must be > 0" }
            require(workDir.isDirectory) { "workDir must be a directory: ${workDir.absolutePath}" }
        }
    }

    @Serializable
    data class Args(
        @property:LLMDescription("The bash command to execute.")
        val command: String,
        @property:LLMDescription("Override the default command timeout.")
        val timeout: Int? = null,
    )

    @Serializable
    data class Result(
        val stdout: String,
        val stderr: String,
        val returncode: Int,
    )

    private enum class CommandPolicy { ALLOW, DENY, ASK }

    override suspend fun execute(args: Args): Result {
        val command = args.command.trim()
        if (command.isEmpty()) {
            throw ToolExecutionException("Empty command provided.")
        }

        val timeoutSeconds = (args.timeout?.toLong() ?: config.default_timeout).coerceAtLeast(1)

        when (classify(command)) {
            CommandPolicy.DENY -> throw ToolExecutionException("Command is denied: ${command.quoteForMsg()}")
            CommandPolicy.ALLOW -> Unit
            CommandPolicy.ASK -> {
                when (val decision = config.confirmationHandler.confirm(command, timeoutSeconds)) {
                    ShellCommandConfirmation.Approved -> Unit
                    is ShellCommandConfirmation.Denied ->
                        throw ToolExecutionException("Command execution denied: ${decision.reason}")
                }
            }
        }

        val output = JvmProcessRunner.runShell(
            command = command,
            workDir = config.workDir,
            env = baseEnv(),
            timeoutSeconds = timeoutSeconds,
            maxOutputBytes = config.max_output_bytes,
            decodeMode = JvmProcessRunner.DecodeMode.REPLACE,
        )

        if (output.exitCode != 0) {
            // Mirror the original behavior: non-zero exit means tool failure.
            val msg = buildString {
                append("Command failed: ").append(command.quoteForMsg()).append('\n')
                append("Return code: ").append(output.exitCode)
                if (output.stderr.isNotBlank()) {
                    append("\nStderr:\n").append(output.stderr.trimEnd())
                }
                if (output.stdout.isNotBlank()) {
                    append("\nStdout:\n").append(output.stdout.trimEnd())
                }
            }
            throw ToolExecutionException(msg)
        }

        return Result(
            stdout = output.stdout,
            stderr = output.stderr,
            returncode = output.exitCode,
        )
    }

    private fun classify(fullCommand: String): CommandPolicy {
        val parts = extractCommandParts(fullCommand)
        if (parts.isEmpty()) return CommandPolicy.ASK

        // Deny if ANY segment is denylisted.
        for (part in parts) {
            if (isPrefixDenied(part)) return CommandPolicy.DENY
            if (isStandaloneDenied(part)) return CommandPolicy.DENY
        }

        // Allow if ALL segments are allowlisted.
        if (parts.all { isPrefixAllowlisted(it) }) return CommandPolicy.ALLOW

        return CommandPolicy.ASK
    }

    private fun extractCommandParts(command: String): List<String> {
        // Best-effort split (not a full shell parser).
        return command
            .split(Regex("""\s*(?:;|\|\||&&|\|)\s*|\r?\n+"""))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    private fun isPrefixAllowlisted(commandPart: String): Boolean =
        config.allowlist.any { prefix -> commandPart.startsWith(prefix) }

    private fun isPrefixDenied(commandPart: String): Boolean =
        config.denylist.any { prefix -> commandPart.startsWith(prefix) }

    private fun isStandaloneDenied(commandPart: String): Boolean {
        val trimmed = commandPart.trim()
        if (trimmed.isEmpty()) return false

        val tokens = trimmed.split(Regex("""\s+"""), limit = 2)
        if (tokens.size > 1) return false // only deny when run without arguments

        val cmd = tokens[0]
        val base = cmd.substringAfterLast('/')
        val baseNoExt = base.substringBeforeLast('.', base)

        return (cmd in config.denylist_standalone) ||
            (base in config.denylist_standalone) ||
            (baseNoExt in config.denylist_standalone)
    }

    private fun baseEnv(): Map<String, String> {
        val isWin = System.getProperty("os.name").lowercase().contains("win")
        val env = HashMap(System.getenv())

        env["CI"] = "true"
        env["NONINTERACTIVE"] = "1"
        env["NO_TTY"] = "1"
        env["NO_COLOR"] = "1"

        if (!isWin) {
            env["TERM"] = "dumb"
            env["DEBIAN_FRONTEND"] = "noninteractive"
        }

        env["GIT_PAGER"] = if (isWin) "more" else "cat"
        env["PAGER"] = if (isWin) "more" else "cat"

        return env
    }

    private fun String.quoteForMsg(): String = "'" + this.replace("'", "\\'") + "'"

    private object Defaults {
        fun defaultAllowlist(): List<String> {
            val common = listOf("echo", "find", "git diff", "git log", "git status", "tree", "whoami")
            return if (System.getProperty("os.name").lowercase().contains("win")) {
                common + listOf("dir", "findstr", "more", "type", "ver", "where")
            } else {
                common + listOf(
                    "cat",
                    "file",
                    "head",
                    "ls",
                    "pwd",
                    "stat",
                    "tail",
                    "uname",
                    "wc",
                    "which",
                )
            }
        }

        fun defaultDenylist(): List<String> {
            val common = listOf("gdb", "pdb", "passwd")
            return if (System.getProperty("os.name").lowercase().contains("win")) {
                common + listOf("cmd /k", "powershell -NoExit", "pwsh -NoExit", "notepad")
            } else {
                common + listOf(
                    "nano",
                    "vim",
                    "vi",
                    "emacs",
                    "bash -i",
                    "sh -i",
                    "zsh -i",
                    "fish -i",
                    "dash -i",
                    "screen",
                    "tmux",
                )
            }
        }

        fun defaultDenylistStandalone(): Set<String> {
            val common = setOf("python", "python3", "ipython")
            return if (System.getProperty("os.name").lowercase().contains("win")) {
                common + setOf("cmd", "powershell", "pwsh", "notepad")
            } else {
                common + setOf("bash", "sh", "nohup", "vi", "vim", "emacs", "nano", "su")
            }
        }
    }
}
