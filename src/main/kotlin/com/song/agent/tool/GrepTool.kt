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
    description = "Recursively search files for a regex pattern using ripgrep (rg) or grep. Respects .gitignore and .codeignore files by default when using ripgrep.",
) {

    data class Config(
        val workDir: File = File(System.getProperty("user.dir")),
        val max_output_bytes: Int = 64_000,
        val default_max_matches: Int = 100,
        val default_timeout: Long = 60,
        val exclude_patterns: List<String> = defaultExcludePatterns(),
        val codeignore_file: String = ".vibeignore",
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
        @property:LLMDescription("Regular expression to search for.")
        val pattern: String,
        @property:LLMDescription("Directory or file path to search within (relative to workspace).")
        val path: String = ".",
        @property:LLMDescription("Override the default maximum number of matches.")
        val max_matches: Int? = null,
        @property:LLMDescription("Whether to respect .gitignore and .ignore files.")
        val use_default_ignore: Boolean = true,
    )

    @Serializable
    data class Result(
        val matches: String,
        val match_count: Int,
        val was_truncated: Boolean,
    )

    private enum class Backend { RIPGREP, GNU_GREP }

    override suspend fun execute(args: Args): Result {
        val pattern = args.pattern
        if (pattern.isBlank()) throw ToolExecutionException("pattern must not be blank")

        val maxMatches = (args.max_matches ?: config.default_max_matches).coerceAtLeast(1)

        // Security: do not allow searching outside workDir.
        val resolvedTarget = resolvePathInsideWorkspace(args.path)
        if (!resolvedTarget.exists()) {
            throw ToolExecutionException("Path does not exist: ${args.path}")
        }

        val excludePatterns = buildExcludePatterns()

        val backend = detectBackend()
            ?: throw ToolExecutionException("No grep backend found. Install ripgrep (rg) or GNU grep (grep).")

        val cmd = when (backend) {
            Backend.RIPGREP -> buildRipgrepCommand(args, excludePatterns, maxMatches)
            Backend.GNU_GREP -> buildGnuGrepCommand(args, excludePatterns, maxMatches)
        }

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

    private fun detectBackend(): Backend? {
        // Prefer ripgrep if present.
        if (ExecutableFinder.exists("rg")) return Backend.RIPGREP
        if (ExecutableFinder.exists("grep")) return Backend.GNU_GREP
        return null
    }

    private fun buildExcludePatterns(): List<String> {
        val patterns = config.exclude_patterns.toMutableList()
        val ignoreFile = File(config.workDir, config.codeignore_file)
        if (ignoreFile.isFile) {
            ignoreFile.readLines(Charsets.UTF_8)
                .map { it.trim() }
                .filter { it.isNotBlank() && !it.startsWith("#") }
                .forEach { patterns += it }
        }
        return patterns
    }

    private fun buildRipgrepCommand(args: Args, excludePatterns: List<String>, maxMatches: Int): List<String> {
        val cmd = mutableListOf(
            "rg",
            "--line-number",
            "--no-heading",
            "--smart-case",
            "--no-binary",
            "--max-count",
            (maxMatches + 1).toString(), // request +1 to detect truncation
        )

        if (!args.use_default_ignore) {
            cmd += "--no-ignore"
        }

        for (pattern in excludePatterns) {
            cmd += listOf("--glob", "!$pattern")
        }

        cmd += listOf("-e", args.pattern, args.path)
        return cmd
    }

    private fun buildGnuGrepCommand(args: Args, excludePatterns: List<String>, maxMatches: Int): List<String> {
        val cmd = mutableListOf(
            "grep",
            "-r",
            "-n",
            "-I",
            "-E",
            "--max-count=${maxMatches + 1}", // request +1 to detect truncation
        )

        // Smart-case-ish: if the pattern is all lowercase, do case-insensitive search.
        if (args.pattern == args.pattern.lowercase()) {
            cmd += "-i"
        }

        for (pattern in excludePatterns) {
            if (pattern.endsWith("/")) {
                val dirPattern = pattern.removeSuffix("/")
                cmd += "--exclude-dir=$dirPattern"
            } else {
                cmd += "--exclude=$pattern"
            }
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
