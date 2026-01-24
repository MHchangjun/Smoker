package com.song.detekt

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name

data class DetektConfigContext(
    val path: Path,
    val lines: List<String>
) {
    fun relevantConfigEntries(ruleId: String): List<String> {
        val entries = mutableListOf<String>()
        val rulePath = parseRulePath(ruleId)
        if (rulePath != null) {
            val sectionIndex = findRuleLineIndex(rulePath.section)
            if (sectionIndex != null) {
                val sectionEnd = blockEndIndex(sectionIndex)
                val ruleIndex = findRuleLineIndex(
                    rulePath.rule,
                    start = sectionIndex + 1,
                    end = sectionEnd + 1
                )
                if (ruleIndex != null) {
                    val ruleEnd = blockEndIndex(ruleIndex)
                    entries += entriesFromBlock(
                        startIndex = ruleIndex,
                        endIndex = ruleEnd,
                        prefix = listOf(rulePath.section)
                    )
                }
            }
        }

        if (entries.isEmpty()) {
            for (candidate in ruleIdCandidates(ruleId)) {
                val index = findRuleLineIndex(candidate) ?: continue
                val endIndex = blockEndIndex(index)
                entries += entriesFromBlock(startIndex = index, endIndex = endIndex, prefix = emptyList())
                if (entries.isNotEmpty()) break
            }
        }

        val configValidation = findScalarAtPath(listOf("config", "validation"))
        if (configValidation != null) {
            entries += "config.validation: $configValidation"
        }

        return entries.distinct()
    }

    fun ruleSnippet(ruleId: String): String? {
        val rulePath = parseRulePath(ruleId)
        if (rulePath != null) {
            val sectionIndex = findRuleLineIndex(rulePath.section)
            if (sectionIndex != null) {
                val sectionEnd = blockEndIndex(sectionIndex)
                val ruleIndex = findRuleLineIndex(
                    rulePath.rule,
                    start = sectionIndex + 1,
                    end = sectionEnd + 1
                )
                if (ruleIndex != null) {
                    return buildSnippet(ruleIndex, explicitParentIndex = sectionIndex)
                }
            }
        }

        for (candidate in ruleIdCandidates(ruleId)) {
            val index = findRuleLineIndex(candidate) ?: continue
            return buildSnippet(index)
        }

        return null
    }

    private fun leadingSpaces(line: String): Int {
        var count = 0
        for (ch in line) {
            if (ch != ' ') break
            count++
        }
        return count
    }

    private fun ruleIdCandidates(ruleId: String): List<String> {
        val parts = mutableListOf<String>()
        parts += ruleId
        val lastColon = ruleId.substringAfterLast(':', "")
        if (lastColon.isNotBlank()) parts += lastColon
        val lastSlash = ruleId.substringAfterLast('/', "")
        if (lastSlash.isNotBlank()) parts += lastSlash
        val lastDot = ruleId.substringAfterLast('.', "")
        if (lastDot.isNotBlank()) parts += lastDot
        return parts.distinct()
    }

    private data class RulePath(
        val section: String,
        val rule: String
    )

    private fun parseRulePath(ruleId: String): RulePath? {
        val parts = ruleId.split(':', limit = 2)
        if (parts.size != 2) return null
        val section = parts[0].trim()
        val rule = parts[1].trim()
        if (section.isBlank() || rule.isBlank()) return null
        return RulePath(section, rule)
    }

    private fun findRuleLineIndex(ruleId: String, start: Int = 0, end: Int = lines.size): Int? {
        if (start >= end) return null
        for (i in start until end) {
            val trimmed = lines[i].trimStart()
            if (trimmed.startsWith("$ruleId:")) {
                return i
            }
        }
        return null
    }

    private fun blockEndIndex(startIndex: Int): Int {
        val baseIndent = leadingSpaces(lines[startIndex])
        var end = startIndex
        for (i in startIndex + 1 until lines.size) {
            val line = lines[i]
            if (line.isBlank()) {
                end = i
                continue
            }
            val indent = leadingSpaces(line)
            val trimmed = line.trimStart()
            if (indent <= baseIndent && !trimmed.startsWith("#")) {
                break
            }
            end = i
        }
        return end
    }

    private fun buildSnippet(ruleIndex: Int, explicitParentIndex: Int? = null): String {
        val ruleBlock = extractBlock(ruleIndex)
        val parentIndex = explicitParentIndex ?: findParentIndex(ruleIndex)
        if (parentIndex == null || parentIndex == ruleIndex) return ruleBlock
        val parentLine = lines[parentIndex]
        return listOf(parentLine, ruleBlock).joinToString("\n").trimEnd()
    }

    private fun extractBlock(startIndex: Int): String {
        val endIndex = blockEndIndex(startIndex)
        return lines.subList(startIndex, endIndex + 1).joinToString("\n").trimEnd()
    }

    private fun findParentIndex(childIndex: Int): Int? {
        val childIndent = leadingSpaces(lines[childIndex])
        for (i in childIndex - 1 downTo 0) {
            val line = lines[i]
            if (line.isBlank()) continue
            val trimmed = line.trimStart()
            if (trimmed.startsWith("#")) continue
            val indent = leadingSpaces(line)
            if (indent < childIndent && trimmed.endsWith(":")) {
                return i
            }
        }
        return null
    }

    private fun entriesFromBlock(
        startIndex: Int,
        endIndex: Int,
        prefix: List<String>
    ): List<String> {
        val entries = mutableListOf<String>()
        val stack = mutableListOf<KeyFrame>()
        val baseIndent = leadingSpaces(lines[startIndex])

        for (i in startIndex..endIndex) {
            val line = lines[i]
            if (line.isBlank()) continue
            val trimmed = line.trimStart()
            if (trimmed.startsWith("#")) continue
            val indent = leadingSpaces(line)
            if (indent < baseIndent) continue

            while (stack.isNotEmpty() && stack.last().indent >= indent) {
                stack.removeAt(stack.lastIndex)
            }

            val keyValue = parseKeyValue(trimmed) ?: continue
            val (key, value) = keyValue
            if (value == null || value.isBlank()) {
                stack.add(KeyFrame(indent, key))
                continue
            }
            val path = prefix + stack.map { it.key } + key
            entries += path.joinToString(".") + ": " + value
        }

        return entries
    }

    private fun findScalarAtPath(path: List<String>): String? {
        val stack = mutableListOf<KeyFrame>()
        for (line in lines) {
            if (line.isBlank()) continue
            val trimmed = line.trimStart()
            if (trimmed.startsWith("#")) continue
            val indent = leadingSpaces(line)

            while (stack.isNotEmpty() && stack.last().indent >= indent) {
                stack.removeAt(stack.lastIndex)
            }

            val keyValue = parseKeyValue(trimmed) ?: continue
            val (key, value) = keyValue
            if (value == null || value.isBlank()) {
                stack.add(KeyFrame(indent, key))
                continue
            }

            val fullPath = stack.map { it.key } + key
            if (fullPath == path) return value
        }
        return null
    }

    private fun parseKeyValue(trimmed: String): Pair<String, String?>? {
        val colonIndex = trimmed.indexOf(':')
        if (colonIndex <= 0) return null
        val key = trimmed.substring(0, colonIndex).trim()
        if (key.isEmpty()) return null
        val value = trimmed.substring(colonIndex + 1).trim()
        return key to value.ifEmpty { null }
    }

    private data class KeyFrame(
        val indent: Int,
        val key: String
    )
}

fun loadDetektConfig(projectRoot: Path): DetektConfigContext? {
    val configPath = findDetektConfigFile(projectRoot) ?: return null
    if (!Files.exists(configPath)) return null
    val lines = Files.readAllLines(configPath)
    return DetektConfigContext(configPath, lines)
}

private fun findDetektConfigFile(projectRoot: Path): Path? {
    val candidates = listOf(
        projectRoot.resolve("detekt.yml"),
        projectRoot.resolve("detekt.yaml"),
        projectRoot.resolve("config/detekt/detekt.yml"),
        projectRoot.resolve("config/detekt/detekt.yaml"),
        projectRoot.resolve("config/detekt.yml"),
        projectRoot.resolve("config/detekt.yaml")
    )
    candidates.firstOrNull { Files.exists(it) }?.let { return it }

    return runCatching {
        Files.walk(projectRoot, 5).use { stream ->
            stream
                .filter { Files.isRegularFile(it) }
                .filter { it.name == "detekt.yml" || it.name == "detekt.yaml" }
                .findFirst()
                .orElse(null)
        }
    }.getOrNull()
}
