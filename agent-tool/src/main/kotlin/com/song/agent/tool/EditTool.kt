package com.song.agent.tool

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.song.agent.tool.diagnostics.runPostEditDiagnostics
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import java.io.File

class EditTool(
    private val project: Project,
    private val config: Config = Config(),
) : Tool<EditTool.Args, String>(
    argsSerializer = Args.serializer(),
    resultSerializer = String.serializer(),
    name = ToolNames.EDIT,
    description = """
Replaces text within a file. By default, replaces a single occurrence. Set \`replace_all\` to true when you intend to modify every instance of \`old_string\`. This tool requires providing significant context around the change to ensure precise targeting. Always use the read_file tool to examine the file's current content before attempting a text replacement.

The user has the ability to modify the \`new_string\` content. If modified, this will be stated in the response.

Expectation for required parameters:
1. \`file_path\` MUST be an absolute path; otherwise an error will be thrown.
2. \`old_string\` MUST be the exact literal text to replace (including all whitespace, indentation, newlines, and surrounding code etc.).
3. \`new_string\` MUST be the exact literal text to replace \`old_string\` with (also including all whitespace, indentation, newlines, and surrounding code etc.). Ensure the resulting code is correct and idiomatic.
4. NEVER escape \`old_string\` or \`new_string\`, that would break the exact literal text requirement.
**Important:** If ANY of the above are not satisfied, the tool will fail. CRITICAL for \`old_string\`: Must uniquely identify the single instance to change. Include at least 3 lines of context BEFORE and AFTER the target text, matching whitespace and indentation precisely. If this string matches multiple locations, or does not match exactly, the tool will fail.
**Multiple replacements:** Set \`replace_all\` to true when you want to replace every occurrence that matches \`old_string\`.
""".trimIndent()
) {

    data class Config(
        val workDir: File = File(System.getProperty("user.dir")),
        val createParentDirs: Boolean = true,
    ) {
        init {
            require(workDir.isDirectory) { "workDir must be a directory: ${workDir.absolutePath}" }
        }
    }

    @Serializable
    data class Args(
        @SerialName("file_path")
        @property:LLMDescription("The absolute path to the file to modify. Must start with '/'.")
        val filePath: String,
        @SerialName("old_string")
        @property:LLMDescription("The exact literal text to replace, preferably unescaped. For single replacements (default), include at least 3 lines of context BEFORE and AFTER the target text, matching whitespace and indentation precisely. If this string is not the exact literal text (i.e. you escaped it) or does not match exactly, the tool will fail.")
        val oldString: String,
        @SerialName("new_string")
        @property:LLMDescription("The exact literal text to replace `old_string` with, preferably unescaped. Provide the EXACT text. Ensure the resulting code is correct and idiomatic.")
        val newString: String
    )

    override suspend fun execute(args: Args): String {
        if (args.filePath.isBlank()) return "file_path must not be empty"

        val target = resolvePathInsideWorkspace(args.filePath)
            ?: return "File path must be absolute: ${args.filePath}"

        if (!target.path.startsWith(config.workDir.canonicalFile.path + File.separator) && target != config.workDir.canonicalFile) {
            return "Security error: Cannot access outside workspace: ${args.filePath}"
        }

        val fileExists = target.exists()

        if (fileExists && !target.isFile) {
            return "Not a file: ${args.filePath}"
        }

        val existingState = if (fileExists) loadExistingFileState(target) else null
        val currentContent = existingState?.content

        val normalizedOld = args.oldString.normalizeLineEndings()
        val normalizedNew = args.newString.normalizeLineEndings()

        val editPlan = calculateEdit(
            filePath = args.filePath,
            currentContent = currentContent,
            fileExists = fileExists,
            oldString = normalizedOld,
            newString = normalizedNew
        )

        if (editPlan.error != null) {
            return editPlan.error
        }

        val parent = target.parentFile
        if (parent != null && config.createParentDirs && !parent.exists() && !parent.mkdirs()) {
            return "Failed to create parent directory: ${parent.path}"
        }

        val writeResult = applyWrite(target, editPlan.newContent, existingState)
        if (writeResult.error != null) return writeResult.error

        val finalVFile = writeResult.vFile

        val snippet = extractSnippet(currentContent, editPlan.newContent)
        val llmContent = buildString {
            if (editPlan.isNewFile) {
                append("Created new file: ${args.filePath} with provided content.")
            } else {
                append("The file: ${args.filePath} has been updated. Replaced ${editPlan.replacedCount} occurrence(s).")
            }
            if (snippet != null) {
                append("\n\n---\n\n")
                append(snippet)
            }
            if (finalVFile != null) {
                append(runPostEditDiagnostics(project, finalVFile))
            }
        }

        return llmContent
    }

    // Plain disk read — agent is the only writer, so the on-disk file is
    // authoritative. Avoids touching IntelliJ's threaded model from a background
    // coroutine.
    private fun readCurrentContent(target: File): String {
        return target.readText(Charsets.UTF_8).replace("\r\n", "\n")
    }

    private data class ExistingFileState(
        val content: String,
        val vFile: VirtualFile?,
        val document: Document?,
    )

    private data class WriteResult(val vFile: VirtualFile? = null, val error: String? = null)

    private fun loadExistingFileState(target: File): ExistingFileState {
        val vFile = resolveVirtualFile(target)
        val document = vFile?.let { loadDocument(it) }
        val content = document?.text?.replace("\r\n", "\n") ?: readCurrentContent(target)
        return ExistingFileState(content = content, vFile = vFile, document = document)
    }

    private fun applyWrite(target: File, newContent: String, existingState: ExistingFileState?): WriteResult {
        if (existingState?.vFile != null && existingState.document != null) {
            return applyDocumentWrite(existingState, newContent)
        }
        return applyDiskWrite(target, newContent)
    }

    private fun applyDocumentWrite(existingState: ExistingFileState, newContent: String): WriteResult {
        val document = requireNotNull(existingState.document)
        val vFile = requireNotNull(existingState.vFile)
        var writeError: String? = null
        ApplicationManager.getApplication().invokeAndWait {
            try {
                WriteCommandAction.runWriteCommandAction(project) {
                    document.replaceString(0, document.textLength, newContent)
                }
                PsiDocumentManager.getInstance(project).commitDocument(document)
                FileDocumentManager.getInstance().saveDocument(document)
            } catch (e: Throwable) {
                writeError = "Document write failed for ${vFile.path}: ${e.message}"
            }
        }
        return WriteResult(vFile = vFile, error = writeError)
    }

    // Disk fallback for files that do not have an editable IntelliJ document yet
    // (for example brand-new files created through an empty old_string).
    private fun applyDiskWrite(target: File, newContent: String): WriteResult {
        try {
            target.parentFile?.takeIf { !it.exists() }?.mkdirs()
            target.writeText(newContent, Charsets.UTF_8)
        } catch (e: Throwable) {
            return WriteResult(error = "Disk write failed for ${target.absolutePath}: ${e.message}")
        }

        var vFile: VirtualFile? = null
        var refreshError: String? = null
        ApplicationManager.getApplication().invokeAndWait {
            try {
                val refreshed = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(target)
                refreshed?.refresh(false, false)
                vFile = refreshed
            } catch (e: Throwable) {
                refreshError = "VFS refresh failed for ${target.absolutePath}: ${e.message}"
            }
        }
        return WriteResult(vFile = vFile, error = refreshError)
    }

    private fun resolveVirtualFile(target: File): VirtualFile? {
        var vFile: VirtualFile? = null
        ApplicationManager.getApplication().invokeAndWait {
            val localFileSystem = LocalFileSystem.getInstance()
            vFile = localFileSystem.findFileByIoFile(target) ?: localFileSystem.refreshAndFindFileByIoFile(target)
        }
        return vFile
    }

    private fun loadDocument(vFile: VirtualFile): Document? {
        var document: Document? = null
        ApplicationManager.getApplication().invokeAndWait {
            document = FileDocumentManager.getInstance().getDocument(vFile)
        }
        return document
    }

    private data class EditPlan(
        val newContent: String = "",
        val occurrences: Int = 0,
        val replacedCount: Int = 0,
        val isNewFile: Boolean = false,
        val error: String? = null,
    )

    private fun calculateEdit(
        filePath: String,
        currentContent: String?,
        fileExists: Boolean,
        oldString: String,
        newString: String
    ): EditPlan {
        if (oldString.isEmpty() && !fileExists) {
            return EditPlan(
                newContent = newString,
                occurrences = 0,
                replacedCount = 1,
                isNewFile = true
            )
        }

        if (!fileExists) {
            return EditPlan(error = "File not found. Cannot apply edit. Use an empty old_string to create a new file.")
        }

        if (currentContent == null) {
            return EditPlan(error = "Failed to read content of existing file: $filePath")
        }

        if (oldString.isEmpty()) {
            return EditPlan(error = "Failed to edit. Attempted to create a file that already exists.")
        }

        val augmented = maybeAugmentOldStringForDeletion(currentContent, oldString, newString)

        val matchResult = EditHelper.findMatch(currentContent, augmented)
            ?: return EditPlan(
                error = "Failed to edit, 0 occurrences found for old_string in $filePath. " +
                    "The exact text was not found. Verify whitespace, indentation, and context with read_file."
            )

        val effectiveOldString = matchResult.matchedOldString
        val occurrences = matchResult.occurrences

        if (occurrences > 1) {
            return EditPlan(
                error = "Failed to edit. Found $occurrences occurrences for old_string in $filePath " +
                    "but replace_all was not enabled."
            )
        }

        if (effectiveOldString == newString) {
            return EditPlan(error = "No changes to apply. The old_string and new_string are identical.")
        }

        val newContent = currentContent.replaceFirst(effectiveOldString, newString)

        if (newContent == currentContent) {
            return EditPlan(error = "No changes to apply. The new content is identical to the current content.")
        }

        val replacedCount = 1
        return EditPlan(
            newContent = newContent,
            occurrences = occurrences,
            replacedCount = replacedCount,
            isNewFile = false
        )
    }

    private fun maybeAugmentOldStringForDeletion(
        fileContent: String,
        oldString: String,
        newString: String,
    ): String {
        if (oldString.isEmpty() || newString.isNotEmpty() || oldString.endsWith("\n")) {
            return oldString
        }
        val candidate = "$oldString\n"
        return if (fileContent.contains(candidate)) candidate else oldString
    }

    private fun resolvePathInsideWorkspace(path: String): File? {
        val raw = File(path)
        if (!raw.isAbsolute) return null
        return raw.canonicalFile
    }

    private fun String.normalizeLineEndings(): String = replace("\r\n", "\n")

    private fun extractSnippet(oldContent: String?, newContent: String): String? {
        val newLines = newContent.lines()
        if (newLines.isEmpty()) return null

        if (oldContent == null) {
            return newLines.take(30).joinToString("\n")
        }

        val oldLines = oldContent.lines()
        var prefix = 0
        while (prefix < oldLines.size && prefix < newLines.size && oldLines[prefix] == newLines[prefix]) {
            prefix++
        }

        var oldSuffix = oldLines.size - 1
        var newSuffix = newLines.size - 1
        while (oldSuffix >= prefix && newSuffix >= prefix && oldLines[oldSuffix] == newLines[newSuffix]) {
            oldSuffix--
            newSuffix--
        }

        val from = (prefix - 3).coerceAtLeast(0)
        val to = (newSuffix + 3).coerceAtMost(newLines.lastIndex)
        if (from > to) return null

        return newLines.subList(from, to + 1).joinToString("\n")
    }
}
