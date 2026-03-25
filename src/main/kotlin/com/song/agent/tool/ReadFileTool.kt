package com.song.agent.tool

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames
import java.io.File

class ReadFileTool(
    private val config: Config = Config()
) : Tool<ReadFileTool.Args, ReadFileTool.Result>(
    argsSerializer = Args.serializer(),
    resultSerializer = Result.serializer(),
    name = ToolNames.READ_FILE,
    description = "Reads and returns the content of a specified file. If the file is large, the content will be truncated. The tool's response will clearly indicate if truncation has occurred and will provide details on how to read more of the file using the 'offset' and 'limit' parameters. Handles text, images (PNG, JPG, GIF, WEBP, SVG, BMP), and PDF files. For text files, it can read specific line ranges."
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

    @OptIn(ExperimentalSerializationApi::class)
    @Serializable
    data class Args(
        @property:LLMDescription("The absolute path to the file to read (e.g., '/home/user/project/file.txt'). Relative paths are not supported. You must provide an absolute path.")
        @JsonNames("path")
        val absolute_path: String,
        @property:LLMDescription("Optional: For text files, the 0-based line number to start reading from. Requires 'limit' to be set. Use for paginating through large files.")
        val offset: Int? = null,
        @property:LLMDescription("Optional: For text files, maximum number of lines to read. Use with 'offset' to paginate through large files. If omitted, reads the entire file (if feasible, up to a default limit).")
        val limit: Int? = null,
    )

    @Serializable
    data class Result(
        val absolute_path: String,
        val content: String,
        val lines_read: Int,
        val was_truncated: Boolean,
        val lines_shown_start: Int? = null,
        val lines_shown_end: Int? = null,
        val total_lines: Int? = null,
    )

    override suspend fun execute(args: Args): Result {
        validateInputs(args)

        val resolved = resolvePathInsideWorkspace(args.absolute_path)
        val offset = args.offset ?: 0

        if (!resolved.exists()) {
            throw ToolExecutionException("File not found: ${args.absolute_path}")
        }
        if (!resolved.isFile) {
            throw ToolExecutionException("Not a file: ${args.absolute_path}")
        }

        val content = readFile(
            file = resolved,
            offset = offset,
            limit = args.limit,
            maxBytes = config.max_read_bytes,
        )

        val finalContent = if (content.wasTruncated) {
            val start = content.linesShownStart
            val end = content.linesShownEnd
            val total = content.totalLineCount
            if (start != null && end != null && total != null) {
                "Showing lines $start-$end of $total total lines.\n\n---\n\n${content.content}"
            } else {
                "File content was truncated due to the read byte limit (${config.max_read_bytes} bytes).\n\n---\n\n${content.content}"
            }
        } else {
            content.content
        }

        return Result(
            absolute_path = args.absolute_path,
            content = finalContent,
            lines_read = content.linesRead,
            was_truncated = content.wasTruncated,
            lines_shown_start = content.linesShownStart,
            lines_shown_end = content.linesShownEnd,
            total_lines = content.totalLineCount,
        )
    }

    private fun validateInputs(args: Args) {
        if (args.absolute_path.isBlank()) {
            throw ToolExecutionException("The 'absolute_path' parameter must be non-empty.")
        }
        if (!File(args.absolute_path).isAbsolute) {
            throw ToolExecutionException(
                "File path must be absolute, but was relative: ${args.absolute_path}. You must provide an absolute path."
            )
        }
        if (args.offset != null && args.offset < 0) {
            throw ToolExecutionException("offset must be >= 0")
        }
        if (args.offset != null && args.limit == null) {
            throw ToolExecutionException("The 'offset' parameter requires 'limit' to be set.")
        }
        if (args.limit != null && args.limit <= 0) throw ToolExecutionException("limit must be > 0 when provided")
    }

    private fun resolvePathInsideWorkspace(path: String): File {
        val root = config.workDir.canonicalFile
        val target = File(path).canonicalFile

        if (!target.path.startsWith(root.path + File.separator) && target != root) {
            throw ToolExecutionException("Security error: Cannot read outside workspace: $path")
        }
        return target
    }

    private data class ReadResult(
        val content: String,
        val linesRead: Int,
        val wasTruncated: Boolean,
        val linesShownStart: Int?,
        val linesShownEnd: Int?,
        val totalLineCount: Int?,
    )

    private fun readFile(file: File, offset: Int, limit: Int?, maxBytes: Int): ReadResult {
        var bytesRead = 0
        var totalLinesSeen = 0
        var linesRead = 0
        var wasTruncated = false
        var linesShownStart: Int? = null
        var linesShownEnd: Int? = null
        var totalLineCount: Int? = null

        val sb = StringBuilder()

        file.bufferedReader(Charsets.UTF_8).use { reader ->
            while (true) {
                val line = reader.readLine() ?: break // returns without newline
                val currentLineIndex = totalLinesSeen
                totalLinesSeen++

                if (currentLineIndex < offset) {
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
                    while (reader.readLine() != null) {
                        totalLinesSeen++
                    }
                    totalLineCount = totalLinesSeen
                    break
                }

                if (linesShownStart == null) {
                    linesShownStart = currentLineIndex + 1
                }
                linesShownEnd = currentLineIndex + 1
                sb.append(normalizedLine)
                bytesRead += lineBytes
                linesRead++
            }
        }

        return ReadResult(
            content = sb.toString(),
            linesRead = linesRead,
            wasTruncated = wasTruncated,
            linesShownStart = linesShownStart,
            linesShownEnd = linesShownEnd,
            totalLineCount = totalLineCount,
        )
    }
}
