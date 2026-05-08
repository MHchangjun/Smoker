package com.song.inspection

import com.song.sarif.Finding
import java.io.File

class InspectionPromptBuilder {
    fun build(path: String, findings: List<Finding>): String {
        val file = File(path)
        val fileLines = runCatching { file.readLines() }.getOrDefault(emptyList())
        val ruleId = findings.firstOrNull()?.ruleId.orEmpty()
        val plural = findings.size > 1
        val issueWord = if (plural) "issues" else "issue"
        val findingsHeader = if (plural) "## Findings" else "## Finding"

        return buildString {
            appendLine("Fix the following IDE inspection $issueWord in `$path`.")
            appendLine()
            appendLine("**Inspection:** $ruleId")
            appendLine()
            policyFor(ruleId)?.let {
                appendLine("## Fix policy")
                appendLine()
                appendLine(it)
                appendLine()
            }
            appendLine(findingsHeader)
            findings
                .sortedByDescending { it.startLine ?: 0 }
                .forEach { finding ->
                    appendLine()
                    val line = finding.startLine?.let { "line $it" } ?: "unknown line"
                    val message = finding.message ?: "no message"
                    appendLine("- **$line:** $message")
                    appendLine()
                    appendLine(extractCodeSnippet(fileLines, finding))
                }
        }.trimEnd()
    }

    private fun policyFor(ruleId: String): String? = when (ruleId) {
        "UnusedSymbol" -> """
Delete the declaration. The IDE inspection has already excluded all standard exemptions — if the symbol is in the findings, it is unused.

### Verify

1. `lsp findReferences` on the symbol with `includeDeclaration: false`.
   - 0 results → proceed.
   - ≥1 result → keep.
2. Grep the repo for the symbol name in non-Kotlin sources (XML, resource files, Gradle scripts).
   - Any match → keep.
   - No match → proceed.

### Action

- Both checks pass → delete the declaration, remove imports that become unused, delete the file if it becomes empty.
- Otherwise → skip.
        """.trimIndent()
        else -> null
    }

    private fun extractCodeSnippet(fileLines: List<String>, finding: Finding): String {
        val startLine = finding.startLine ?: return ""
        if (fileLines.isEmpty()) return ""
        val endLine = finding.endLine ?: startLine
        val start = (startLine - 1).coerceIn(0, fileLines.lastIndex)
        val end = (endLine - 1).coerceIn(start, fileLines.lastIndex)
        val code = fileLines.subList(start, end + 1).joinToString("\n")
        return "```kotlin\n$code\n```"
    }
}