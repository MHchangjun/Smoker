package com.song.agent.tool

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription
import com.song.agent.tool.GrepTool.Defaults.defaultExcludePatterns
import kotlinx.serialization.Serializable
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

class GrepTool(
    private val config: Config = Config()
) : Tool<GrepTool.Args, GrepTool.Result>(
    argsSerializer = Args.serializer(),
    resultSerializer = Result.serializer(),
    name = "grep",
    description = "A powerful search tool built on ripgrep\\n\\n  Usage:\\n  - ALWAYS use Grep for search tasks. NEVER invoke `grep` or `rg` as a Bash command. The Grep tool has been optimized for correct permissions and access.\\n  - Supports full regex syntax (e.g., \"log.*Error\", \"function\\\\s+\\\\w+\")\\n  - Filter files with glob parameter (e.g., \"*.js\", \"**/*.tsx\")\\n  - Use Task tool for open-ended searches requiring multiple rounds\\n  - Pattern syntax: Uses ripgrep (not grep) - special regex characters need escaping (use `interface\\\\{\\\\}` to find `interface{}` in Go code)\\n',"
) {

    data class Config(
        val workDir: File = File(System.getProperty("user.dir")),
        val max_output_bytes: Int = 64_000,
        val default_max_matches: Int = 100,
        val default_timeout: Long = 60,
        val exclude_patterns: List<String> = defaultExcludePatterns(),
    ) {
        init {
            require(max_output_bytes > 0) { "max_output_bytes must be > 0" }
            require(default_max_matches > 0) { "default_max_matches must be > 0" }
            require(default_timeout > 0) { "default_timeout must be > 0" }
            require(workDir.isDirectory) { "workDir must be a directory: ${workDir.absolutePath}" }
        }
    }

    @Serializable
    data class Args(
        @property:LLMDescription("The regular expression pattern to search for in file contents")
        val pattern: String,
        @property:LLMDescription("File or directory to search in (rg PATH). Defaults to current working directory.")
        val path: String = ".",
        @property:LLMDescription("Glob pattern to filter files (e.g. \"*.js\", \"*.{ts,tsx}\") - maps to rg --glob")
        val glob: String? = null,
        @property:LLMDescription("Limit output to first N lines/entries. Optional - shows all matches if not specified.")
        val limit: Int? = null
    )

    @Serializable
    data class Result(
        val matches: String,
        val match_count: Int,
        val was_truncated: Boolean,
    )

    override suspend fun execute(args: Args): Result {
        val pattern = args.pattern
        if (pattern.isBlank()) throw ToolExecutionException("pattern must not be blank")

        val maxMatches = (args.limit ?: config.default_max_matches).coerceAtLeast(1)

        // Security: do not allow searching outside workDir.
        val resolvedTarget = resolvePathInsideWorkspace(args.path)
        if (!resolvedTarget.exists()) {
            throw ToolExecutionException("Path does not exist: ${args.path}")
        }

        val excludePatterns = config.exclude_patterns

        val glob = args.glob?.trim()?.takeIf { it.isNotEmpty() }
        val cmd = buildRipgrepCommand(args, excludePatterns, maxMatches, glob)

        val output = JvmProcessRunner.run(
            command = cmd,
            workDir = config.workDir,
            env = System.getenv(),
            timeoutSeconds = config.default_timeout,
            maxOutputBytes = config.max_output_bytes,
            decodeMode = JvmProcessRunner.DecodeMode.IGNORE,
        )

        // 0: matches found, 1: no matches. Anything else is an error.
        if (output.exitCode !in setOf(0, 1)) {
            val errorMsg = output.stderr.trim().ifEmpty { "Process exited with code ${output.exitCode}" }
            throw ToolExecutionException("grep error: $errorMsg")
        }

        return parseOutput(output.stdout, maxMatches)
    }

    private fun buildRipgrepCommand(
        args: Args,
        excludePatterns: List<String>,
        maxMatches: Int,
        glob: String?,
    ): List<String> {
        val cmd = mutableListOf(
            "rg",
            "--line-number",
            "--no-heading",
            "--smart-case",
            "--no-binary",
            "--max-count",
            (maxMatches + 1).toString(), // request +1 to detect truncation
        )

        for (pattern in excludePatterns) {
            cmd += listOf("--glob", "!$pattern")
        }

        if (glob != null) {
            cmd += listOf("--glob", glob)
        }

        cmd += listOf("-e", args.pattern, args.path)
        return cmd
    }

    private fun parseOutput(stdout: String, maxMatches: Int): Result {
        val lines = stdout.split('\n').filter { it.isNotEmpty() }

        val truncatedLines = lines.take(maxMatches)
        val truncatedOutput = truncatedLines.joinToString("\n")

        val finalOutput = truncateUtf8ToBytes(truncatedOutput, config.max_output_bytes)

        val wasTruncated =
            (lines.size > maxMatches) ||
                (truncatedOutput.toByteArray(Charsets.UTF_8).size > config.max_output_bytes)

        return Result(
            matches = finalOutput,
            match_count = truncatedLines.size,
            was_truncated = wasTruncated,
        )
    }

    private fun truncateUtf8ToBytes(text: String, maxBytes: Int): String {
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size <= maxBytes) return text
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.IGNORE)
            .onUnmappableCharacter(CodingErrorAction.IGNORE)
        return decoder.decode(ByteBuffer.wrap(bytes, 0, maxBytes)).toString()
    }

    private fun resolvePathInsideWorkspace(relativeOrAbsolute: String): File {
        val root = config.workDir.canonicalFile
        val target = File(relativeOrAbsolute).let { f ->
            if (f.isAbsolute) f else File(root, relativeOrAbsolute)
        }.canonicalFile

        if (!target.path.startsWith(root.path + File.separator) && target != root) {
            throw ToolExecutionException("Security error: path is outside workspace: $relativeOrAbsolute")
        }
        return target
    }

    private object Defaults {
        fun defaultExcludePatterns(): List<String> = listOf(
            ".venv/",
            "venv/",
            ".env/",
            "env/",
            "node_modules/",
            ".git/",
            "__pycache__/",
            ".pytest_cache/",
            ".mypy_cache/",
            ".tox/",
            ".nox/",
            ".coverage/",
            "htmlcov/",
            "dist/",
            "build/",
            ".idea/",
            ".vscode/",
            "*.egg-info",
            "*.pyc",
            "*.pyo",
            "*.pyd",
            ".DS_Store",
            "Thumbs.db",
        )
    }
}
