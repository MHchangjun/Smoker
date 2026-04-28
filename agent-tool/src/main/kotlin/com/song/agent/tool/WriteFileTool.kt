package com.song.agent.tool

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.song.agent.tool.diagnostics.runPostEditDiagnostics
import kotlinx.serialization.Serializable
import java.io.File

class WriteFileTool(
    private val project: Project,
    private val config: Config = Config(),
) : Tool<WriteFileTool.Args, WriteFileTool.Result>(
    argsSerializer = Args.serializer(),
    resultSerializer = Result.serializer(),
    name = ToolNames.WRITE_FILE,
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
        val diagnostics: String? = null,
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

        val writeResult = applyWrite(resolved, args.content, fileExisted)
        if (writeResult.error != null) {
            throw ToolExecutionException("Error writing ${args.path}: ${writeResult.error}")
        }

        val diagnostics = writeResult.vFile
            ?.let { runPostEditDiagnostics(project, it) }
            ?.takeIf { it.isNotEmpty() }

        return Result(
            path = args.path,
            bytes_written = contentBytes,
            file_existed = fileExisted,
            content = args.content,
            diagnostics = diagnostics,
        )
    }

    private data class WriteResult(val vFile: VirtualFile? = null, val error: String? = null)

    // Same Option A pattern as EditTool: disk write on background, VFS refresh
    // on EDT inside WriteAction.
    private fun applyWrite(target: File, content: String, fileExisted: Boolean): WriteResult {
        try {
            target.parentFile?.takeIf { !it.exists() }?.mkdirs()
            target.writeText(content, Charsets.UTF_8)
        } catch (e: Throwable) {
            return WriteResult(error = "Disk write failed for ${target.absolutePath}: ${e.message}")
        }

        var vFile: VirtualFile? = null
        var refreshError: String? = null
        ApplicationManager.getApplication().invokeAndWait {
            try {
                WriteAction.run<Throwable> {
                    val refreshed = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(target)
                    refreshed?.refresh(false, false)
                    vFile = refreshed
                }
            } catch (e: Throwable) {
                refreshError = "VFS refresh failed for ${target.absolutePath}: ${e.message}"
            }
        }
        return WriteResult(vFile = vFile, error = refreshError)
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
