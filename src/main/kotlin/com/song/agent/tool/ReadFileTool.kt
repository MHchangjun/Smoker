package com.song.agent.tool

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable
import java.io.File

class ReadFileTool(
    private val config: Config = Config()
) : Tool<ReadFileTool.Args, ReadFileTool.Result>(
    argsSerializer = Args.serializer(),
    resultSerializer = Result.serializer(),
    name = "read_file",
    description = "Read a UTF-8 file, returning content from a specific line range. Reading is capped by a byte limit for safety."
) {

    data class Config(
        val workDir: File = File(System.getProperty("user.dir")),
        val max_read_bytes: Int = 64_000,
    ) {
        init {
            require(max_read_bytes > 0) { "max_read_bytes must be > 0" }
            require(workDir.isDirectory) { "workDir must be a directory: ${workDir.absolutePath}" }
        }
    }

    @Serializable
    data class Args(
        @property:LLMDescription("Path to the UTF-8 file to read (relative to workspace).")
        val path: String,
        @property:LLMDescription("Line number to start reading from (0-indexed, inclusive).")
        val offset: Int = 0,
        @property:LLMDescription("Maximum number of lines to read.")
        val limit: Int? = null,
    )

    @Serializable
    data class Result(
        val path: String,
        val content: String,
        val lines_read: Int,
        val was_truncated: Boolean,
    )

    override suspend fun execute(args: Args): Result {
        validateInputs(args)

        val resolved = resolvePathInsideWorkspace(args.path)

        if (!resolved.exists()) {
            throw ToolExecutionException("File not found: ${args.path}")
        }
        if (!resolved.isFile) {
            throw ToolExecutionException("Not a file: ${args.path}")
        }

        val (content, linesRead, wasTruncated) = readFile(
            file = resolved,
            offset = args.offset,
            limit = args.limit,
            maxBytes = config.max_read_bytes,
        )

        return Result(
            path = args.path,
            content = content,
            lines_read = linesRead,
            was_truncated = wasTruncated,
        )
    }

    private fun validateInputs(args: Args) {
        if (args.path.isBlank()) throw ToolExecutionException("path must not be empty")
        if (args.offset < 0) throw ToolExecutionException("offset must be >= 0")
        if (args.limit != null && args.limit <= 0) throw ToolExecutionException("limit must be > 0 when provided")
    }

    private fun resolvePathInsideWorkspace(path: String): File {
        val root = config.workDir.canonicalFile
        val target = File(path).let { f ->
            if (f.isAbsolute) f else File(root, path)
        }.canonicalFile

        if (!target.path.startsWith(root.path + File.separator) && target != root) {
            throw ToolExecutionException("Security error: Cannot read outside workspace: $path")
        }
        return target
    }

    private data class ReadResult(val content: String, val linesRead: Int, val wasTruncated: Boolean)

    private fun readFile(file: File, offset: Int, limit: Int?, maxBytes: Int): ReadResult {
        var bytesRead = 0
        var lineIndex = 0
        var linesRead = 0
        var wasTruncated = false

        val sb = StringBuilder()

        file.bufferedReader(Charsets.UTF_8).use { reader ->
            while (true) {
                val line = reader.readLine() ?: break // returns without newline

                if (lineIndex < offset) {
                    lineIndex++
                    continue
                }

                if (limit != null && linesRead >= limit) {
                    break
                }

                // Python's text mode normalizes newlines to '\n'. We emulate that by appending '\n'.
                val normalizedLine = line + "\n"
                val lineBytes = normalizedLine.toByteArray(Charsets.UTF_8).size

                if (bytesRead + lineBytes > maxBytes) {
                    wasTruncated = true
                    break
                }

                sb.append(normalizedLine)
                bytesRead += lineBytes
                linesRead++
                lineIndex++
            }
        }

        return ReadResult(
            content = sb.toString(),
            linesRead = linesRead,
            wasTruncated = wasTruncated,
        )
    }
}
