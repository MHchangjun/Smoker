package com.song.detekt

import com.song.sarif.Finding
import java.io.File

class DetektPromptBuilder {
    fun build(path: String, findings: List<Finding>): String {
        val fileLines = File(path).readLines()
        val ruleId = findings.firstOrNull()?.ruleId.orEmpty()
        val plural = findings.size > 1
        val issueWord = if (plural) "issues" else "issue"
        val findingsHeader = if (plural) "**Findings:**" else "**Finding:**"

        return buildString {
            appendLine("Fix the following code smell $issueWord in `$path`.")
            appendLine()
            policyFor(ruleId)?.let {
                appendLine("**Fix policy:** $it")
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

    private fun policyFor(ruleId: String): String? = when {
        ruleId.endsWith("PrintStackTrace") ->
            "Remove the `e.printStackTrace()` call entirely. Do NOT replace it with any logger. " +
                "If the catch block becomes empty, rename the exception variable to `_`."
        ruleId.endsWith("ComplexCondition") ->
            "Extract the condition into a private function. " +
                "Do NOT split into multiple local boolean variables."
        ruleId.endsWith("EmptyIfBlock") ->
            "Remove the entire `if` block. If the condition contains function calls with side effects, " +
                "extract those calls before the `if` and remove the `if` block afterward. " +
                "Do NOT leave empty `if` blocks with a comment."
        else -> null
    }

    private fun extractCodeSnippet(fileLines: List<String>, finding: Finding): String {
        val startLine = finding.startLine ?: return ""
        val endLine = finding.endLine ?: startLine
        val start = (startLine - 1).coerceIn(0, fileLines.lastIndex)
        val end = (endLine - 1).coerceIn(start, fileLines.lastIndex)
        val code = fileLines.subList(start, end + 1).joinToString("\n")
        return "```kotlin\n$code\n```"
    }
}