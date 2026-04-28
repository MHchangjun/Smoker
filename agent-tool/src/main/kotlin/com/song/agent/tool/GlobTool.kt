package com.song.agent.tool

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.text.Collator
import java.util.Locale

class GlobTool(
    private val config: Config = Config()
) : Tool<GlobTool.Args, GlobTool.Result>(
    argsSerializer = Args.serializer(),
    resultSerializer = Result.serializer(),
    name = ToolNames.GLOB,
    description = """
Fast file pattern matching tool that works with any codebase size
- Supports glob patterns like "**/*.js" or "src/**/*.ts"
- Returns matching file paths sorted by modification time
- Use this tool when you need to find files by name patterns
- When you are doing an open ended search that may require multiple rounds of globbing and grepping, use the Agent tool instead
- You have the capability to call multiple tools in a single response. It is always better to speculatively perform multiple searches as a batch that are potentially useful.
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
        @property:LLMDescription("The glob pattern to match files against.")
        val pattern: String,
        @property:LLMDescription("The directory to search in. If not specified, the current working directory will be used. IMPORTANT: Omit this field to use the default directory. DO NOT enter \"undefined\" or \"null\" - simply omit it for the default behavior. Must be a valid directory path if provided.")
        val path: String? = null,
    )

    @Serializable
    data class Result(
        val summary: String,
        val matches: String,
        val match_count: Int,
        val was_truncated: Boolean,
    )

    override suspend fun execute(args: Args): Result {
        val pattern = stripWrappingQuotes(args.pattern.trim())
        if (pattern.isEmpty()) throw ToolExecutionException("pattern must not be blank")
        if (args.path != null && args.path.isBlank()) throw ToolExecutionException("path must not be blank when provided")

        val maxMatches = config.default_max_matches
        val targetPath = args.path?.trim()?.takeIf { it.isNotEmpty() } ?: "."

        val resolvedTarget = resolvePathInsideWorkspace(targetPath)
        if (!resolvedTarget.exists()) {
            throw ToolExecutionException("Path does not exist: $targetPath")
        }

        val cmd = buildRipgrepCommand(pattern, targetPath, config.exclude_patterns)
        val output = JvmProcessRunner.run(
            command = cmd,
            workDir = config.workDir,
            env = System.getenv(),
            timeoutSeconds = config.default_timeout,
            maxOutputBytes = config.max_output_bytes,
            decodeMode = JvmProcessRunner.DecodeMode.IGNORE,
        )

        // 0: files matched, 1: no files matched.
        if (output.exitCode !in setOf(0, 1)) {
            val errorMsg = output.stderr.trim().ifEmpty { "Process exited with code ${output.exitCode}" }
            throw ToolExecutionException("glob error: $errorMsg")
        }

        return parseOutput(output.stdout, maxMatches, pattern, args.path)
    }

    private fun buildRipgrepCommand(pattern: String, path: String, excludePatterns: List<String>): List<String> {
        val cmd = mutableListOf(
            "rg",
            "--files",
            "--glob",
            pattern,
        )

        for (pattern in excludePatterns) {
            cmd += listOf("--glob", "!$pattern")
        }

        cmd += path
        return cmd
    }

    private fun parseOutput(stdout: String, maxMatches: Int, pattern: String, rawPath: String?): Result {
        val lines = stdout.split('\n').filter { it.isNotEmpty() }
        val location = if (rawPath != null) "in path \"$rawPath\"" else "in the workspace directory"

        if (lines.isEmpty()) {
            return Result(
                summary = "No files found matching pattern \"$pattern\" $location.",
                matches = "",
                match_count = 0,
                was_truncated = false,
            )
        }

        val sortedLines = sortMatchPaths(lines)
        val truncatedLines = sortedLines.take(maxMatches)
        val truncatedOutput = truncatedLines.joinToString("\n")
        val finalOutput = truncateUtf8ToBytes(truncatedOutput, config.max_output_bytes)

        val wasTruncated =
            (sortedLines.size > maxMatches) ||
                (truncatedOutput.toByteArray(Charsets.UTF_8).size > config.max_output_bytes)

        val fileTerm = if (sortedLines.size == 1) "file" else "files"
        val truncatedNote = if (wasTruncated) " (truncated)" else ""
        val summary = "Found ${sortedLines.size} $fileTerm matching \"$pattern\" $location, sorted by modification time (newest first)$truncatedNote"

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

    private fun truncateUtf8ToBytes(text: String, maxBytes: Int): String {
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size <= maxBytes) return text
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.IGNORE)
            .onUnmappableCharacter(CodingErrorAction.IGNORE)
        return decoder.decode(ByteBuffer.wrap(bytes, 0, maxBytes)).toString()
    }

    private fun sortMatchPaths(paths: List<String>): List<String> {
        val nowTimestamp = System.currentTimeMillis()
        val recencyThresholdMs = 24 * 60 * 60 * 1000L
        val mtimeCache = mutableMapOf<String, Long>()
        val collator = Collator.getInstance(Locale.ROOT)

        fun mtime(path: String): Long {
            return mtimeCache.getOrPut(path) {
                val file = resolveOutputPath(path)
                if (!file.exists()) 0L else file.lastModified()
            }
        }

        return paths.sortedWith { a, b ->
            val mtimeA = mtime(a)
            val mtimeB = mtime(b)
            val aIsRecent = nowTimestamp - mtimeA < recencyThresholdMs
            val bIsRecent = nowTimestamp - mtimeB < recencyThresholdMs

            when {
                aIsRecent && bIsRecent -> mtimeB.compareTo(mtimeA)
                aIsRecent -> -1
                bIsRecent -> 1
                else -> collator.compare(a, b)
            }
        }
    }

    private fun resolveOutputPath(outputPath: String): File {
        val raw = File(outputPath)
        val resolved = if (raw.isAbsolute) raw else File(config.workDir, outputPath)
        return runCatching { resolved.canonicalFile }.getOrDefault(resolved.absoluteFile)
    }

    private fun resolvePathInsideWorkspace(relativeOrAbsolute: String): File {
        val root = config.workDir.canonicalFile
        val target = File(relativeOrAbsolute).let { file ->
            if (file.isAbsolute) file else File(root, relativeOrAbsolute)
        }.canonicalFile

        if (!target.path.startsWith(root.path + File.separator) && target != root) {
            throw ToolExecutionException("Security error: path is outside workspace: $relativeOrAbsolute")
        }
        return target
    }

    private companion object {
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
