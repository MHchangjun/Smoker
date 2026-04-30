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
    name = ToolNames.GREP,
    description = """
A powerful search tool built on ripgrep

Usage:
- ALWAYS use Grep for search tasks. NEVER invoke `grep` or `rg` as a Bash command. The Grep tool has been optimized for correct permissions and access.
- Supports full regex syntax (e.g., "log.*Error", "function\s+\w+")
- Filter files with glob parameter (e.g., "*.js", "**/*.tsx") — pass the raw pattern (no surrounding quotes).
- Pattern syntax: Uses ripgrep (not grep) - special regex characters need escaping (use `interface\{\}` to find `interface{}` in Go code)
""".trimIndent()
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
        val path: String? = null,
        @property:LLMDescription("Glob pattern to filter files (e.g. \"*.js\", \"*.{ts,tsx}\") - maps to rg --glob")
        val glob: String? = null,
        @property:LLMDescription("Limit output to first N lines/entries. Optional — shows all matches up to the configured cap if omitted.")
        val limit: Int? = null
    )

    @Serializable
    data class Result(
        val summary: String,
        val matches: String,
        val match_count: Int,
        val was_truncated: Boolean,
    )

    override suspend fun execute(args: Args): Result {
        val pattern = args.pattern
        if (pattern.isBlank()) throw ToolExecutionException("pattern must not be blank")

        try {
            Regex(pattern)
        } catch (e: Exception) {
            throw ToolExecutionException("Invalid regular expression pattern: $pattern. Error: ${e.message}")
        }

        val maxMatches = (args.limit ?: config.default_max_matches).coerceAtLeast(1)

        val rawPath = normalizeSearchPath(args.path) ?: "."
        val resolvedTarget = resolvePathInsideWorkspace(rawPath)
        if (!resolvedTarget.exists()) {
            throw ToolExecutionException("Path does not exist: $rawPath")
        }

        val glob = args.glob?.let { stripWrappingQuotes(it.trim()) }?.takeIf { it.isNotEmpty() }
        val cmd = buildRipgrepCommand(pattern, rawPath, config.exclude_patterns, glob)

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

        return parseOutput(output.stdout, maxMatches, pattern, args.path?.let { rawPath }, glob)
    }

    private fun buildRipgrepCommand(
        pattern: String,
        path: String,
        excludePatterns: List<String>,
        glob: String?,
    ): List<String> {
        val cmd = mutableListOf(
            "rg",
            "--line-number",
            "--no-heading",
            "--with-filename",
            "--smart-case",
            "--no-binary",
            "--threads",
            "4",
        )

        for (excluded in excludePatterns) {
            cmd += listOf("--glob", "!$excluded")
        }

        if (glob != null) {
            cmd += listOf("--glob", glob)
        }

        cmd += listOf("-e", pattern, path)
        return cmd
    }

    private fun parseOutput(
        stdout: String,
        maxMatches: Int,
        pattern: String,
        rawPath: String?,
        glob: String?,
    ): Result {
        val lines = stdout.split('\n').filter { it.isNotEmpty() }

        val location = if (rawPath != null) "in path \"$rawPath\"" else "in the workspace directory"
        val filterDesc = if (glob != null) " (filter: \"$glob\")" else ""

        if (lines.isEmpty()) {
            return Result(
                summary = "No matches found for pattern \"$pattern\" $location$filterDesc.",
                matches = "",
                match_count = 0,
                was_truncated = false,
            )
        }

        val truncatedLines = lines.take(maxMatches)
        val truncatedOutput = truncatedLines.joinToString("\n")
        val finalOutput = truncateUtf8ToBytes(truncatedOutput, config.max_output_bytes)

        val wasTruncated =
            (lines.size > maxMatches) ||
                (truncatedOutput.toByteArray(Charsets.UTF_8).size > config.max_output_bytes)

        val matchTerm = if (lines.size == 1) "match" else "matches"
        val truncatedNote = if (wasTruncated) " (truncated)" else ""
        val summary = "Found ${lines.size} $matchTerm for pattern \"$pattern\" $location$filterDesc$truncatedNote"

        return Result(
            summary = summary,
            matches = finalOutput,
            match_count = truncatedLines.size,
            was_truncated = wasTruncated,
        )
    }

    private fun stripWrappingQuotes(raw: String): String {
        if (raw.length < 2) return raw
        val first = raw.first()
        val last = raw.last()
        return if ((first == '"' || first == '\'') && first == last) raw.substring(1, raw.length - 1) else raw
    }

    private fun normalizeSearchPath(rawPath: String?): String? {
        val trimmed = rawPath?.trim() ?: return null
        val normalized = stripWrappingQuotes(trimmed).trim()
        return normalized
            .takeIf { it.isNotEmpty() }
            ?.takeUnless { it.equals("null", ignoreCase = true) || it.equals("undefined", ignoreCase = true) }
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
