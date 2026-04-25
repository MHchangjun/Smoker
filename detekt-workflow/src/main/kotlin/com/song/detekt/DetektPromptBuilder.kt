package com.song.detekt

import com.song.sarif.Finding
import java.io.File

class DetektPromptBuilder {
    fun build(path: String, findings: List<Finding>): String {
        val fileLines = File(path).readLines()

        val blocks = findings
            .sortedByDescending { it.startLine ?: 0 }
            .joinToString("\n\n") { finding ->
                val ruleId = finding.ruleId.takeIf { it.isNotBlank() } ?: "unknown"
                val line = finding.startLine?.let { " line $it:" } ?: ""
                val message = finding.message ?: "no message"
                val code = extractCodeSnippet(fileLines, finding)
                "[$ruleId]$line $message\n\n$code"
            }

        return """
$path

$blocks
""".trimIndent()
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