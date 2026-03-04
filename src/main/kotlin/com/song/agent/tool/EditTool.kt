package com.song.agent.tool

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File

class EditTool(
    private val config: Config = Config()
) : Tool<EditTool.Args, EditTool.Result>(
    argsSerializer = Args.serializer(),
    resultSerializer = Result.serializer(),
    name = "edit",
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
        val newString: String,
        @SerialName("replace_all")
        @property:LLMDescription("Replace all occurrences of old_string (default false).")
        val replaceAll: Boolean = false,
    )

    @Serializable
    data class Result(
        @SerialName("file_path")
        val filePath: String,
        @SerialName("occurrences_found")
        val occurrencesFound: Int,
        @SerialName("replaced_count")
        val replacedCount: Int,
        @SerialName("is_new_file")
        val isNewFile: Boolean,
        val message: String,
        @SerialName("snippet")
        val snippet: String? = null,
    )

    override suspend fun execute(args: Args): Result {
        validateInputs(args)

        val target = resolvePathInsideWorkspace(args.filePath)
        val fileExists = target.exists()

        if (fileExists && !target.isFile) {
            throw ToolExecutionException("Not a file: ${args.filePath}")
        }

        val currentContent = if (fileExists) {
            target.readText(Charsets.UTF_8).normalizeLineEndings()
        } else {
            null
        }

        val normalizedOld = args.oldString.normalizeLineEndings()
        val normalizedNew = args.newString.normalizeLineEndings()

        val editPlan = calculateEdit(
            filePath = args.filePath,
            currentContent = currentContent,
            fileExists = fileExists,
            oldString = normalizedOld,
            newString = normalizedNew,
            replaceAll = args.replaceAll,
        )

        val parent = target.parentFile
        if (parent != null && config.createParentDirs && !parent.exists() && !parent.mkdirs()) {
            throw ToolExecutionException("Failed to create parent directory: ${parent.path}")
        }

        try {
            target.writeText(editPlan.newContent, Charsets.UTF_8)
        } catch (e: Exception) {
            throw ToolExecutionException("Error writing ${args.filePath}: ${e.message}", e)
        }

        val snippet = extractSnippet(currentContent, editPlan.newContent)
        val message = if (editPlan.isNewFile) {
            "Created new file: ${args.filePath}."
        } else {
            "Updated ${args.filePath}. Replaced ${editPlan.replacedCount} occurrence(s)."
        }

        return Result(
            filePath = args.filePath,
            occurrencesFound = editPlan.occurrences,
            replacedCount = editPlan.replacedCount,
            isNewFile = editPlan.isNewFile,
            message = message,
            snippet = snippet
        )
    }

    private data class EditPlan(
        val newContent: String,
        val occurrences: Int,
        val replacedCount: Int,
        val isNewFile: Boolean,
    )

    private fun calculateEdit(
        filePath: String,
        currentContent: String?,
        fileExists: Boolean,
        oldString: String,
        newString: String,
        replaceAll: Boolean,
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
            throw ToolExecutionException(
                "File not found. Cannot apply edit. Use an empty old_string to create a new file."
            )
        }

        if (currentContent == null) {
            throw ToolExecutionException("Failed to read content of existing file: $filePath")
        }

        if (oldString.isEmpty()) {
            throw ToolExecutionException("Failed to edit. Attempted to create a file that already exists.")
        }

        val effectiveOldString = maybeAugmentOldStringForDeletion(currentContent, oldString, newString)
        val occurrences = countOccurrences(currentContent, effectiveOldString)

        if (occurrences == 0) {
            throw ToolExecutionException(
                "Failed to edit, 0 occurrences found for old_string in $filePath. " +
                    "The exact text was not found. Verify whitespace, indentation, and context with read_file."
            )
        }

        if (!replaceAll && occurrences > 1) {
            throw ToolExecutionException(
                "Failed to edit. Found $occurrences occurrences for old_string in $filePath " +
                    "but replace_all was not enabled."
            )
        }

        if (effectiveOldString == newString) {
            throw ToolExecutionException(
                "No changes to apply. The old_string and new_string are identical."
            )
        }

        val newContent = if (replaceAll) {
            currentContent.replace(effectiveOldString, newString)
        } else {
            currentContent.replaceFirst(effectiveOldString, newString)
        }

        if (newContent == currentContent) {
            throw ToolExecutionException(
                "No changes to apply. The new content is identical to the current content."
            )
        }

        val replacedCount = if (replaceAll) occurrences else 1
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

    private fun countOccurrences(source: String, substr: String): Int {
        if (substr.isEmpty()) return 0
        var count = 0
        var index = source.indexOf(substr)
        while (index != -1) {
            count++
            index = source.indexOf(substr, index + substr.length)
        }
        return count
    }

    private fun resolvePathInsideWorkspace(path: String): File {
        val root = config.workDir.canonicalFile
        val raw = File(path)
        if (!raw.isAbsolute) {
            throw ToolExecutionException("File path must be absolute: $path")
        }

        val target = raw.canonicalFile
        if (!target.path.startsWith(root.path + File.separator) && target != root) {
            throw ToolExecutionException("Security error: Cannot access outside workspace: $path")
        }
        return target
    }

    private fun validateInputs(args: Args) {
        if (args.filePath.isBlank()) throw ToolExecutionException("file_path must not be empty")
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
