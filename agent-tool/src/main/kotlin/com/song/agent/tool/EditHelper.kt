package com.song.agent.tool

/**
 * Multi-pass fuzzy matching for EditTool.
 *
 * Pass 1: Exact indexOf match
 * Pass 2: Unicode-normalized indexOf (smart quotes → ASCII, NBSP → space, etc.)
 * Pass 3: Line-by-line identity match
 * Pass 4: Line-by-line trimEnd() match (trailing whitespace tolerance)
 * Pass 5: Line-by-line normalizeChars + trimEnd
 * Pass 6: Strip trailing empty lines from oldString, then retry Pass 1-5
 *
 * Intentionally NOT done: leading whitespace normalization (indentation) — prevents wrong-scope matches.
 */
object EditHelper {

    data class MatchResult(
        val matchedOldString: String,
        val occurrences: Int,
        val pass: Int,
    )

    /**
     * Tries to find [oldString] in [fileContent] using progressively looser matching.
     * Returns the actual substring from fileContent that matched, plus occurrence count and which pass succeeded.
     * Returns null if no pass finds a match.
     */
    fun findMatch(fileContent: String, oldString: String): MatchResult? {
        if (oldString.isEmpty()) return null

        // Pass 1: Exact indexOf
        countExact(fileContent, oldString).let { count ->
            if (count > 0) return MatchResult(oldString, count, pass = 1)
        }

        // Pass 2: Unicode-normalized indexOf
        findWithNormalizedChars(fileContent, oldString)?.let { return it }

        // Pass 3-5: Line-based matching
        findLineBasedMatch(fileContent, oldString)?.let { return it }

        // Pass 6: Strip trailing empty lines from oldString, then retry 1-5
        val trimmed = stripTrailingEmptyLines(oldString)
        if (trimmed != oldString && trimmed.isNotEmpty()) {
            countExact(fileContent, trimmed).let { count ->
                if (count > 0) return MatchResult(trimmed, count, pass = 6)
            }
            findWithNormalizedChars(fileContent, trimmed)?.let {
                return it.copy(pass = 6)
            }
            findLineBasedMatch(fileContent, trimmed)?.let {
                return it.copy(pass = 6)
            }
        }

        return null
    }

    // ── Pass 2: Unicode normalization ──

    private fun findWithNormalizedChars(fileContent: String, oldString: String): MatchResult? {
        val normalizedFile = normalizeUnicodeChars(fileContent)
        val normalizedOld = normalizeUnicodeChars(oldString)

        if (normalizedFile == fileContent && normalizedOld == oldString) return null

        val count = countExact(normalizedFile, normalizedOld)
        if (count == 0) return null

        val actualMatch = extractOriginalMatch(fileContent, normalizedFile, normalizedOld)
            ?: return null

        return MatchResult(actualMatch, count, pass = 2)
    }

    /**
     * Finds the original (pre-normalization) substring in fileContent
     * that corresponds to normalizedOld in normalizedFile.
     */
    private fun extractOriginalMatch(
        fileContent: String,
        normalizedFile: String,
        normalizedOld: String,
    ): String? {
        val normIndex = normalizedFile.indexOf(normalizedOld)
        if (normIndex == -1) return null

        // Build a mapping from normalized char index → original char index
        // Since normalization is char-by-char replacement (same length), indices align directly.
        val endIndex = normIndex + normalizedOld.length
        if (endIndex > fileContent.length) return null

        return fileContent.substring(normIndex, endIndex)
    }

    // ── Pass 3-5: Line-based matching ──

    private fun findLineBasedMatch(fileContent: String, oldString: String): MatchResult? {
        val fileLines = fileContent.lines()
        val oldLines = oldString.lines()

        if (oldLines.isEmpty()) return null

        // Pass 3: Line-by-line exact identity
        findLinesMatch(fileLines, oldLines) { a, b -> a == b }?.let { (start, end) ->
            val matched = fileLines.subList(start, end).joinToString("\n")
            val count = countLineMatches(fileLines, oldLines) { a, b -> a == b }
            return MatchResult(matched, count, pass = 3)
        }

        // Pass 4: Line-by-line trimEnd
        findLinesMatch(fileLines, oldLines) { a, b -> a.trimEnd() == b.trimEnd() }?.let { (start, end) ->
            val matched = fileLines.subList(start, end).joinToString("\n")
            val count = countLineMatches(fileLines, oldLines) { a, b -> a.trimEnd() == b.trimEnd() }
            return MatchResult(matched, count, pass = 4)
        }

        // Pass 5: normalizeChars + trimEnd
        findLinesMatch(fileLines, oldLines) { a, b ->
            normalizeUnicodeChars(a).trimEnd() == normalizeUnicodeChars(b).trimEnd()
        }?.let { (start, end) ->
            val matched = fileLines.subList(start, end).joinToString("\n")
            val count = countLineMatches(fileLines, oldLines) { a, b ->
                normalizeUnicodeChars(a).trimEnd() == normalizeUnicodeChars(b).trimEnd()
            }
            return MatchResult(matched, count, pass = 5)
        }

        return null
    }

    /**
     * Finds the first contiguous range in [fileLines] that matches [oldLines] using [comparator].
     * Returns (startIndex, endIndex) exclusive, or null.
     */
    private fun findLinesMatch(
        fileLines: List<String>,
        oldLines: List<String>,
        comparator: (String, String) -> Boolean,
    ): Pair<Int, Int>? {
        val searchLen = oldLines.size
        if (searchLen > fileLines.size) return null

        outer@ for (i in 0..fileLines.size - searchLen) {
            for (j in oldLines.indices) {
                if (!comparator(fileLines[i + j], oldLines[j])) continue@outer
            }
            return i to (i + searchLen)
        }
        return null
    }

    /**
     * Counts how many non-overlapping contiguous matches of [oldLines] exist in [fileLines].
     */
    private fun countLineMatches(
        fileLines: List<String>,
        oldLines: List<String>,
        comparator: (String, String) -> Boolean,
    ): Int {
        val searchLen = oldLines.size
        if (searchLen > fileLines.size) return 0

        var count = 0
        var i = 0
        outer@ while (i <= fileLines.size - searchLen) {
            for (j in oldLines.indices) {
                if (!comparator(fileLines[i + j], oldLines[j])) {
                    i++
                    continue@outer
                }
            }
            count++
            i += searchLen
        }
        return count
    }

    // ── Utilities ──

    private fun countExact(source: String, substr: String): Int {
        if (substr.isEmpty()) return 0
        var count = 0
        var index = source.indexOf(substr)
        while (index != -1) {
            count++
            index = source.indexOf(substr, index + substr.length)
        }
        return count
    }

    /**
     * Normalizes common unicode variants to ASCII equivalents.
     * Designed to be a same-length, char-by-char replacement so index mapping is trivial.
     */
    internal fun normalizeUnicodeChars(text: String): String {
        val sb = StringBuilder(text.length)
        for (ch in text) {
            sb.append(
                when (ch) {
                    '\u201C', '\u201D' -> '"'    // "" → "
                    '\u2018', '\u2019' -> '\''   // '' → '
                    '\u2014' -> '-'              // — (em dash) → -
                    '\u2013' -> '-'              // – (en dash) → -
                    '\u00A0' -> ' '              // NBSP → space
                    '\u2026' -> '.'              // … → . (single dot, keeps length different — handled below)
                    '\u00B7' -> '.'              // · → .
                    '\u2022' -> '*'              // • → *
                    else -> ch
                }
            )
        }
        return sb.toString()
    }

    private fun stripTrailingEmptyLines(text: String): String {
        return text.trimEnd('\n', '\r')
    }
}
