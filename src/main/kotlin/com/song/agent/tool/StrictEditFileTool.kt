package com.song.agent.tool

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

class StrictEditTool(
    root: Path
) : Tool<StrictEditTool.Args, String>(
    argsSerializer = Args.serializer(),
    resultSerializer = String.serializer(),
    name = "edit",
    description = TOOL_DESCRIPTION
) {

    private val rootNorm: Path = root.toAbsolutePath().normalize()

    @Serializable
    data class Args(
        @SerialName("file_path")
        @property:LLMDescription("The absolute path to the file to modify. Must start with '/'.")
        val filePath: String,

        @SerialName("old_string")
        @property:LLMDescription(
            "The exact literal text to replace, preferably unescaped. For single replacements (default), include at least 3 lines of context BEFORE and AFTER the target text, matching whitespace and indentation precisely. If this string is not the exact literal text (i.e. you escaped it) or does not match exactly, the tool will fail."
        )
        val oldString: String,

        @SerialName("new_string")
        @property:LLMDescription("The exact literal text to replace `old_string` with, preferably unescaped. Provide the EXACT text. Ensure the resulting code is correct and idiomatic.")
        val newString: String,

        @SerialName("replace_all")
        @property:LLMDescription(
            "Replace all occurrences of old_string (default false)."
        )
        val replaceAll: Boolean = false
    )

    override suspend fun execute(args: Args): String {
        val fullPath = rootNorm.resolve(args.filePath).normalize()

        // Path traversal 방지
        if (!fullPath.startsWith(rootNorm)) {
            throw IllegalArgumentException(
                "filePath '${args.filePath}' is outside of project root."
            )
        }

        val fileExists = fullPath.exists()

        val oldNorm = normalizeLF(args.oldString)
        val newNorm = normalizeLF(args.newString)

        // 새 파일 생성 케이스: 파일 없음 + old_string 빈 문자열
        val isNewFile = (!fileExists && oldNorm.isEmpty())

        if (!fileExists && !isNewFile) {
            throw IllegalArgumentException(
                buildError(
                    filePath = args.filePath,
                    title = "FileNotFound",
                    message = "File not found. Cannot apply edit. Use an empty old_string to create a new file.",
                    details = "File not found: ${fullPath}"
                )
            )
        }

        // 파일이 이미 있는데 old_string==""이면 생성 시도로 간주 -> 실패 (TS 로직)
        if (fileExists && args.oldString.isEmpty()) {
            throw IllegalArgumentException(
                buildError(
                    filePath = args.filePath,
                    title = "AttemptToCreateExistingFile",
                    message = "Failed to edit. Attempted to create a file that already exists.",
                    details = "File already exists, cannot create: ${fullPath}"
                )
            )
        }

        val currentContent: String? = if (fileExists) normalizeLF(fullPath.readText()) else null

        // (방어) new file인데 old_string 비어있지 않으면 여기 오면 안됨
        if (currentContent == null && !isNewFile) {
            throw IllegalArgumentException(
                buildError(
                    filePath = args.filePath,
                    title = "ReadContentFailure",
                    message = "Failed to read content of file.",
                    details = "Failed to read content of existing file: ${fullPath}"
                )
            )
        }

        // --- 검증 + 치환 준비 ---
        val finalOld = if (!isNewFile) {
            maybeAugmentOldStringForDeletion(
                currentContent = currentContent!!,
                oldString = oldNorm,
                newString = newNorm
            )
        } else {
            oldNorm
        }

        val occurrences = if (!isNewFile) countOccurrences(currentContent!!, finalOld) else 0

        if (!isNewFile) {
            if (finalOld.isEmpty()) {
                // TS처럼: 기존 파일에서 old_string 빈 문자열이면 변경 안 함(근데 우리는 여기 오기 전에 이미 에러 처리)
                throw IllegalArgumentException(
                    buildError(
                        filePath = args.filePath,
                        title = "InvalidOldString",
                        message = "old_string cannot be empty when editing an existing file.",
                        details = "Provide exact literal text to replace."
                    )
                )
            }

            if (occurrences == 0) {
                throw IllegalArgumentException(
                    buildError(
                        filePath = args.filePath,
                        title = "EditNoOccurrenceFound",
                        message = "Failed to edit, could not find the string to replace.",
                        details =
                            "0 occurrences found for old_string in ${args.filePath}. " +
                                    "Ensure whitespace/indent/newlines match exactly. Use read_file tool to verify."
                    )
                )
            }

            if (!args.replaceAll && occurrences > 1) {
                throw IllegalArgumentException(
                    buildError(
                        filePath = args.filePath,
                        title = "EditExpectedOccurrenceMismatch",
                        message = "Failed to edit because the text matches multiple locations. Provide more context or set replace_all=true.",
                        details = "Found $occurrences occurrences for old_string in ${args.filePath} but replace_all was not enabled."
                    )
                )
            }

            if (finalOld == newNorm) {
                throw IllegalArgumentException(
                    buildError(
                        filePath = args.filePath,
                        title = "EditNoChange",
                        message = "No changes to apply. The old_string and new_string are identical.",
                        details = "old_string == new_string in file: ${args.filePath}"
                    )
                )
            }
        }

        // --- 치환 수행 ---
        val newContent = if (isNewFile) {
            newNorm
        } else {
            applyReplacement(
                currentContent = currentContent!!,
                oldString = finalOld,
                newString = newNorm,
                replaceAll = args.replaceAll
            )
        }

        if (!isNewFile && currentContent == newContent) {
            throw IllegalArgumentException(
                buildError(
                    filePath = args.filePath,
                    title = "EditNoChange",
                    message = "No changes to apply. The new content is identical to the current content.",
                    details = "No changes to apply for file: ${args.filePath}"
                )
            )
        }

        // --- write ---
        fullPath.parent?.takeIf { !it.exists() }?.createDirectories()
        fullPath.writeText(newContent)

        // --- snippet ---
        val snippet = extractEditSnippet(
            oldContent = currentContent,
            newContent = newContent
        )

        val op = if (isNewFile) "Created" else "Updated"
        return buildString {
            appendLine("$op file: ${args.filePath}")
            if (!isNewFile) appendLine("Occurrences: $occurrences (replace_all=${args.replaceAll})")
            if (snippet != null) {
                appendLine()
                appendLine("Showing lines ${snippet.startLine}-${snippet.endLine} of ${snippet.totalLines}:")
                appendLine("```")
                appendLine(snippet.content)
                appendLine("```")
            }
        }.trimEnd()
    }

    // --- helpers ---

    private fun normalizeLF(s: String): String =
        s.replace("\r\n", "\n").replace("\r", "\n")

    private fun applyReplacement(
        currentContent: String,
        oldString: String,
        newString: String,
        replaceAll: Boolean
    ): String {
        // Kotlin의 String.replace는 리터럴 치환이라 JS의 "$" 대체 이슈가 없음
        return if (replaceAll) {
            currentContent.replace(oldString, newString)
        } else {
            currentContent.replaceFirst(oldString, newString)
        }
    }

    private fun countOccurrences(haystack: String, needle: String): Int {
        if (needle.isEmpty()) return 0
        var count = 0
        var idx = 0
        while (true) {
            val found = haystack.indexOf(needle, startIndex = idx)
            if (found < 0) break
            count++
            idx = found + needle.length
        }
        return count
    }

    /**
     * TS의 deletion 보정 의도 + 너 코드의 "마지막 개행 1개 차이 허용"을 합친 최소 구현.
     * - new_string이 비어있고
     * - old_string에 trailing newline이 없는데
     * - 파일에는 "old_string + \n" 형태로 존재하면
     *   old_string에 \n을 붙여서 줄 단위 삭제가 자연스럽게 되도록 유도
     */
    private fun maybeAugmentOldStringForDeletion(
        currentContent: String,
        oldString: String,
        newString: String
    ): String {
        if (newString.isNotEmpty()) return oldString
        if (oldString.isEmpty()) return oldString

        val hasOldPlusNl = currentContent.contains(oldString + "\n")
        return if (!oldString.endsWith("\n") && hasOldPlusNl) {
            oldString + "\n"
        } else {
            oldString
        }
    }

    private data class Snippet(
        val startLine: Int,
        val endLine: Int,
        val totalLines: Int,
        val content: String
    )

    private fun extractEditSnippet(
        oldContent: String?,
        newContent: String,
        context: Int = 3,
        maxLines: Int = 80
    ): Snippet? {
        val newLines = newContent.split("\n")
        val total = newLines.size

        // 새 파일이면 상단 일부만 보여주기
        if (oldContent == null) {
            val end = minOf(total, maxLines)
            return Snippet(
                startLine = 1,
                endLine = end,
                totalLines = total,
                content = newLines.take(end).joinToString("\n")
            )
        }

        if (oldContent == newContent) return null

        val oldLines = oldContent.split("\n")
        val minSize = minOf(oldLines.size, newLines.size)

        var prefix = 0
        while (prefix < minSize && oldLines[prefix] == newLines[prefix]) prefix++

        var suffix = 0
        while (
            suffix < (minSize - prefix) &&
            oldLines[oldLines.lastIndex - suffix] == newLines[newLines.lastIndex - suffix]
        ) suffix++

        val changedStart = prefix
        val changedEnd = (newLines.size - 1 - suffix).coerceAtLeast(changedStart)

        var start = (changedStart - context).coerceAtLeast(0)
        var end = (changedEnd + context).coerceAtMost(newLines.lastIndex)

        val size = end - start + 1
        if (size > maxLines) {
            val center = (changedStart + changedEnd) / 2
            start = (center - maxLines / 2).coerceAtLeast(0)
            end = (start + maxLines - 1).coerceAtMost(newLines.lastIndex)
        }

        return Snippet(
            startLine = start + 1,
            endLine = end + 1,
            totalLines = total,
            content = newLines.subList(start, end + 1).joinToString("\n")
        )
    }

    private fun buildError(
        filePath: String,
        title: String,
        message: String,
        details: String? = null
    ): String = buildString {
        appendLine("# $title")
        appendLine()
        appendLine("File: `$filePath`")
        appendLine()
        appendLine(message)
        if (!details.isNullOrBlank()) {
            appendLine()
            appendLine("Details:")
            appendLine("```")
            appendLine(details)
            appendLine("```")
        }
        appendLine()
        appendLine(
            "Notes:\n" +
                    "- old_string/new_string must match EXACTLY (including whitespace and newlines)\n" +
                    "- If replace_all=false, old_string must appear exactly once\n" +
                    "- Use read_file to verify the exact file content before editing"
        )
    }.trimEnd()

    override fun encodeResultToString(result: String): String = result

    companion object {
        private val TOOL_DESCRIPTION = """
Replaces text within a file. By default, replaces a single occurrence. Set \`replace_all\` to true when you intend to modify every instance of \`old_string\`. This tool requires providing significant context around the change to ensure precise targeting. Always use the __read_file__ tool to examine the file's current content before attempting a text replacement.

The user has the ability to modify the \`new_string\` content. If modified, this will be stated in the response.

Expectation for required parameters:
1. \`file_path\` MUST be an absolute path; otherwise an error will be thrown.
2. \`old_string\` MUST be the exact literal text to replace (including all whitespace, indentation, newlines, and surrounding code etc.).
3. \`new_string\` MUST be the exact literal text to replace \`old_string\` with (also including all whitespace, indentation, newlines, and surrounding code etc.). Ensure the resulting code is correct and idiomatic.
4. NEVER escape \`old_string\` or \`new_string\`, that would break the exact literal text requirement.
**Important:** If ANY of the above are not satisfied, the tool will fail. CRITICAL for \`old_string\`: Must uniquely identify the single instance to change. Include at least 3 lines of context BEFORE and AFTER the target text, matching whitespace and indentation precisely. If this string matches multiple locations, or does not match exactly, the tool will fail.
**Multiple replacements:** Set \`replace_all\` to true when you want to replace every occurrence that matches \`old_string\`.
""".trimIndent()
    }
}