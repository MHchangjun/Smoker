package com.song.agent.tool

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File

class DiffFencedEditTool(
    private val config: Config = Config()
) : Tool<DiffFencedEditTool.Args, DiffFencedEditTool.Result>(
    argsSerializer = Args.serializer(),
    resultSerializer = Result.serializer(),
    name = ToolNames.DIFF_FENCED_EDIT,
    description = """
Apply changes to a file using *SEARCH/REPLACE blocks*.

Every *SEARCH/REPLACE block* must use this format:
1. The file path alone on a line, verbatim.
2. <<<<<<< SEARCH
3. A contiguous chunk of lines to search for in the existing source code
4. =======
5. The lines to replace into the source code
6. >>>>>>> REPLACE

Every *SEARCH* section must *EXACTLY MATCH* the existing file content, character for character, including all comments, docstrings, etc.
Do NOT include +, -, @@, or any diff-like syntax in the SEARCH block.
The SEARCH block must be a literal copy of the existing code section.

*SEARCH/REPLACE* blocks will *only* replace the first match occurrence.
Include multiple unique *SEARCH/REPLACE* blocks if needed.
Include enough lines in each SEARCH section to uniquely match each set of lines that need to change.

Keep *SEARCH/REPLACE* blocks concise.
Break large *SEARCH/REPLACE* blocks into a series of smaller blocks that each change a small portion of the file.
Include just the changing lines, and a few surrounding lines if needed for uniqueness.

To move code within a file, use 2 *SEARCH/REPLACE* blocks: 1 to delete it from its current location, 1 to insert it in the new location.

If you want to put code in a new file, use a *SEARCH/REPLACE block* with:
- A new file path (including dir/filename)
- An empty *SEARCH* section
- The new file's contents in the *REPLACE* section

Always use the read_file tool to examine the file's current content before attempting edits.
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
        @property:LLMDescription("The absolute path to the file to modify.")
        val filePath: String,
        @SerialName("diff")
        @property:LLMDescription(
            "One or more SEARCH/REPLACE blocks. " +
                    "Each block: file_path\\n<<<<<<< SEARCH\\n...existing code...\\n=======\\n...new code...\\n>>>>>>> REPLACE. " +
                    "The SEARCH section must EXACTLY MATCH existing file content. No +/-/@@ diff syntax."
        )
        val diff: String,
    )

    @Serializable
    data class Result(
        @SerialName("file_path")
        val filePath: String = "",
        @SerialName("blocks_applied")
        val blocksApplied: Int = 0,
        val message: String,
        val snippet: String? = null,
        val error: String? = null,
    )

    override suspend fun execute(args: Args): Result {
        if (args.filePath.isBlank()) {
            return errorResult("file_path must not be empty.")
        }

        val target = try {
            resolvePathInsideWorkspace(args.filePath)
        } catch (e: Exception) {
            return errorResult(e.message ?: "Invalid file path: ${args.filePath}")
        }

        val blocks = parseSearchReplaceBlocks(args.diff.normalizeLineEndings())

        if (blocks.isEmpty()) {
            return errorResult(
                "No valid SEARCH/REPLACE blocks found. " +
                        "Expected format: <<<<<<< SEARCH\\n...\\n=======\\n...\\n>>>>>>> REPLACE"
            )
        }

        if (!target.exists()) {
            if (blocks.size == 1 && blocks[0].search.isEmpty()) {
                return try {
                    ensureParentDirs(target)
                    target.writeText(blocks[0].replace, Charsets.UTF_8)
                    Result(
                        filePath = args.filePath,
                        blocksApplied = 1,
                        message = "Created new file: ${args.filePath}.",
                        snippet = blocks[0].replace.lines().take(30).joinToString("\n"),
                    )
                } catch (e: Exception) {
                    errorResult("Failed to create file ${args.filePath}: ${e.message}")
                }
            }
            return errorResult(
                "File not found: ${args.filePath}. " +
                        "To create a new file, use a single block with an empty SEARCH section."
            )
        }

        if (!target.isFile) {
            return errorResult("Path exists but is not a file: ${args.filePath}")
        }

        val originalContent = target.readText(Charsets.UTF_8).normalizeLineEndings()
        var content = originalContent
        var appliedCount = 0

        for ((index, block) in blocks.withIndex()) {
            val search = block.search
            val replace = block.replace

            if (search.isEmpty()) {
                content += replace
                appliedCount++
                continue
            }

            // Try exact match
            if (content.contains(search)) {
                content = content.replaceFirst(search, replace)
                appliedCount++
                continue
            }

            // Try whitespace-flexible match
            val flexMatch = findFlexibleMatch(content, search)
            if (flexMatch != null) {
                content = content.substring(0, flexMatch.first) +
                        replace +
                        content.substring(flexMatch.second)
                appliedCount++
                continue
            }

            // Match failed — return diagnostic
            val diagnostic = buildMatchDiagnostic(content, search, index + 1, args.filePath)
            return Result(
                filePath = args.filePath,
                blocksApplied = appliedCount,
                message = "Partial failure: applied $appliedCount block(s) before block #${index + 1} failed.",
                error = diagnostic,
            )
        }

        if (content == originalContent) {
            return errorResult("No changes. The REPLACE content is identical to the SEARCH content.")
        }

        return try {
            target.writeText(content, Charsets.UTF_8)
            Result(
                filePath = args.filePath,
                blocksApplied = appliedCount,
                message = "Applied $appliedCount SEARCH/REPLACE block(s) to ${args.filePath}.",
            )
        } catch (e: Exception) {
            errorResult("Applied $appliedCount block(s) in memory but failed to write: ${e.message}")
        }
    }

    // --- Parsing ---

    data class SearchReplaceBlock(val search: String, val replace: String)

    private fun parseSearchReplaceBlocks(diff: String): List<SearchReplaceBlock> {
        val blocks = mutableListOf<SearchReplaceBlock>()
        val lines = diff.lines()
        var i = 0

        while (i < lines.size) {
            val trimmed = lines[i].trimStart()

            // Skip fence lines (```) if present — they're optional noise
            if (trimmed.startsWith("```")) {
                i++
                continue
            }

            if (trimmed.startsWith("<<<<<<< SEARCH")) {
                i++ // skip <<<<<<< SEARCH

                val searchLines = mutableListOf<String>()
                while (i < lines.size && !lines[i].trimStart().startsWith("=======")) {
                    searchLines.add(lines[i]); i++
                }
                if (i >= lines.size) break

                i++ // skip =======

                val replaceLines = mutableListOf<String>()
                while (i < lines.size && !lines[i].trimStart().startsWith(">>>>>>> REPLACE")) {
                    replaceLines.add(lines[i]); i++
                }
                if (i >= lines.size) break

                i++ // skip >>>>>>> REPLACE

                blocks.add(SearchReplaceBlock(
                    search = searchLines.joinToString("\n"),
                    replace = replaceLines.joinToString("\n"),
                ))
            } else {
                i++
            }
        }
        return blocks
    }

    // --- Diagnostics ---

    private fun buildMatchDiagnostic(
        content: String,
        search: String,
        blockNumber: Int,
        filePath: String,
    ): String {
        val contentLines = content.lines()
        val searchLines = search.lines()
        if (searchLines.isEmpty()) return "SEARCH block #$blockNumber is empty."

        val firstSearchLine = searchLines[0].trimEnd()
        val sb = StringBuilder()
        sb.appendLine("SEARCH block #$blockNumber did not match in $filePath.")

        val candidates = contentLines.indices.filter { i ->
            contentLines[i].trimEnd() == firstSearchLine
        }

        if (candidates.isEmpty()) {
            sb.appendLine("Reason: First line of SEARCH not found anywhere in the file.")
            sb.appendLine("  SEARCH line 1: '${searchLines[0]}'")
            val closestIdx = contentLines.indices.minByOrNull {
                levenshteinDistance(contentLines[it].trimEnd(), firstSearchLine)
            }
            if (closestIdx != null) {
                val dist = levenshteinDistance(contentLines[closestIdx].trimEnd(), firstSearchLine)
                if (dist <= firstSearchLine.length / 3) {
                    sb.appendLine("  Closest match at file line ${closestIdx + 1}: '${contentLines[closestIdx]}'")
                    sb.appendLine("  (edit distance: $dist)")
                }
            }
        } else {
            sb.appendLine("First line matched at file line(s): ${candidates.map { it + 1 }}")
            val startIdx = candidates[0]
            for (j in 1 until searchLines.size) {
                val fileIdx = startIdx + j
                if (fileIdx >= contentLines.size) {
                    sb.appendLine("  SEARCH line ${j + 1}: file ended at line ${contentLines.size} (SEARCH has ${searchLines.size} lines).")
                    break
                }
                if (contentLines[fileIdx].trimEnd() != searchLines[j].trimEnd()) {
                    sb.appendLine("  Diverged at SEARCH line ${j + 1} (file line ${fileIdx + 1}):")
                    sb.appendLine("    Expected: '${searchLines[j]}'")
                    sb.appendLine("    Actual:   '${contentLines[fileIdx]}'")

                    val expectedIndent = searchLines[j].length - searchLines[j].trimStart().length
                    val actualIndent = contentLines[fileIdx].length - contentLines[fileIdx].trimStart().length
                    if (expectedIndent != actualIndent &&
                        searchLines[j].trimStart() == contentLines[fileIdx].trimStart()
                    ) {
                        sb.appendLine("    (indentation mismatch: $expectedIndent vs $actualIndent spaces)")
                    }
                    break
                }
            }
        }

        sb.appendLine("Use read_file to verify the exact current content before retrying.")
        return sb.toString().trimEnd()
    }

    private fun levenshteinDistance(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length

        val la = a.length
        val lb = b.length
        var prev = IntArray(lb + 1) { it }
        var curr = IntArray(lb + 1)

        for (i in 1..la) {
            curr[0] = i
            for (j in 1..lb) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = minOf(prev[j] + 1, curr[j - 1] + 1, prev[j - 1] + cost)
            }
            val tmp = prev; prev = curr; curr = tmp
        }
        return prev[lb]
    }

    // --- Flexible matching ---

    private fun findFlexibleMatch(content: String, search: String): Pair<Int, Int>? {
        val contentLines = content.lines()
        val searchLines = search.lines().map { it.trimEnd() }
        if (searchLines.isEmpty()) return null

        for (startIdx in 0..(contentLines.size - searchLines.size)) {
            var matched = true
            for (j in searchLines.indices) {
                if (contentLines[startIdx + j].trimEnd() != searchLines[j]) {
                    matched = false; break
                }
            }
            if (matched) {
                val startOffset = contentLines.take(startIdx).sumOf { it.length + 1 }
                val endOffset = contentLines.take(startIdx + searchLines.size).sumOf { it.length + 1 }
                return Pair(startOffset, endOffset.coerceAtMost(content.length))
            }
        }
        return null
    }

    // --- Utilities ---

    private fun errorResult(message: String) = Result(message = message, error = message)

    private fun ensureParentDirs(target: File) {
        val parent = target.parentFile
        if (parent != null && config.createParentDirs && !parent.exists()) parent.mkdirs()
    }

    private fun resolvePathInsideWorkspace(path: String): File {
        val root = config.workDir.canonicalFile
        val raw = File(path)
        if (!raw.isAbsolute) throw IllegalArgumentException("File path must be absolute: $path")
        val target = raw.canonicalFile
        if (!target.path.startsWith(root.path + File.separator) && target != root) {
            throw IllegalArgumentException("Security error: Cannot access outside workspace: $path")
        }
        return target
    }

    private fun String.normalizeLineEndings(): String = replace("\r\n", "\n")
}