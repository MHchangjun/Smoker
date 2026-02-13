package com.song.agent.tool

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable
import java.io.File

class WriteFileTool(
    private val config: Config = Config()
) : Tool<WriteFileTool.Args, WriteFileTool.Result>(
    argsSerializer = Args.serializer(),
    resultSerializer = Result.serializer(),
    name = "write_file",
    description = "Create or overwrite a UTF-8 file. Fails if file exists unless 'overwrite=True'."
) {

    data class Config(
        val workDir: File = File(System.getProperty("user.dir")),
        val max_write_bytes: Int = 64_000,
        val create_parent_dirs: Boolean = true,
    ) {
        init {
            require(max_write_bytes > 0) { "max_write_bytes must be > 0" }
            require(workDir.isDirectory) { "workDir must be a directory: ${workDir.absolutePath}" }
        }
    }

    @Serializable
    data class Args(
        @property:LLMDescription("Path to the UTF-8 file to write (relative to workspace).")
        val path: String,
        @property:LLMDescription("UTF-8 content to write to the file.")
        val content: String,
        @property:LLMDescription("Must be set to true to overwrite an existing file.")
        val overwrite: Boolean = false,
    )

    @Serializable
    data class Result(
        val path: String,
        val bytes_written: Int,
        val file_existed: Boolean,
        val content: String,
    )

    override suspend fun execute(args: Args): Result {
        validateInputs(args)

        val resolved = resolvePathInsideWorkspace(args.path)
        val contentBytes = args.content.toByteArray(Charsets.UTF_8).size

        if (contentBytes > config.max_write_bytes) {
            throw ToolExecutionException("Content exceeds ${config.max_write_bytes} bytes limit")
        }

        val fileExisted = resolved.exists()
        if (fileExisted && !args.overwrite) {
            throw ToolExecutionException("File '${args.path}' exists. Set overwrite=true to replace.")
        }

        val parent = resolved.parentFile
        if (parent != null) {
            if (config.create_parent_dirs) {
                if (!parent.exists() && !parent.mkdirs()) {
                    throw ToolExecutionException("Failed to create parent directory: ${parent.path}")
                }
            } else if (!parent.exists()) {
                throw ToolExecutionException("Parent directory does not exist: ${parent.path}")
            }
        }

        try {
            resolved.writeText(args.content, Charsets.UTF_8)
        } catch (e: Exception) {
            throw ToolExecutionException("Error writing ${args.path}: ${e.message}", e)
        }

        return Result(
            path = args.path,
            bytes_written = contentBytes,
            file_existed = fileExisted,
            content = args.content,
        )
    }

    private fun validateInputs(args: Args) {
        if (args.path.isBlank()) throw ToolExecutionException("path must not be empty")
    }

    private fun resolvePathInsideWorkspace(path: String): File {
        val root = config.workDir.canonicalFile
        val target = File(path).let { f ->
            if (f.isAbsolute) f else File(root, path)
        }.canonicalFile

        if (!target.path.startsWith(root.path + File.separator) && target != root) {
            throw ToolExecutionException("Security error: Cannot write outside workspace: $path")
        }
        return target
    }
}
