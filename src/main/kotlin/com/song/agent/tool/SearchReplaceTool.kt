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

class SearchReplaceTool(
    root: Path
) : Tool<SearchReplaceTool.Args, String>(
    argsSerializer = Args.serializer(),
    resultSerializer = String.serializer(),
    name = "search_replace",
    description = "Replace sections of files using SEARCH/REPLACE blocks. " +
            "Supports fuzzy matching and detailed error reporting. " +
            "Format: <<<<<<< SEARCH\n[text]\n=======\n[replacement]\n>>>>>>> REPLACE"
) {

    private val rootNorm: Path = root.toAbsolutePath().normalize()

    @Serializable
    data class Args(
        @SerialName("file_path")
        @property:LLMDescription("Path to the file to be patched.")
        val filePath: String,
        @property:LLMDescription("Diff to apply in the simple SEARCH/REPLACE block format.")
        val content: String
    )

    override suspend fun execute(args: Args): String {
        val fullPath = rootNorm.resolve(args.filePath).normalize()

        if (!fullPath.startsWith(rootNorm)) {
            throw IllegalArgumentException("filePath '${args.filePath}' is outside of project root.")
        }

        val edits = parseSearchReplaceBlocks(args.content)

        if (edits.isEmpty()) {
            return "No SEARCH/REPLACE blocks found. Nothing to apply."
        }

        var content = if (fullPath.exists()) fullPath.readText() else ""

        val failed = mutableListOf<EditBlock>()
        val passed = mutableListOf<EditBlock>()

        for (edit in edits) {
            val newContent = applySingleEdit(
                currentContent = content,
                search = edit.search,
                replace = edit.replace
            )

            if (newContent != null) {
                content = newContent
                passed += edit
            } else {
                failed += edit
            }
        }

        if (failed.isEmpty()) {
            fullPath.parent?.takeIf { !it.exists() }?.createDirectories()
            fullPath.writeText(content)

            val blocksWord = if (passed.size == 1) "block" else "blocks"
            return "Successfully applied ${passed.size} SEARCH/REPLACE $blocksWord to ${args.filePath}."
        }

        val blocksWord = if (failed.size == 1) "block" else "blocks"
        val sb = StringBuilder()
        sb.appendLine("# ${failed.size} SEARCH/REPLACE $blocksWord failed to match in ${args.filePath}!")

        for ((index, edit) in failed.withIndex()) {
            sb.appendLine()
            sb.appendLine("## SearchReplaceNoExactMatch #${index + 1}")
            sb.appendLine("This SEARCH block failed to exactly match any lines in ${args.filePath}:")

            val didYouMean = findSimilarLines(edit.search, content)
            if (didYouMean.isNotBlank()) {
                sb.appendLine("Did you mean to match some of these actual lines from ${args.filePath}?")
                sb.appendLine("```")
                sb.appendLine(didYouMean)
                sb.appendLine("```")
                sb.appendLine()
            }

            if (edit.replace.isNotBlank() && content.contains(edit.replace)) {
                sb.appendLine(
                    "Are you sure you still need this SEARCH/REPLACE block?\n" +
                            "The REPLACE lines are already present in ${args.filePath}!\n"
                )
            }
        }

        sb.appendLine(
            "The SEARCH section must exactly match an existing block of lines including all " +
                    "whitespace, comments, indentation, docstrings, etc."
        )

        if (passed.isNotEmpty()) {
            val blocks = if (passed.size == 1) "block" else "blocks"
            sb.appendLine()
            sb.appendLine(
                "# The other ${passed.size} SEARCH/REPLACE $blocks were applied successfully.\n" +
                        "Don't re-send them.\n" +
                        "Just reply with fixed versions of the blocks above that failed to match."
            )
        }

        throw IllegalArgumentException(sb.toString())
    }

    private data class EditBlock(
        val search: String,
        val replace: String
    )

    private fun parseSearchReplaceBlocks(
        patch: String
    ): List<EditBlock> {
        val lines = patch.lines()
        val edits = mutableListOf<EditBlock>()
        var i = 0

        while (i < lines.size) {
            val line = lines[i].trim()

            if (line.startsWith("<<<<<<< SEARCH")) {
                i++

                val searchBuilder = StringBuilder()
                while (i < lines.size && !lines[i].trim().startsWith("=======")) {
                    searchBuilder.appendLine(lines[i])
                    i++
                }
                if (i >= lines.size || !lines[i].trim().startsWith("=======")) {
                    throw IllegalArgumentException("Malformed SEARCH/REPLACE block: missing =======")
                }

                // divider line
                i++

                val replaceBuilder = StringBuilder()
                while (i < lines.size && !lines[i].trim().startsWith(">>>>>>> REPLACE")) {
                    replaceBuilder.appendLine(lines[i])
                    i++
                }
                if (i >= lines.size || !lines[i].trim().startsWith(">>>>>>> REPLACE")) {
                    throw IllegalArgumentException("Malformed SEARCH/REPLACE block: missing >>>>>>> REPLACE")
                }

                // UPDATED line
                i++

                val search = searchBuilder.toString()
                val replace = replaceBuilder.toString()

                edits += EditBlock(search = search, replace = replace)
                continue
            }

            i++
        }

        return edits
    }

    private fun ensureTrailingNewline(text: String): String {
        if (text.isEmpty()) return text
        return if (text.endsWith("\n")) text else text + "\n"
    }

    private fun applySingleEdit(
        currentContent: String,
        search: String,
        replace: String
    ): String? {
        if (currentContent.isEmpty() && search.isBlank()) {
            return ensureTrailingNewline(replace)
        }

        if (search.isBlank()) {
            return currentContent + ensureTrailingNewline(replace)
        }

        var idx = currentContent.indexOf(search)
        var pattern = search

        // 2차: 마지막 개행 하나 차이 허용 (옵션)
        if (idx == -1) {
            val alt =
                if (search.endsWith("\n")) search.removeSuffix("\n")
                else search + "\n"

            val altIdx = currentContent.indexOf(alt)
            if (altIdx != -1) {
                idx = altIdx
                pattern = alt
            }
        }

        if (idx == -1) {
            return null
        }

        val before = currentContent.substring(0, idx)
        val after = currentContent.substring(idx + pattern.length)
        return before + replace + after
    }

    private fun findSimilarLines(
        searchLines: String,
        content: String,
        threshold: Double = 0.6
    ): String {
        val search = searchLines.lines().filter { it.isNotEmpty() }
        val contentLines = content.lines()

        if (search.isEmpty() || contentLines.isEmpty()) return ""

        var bestRatio = 0.0
        var bestStart = -1

        val windowSize = search.size
        for (i in 0..(contentLines.size - windowSize)) {
            val chunk = contentLines.subList(i, i + windowSize)
            val ratio = lineSimilarity(search, chunk)
            if (ratio > bestRatio) {
                bestRatio = ratio
                bestStart = i
            }
        }

        if (bestRatio < threshold || bestStart == -1) return ""

        // 앞뒤로 몇 줄 더 보여주기
        val context = 5
        val from = (bestStart - context).coerceAtLeast(0)
        val to = (bestStart + windowSize + context).coerceAtMost(contentLines.size)
        return contentLines.subList(from, to).joinToString("\n")
    }

    private fun lineSimilarity(a: List<String>, b: List<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val minSize = minOf(a.size, b.size)
        var same = 0
        for (i in 0 until minSize) {
            if (a[i].trim() == b[i].trim()) same++
        }
        return same.toDouble() / maxOf(a.size, b.size)
    }

    override fun encodeResultToString(result: String): String {
        return result
    }

}
