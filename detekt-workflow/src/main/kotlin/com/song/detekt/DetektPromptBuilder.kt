package com.song.detekt

import com.song.sarif.Finding
import java.io.File

class DetektPromptBuilder {
    fun build(path: String, findings: List<Finding>, detektConfig: DetektConfigContext?): String {
        val fileLines = File(path).readLines()

        val findingsByRule = findings
            .groupBy { it.ruleId.takeIf { id -> id.isNotBlank() } ?: "unknown" }
            .entries
            .sortedByDescending { (_, ruleFindings) ->
                ruleFindings.maxOf { it.startLine ?: 0 }
            }

        val blocks = findingsByRule.joinToString("\n\n") { (ruleId, ruleFindings) ->
            val configSnippet = buildConfigLine(ruleFindings.first(), detektConfig)
            val lines = ruleFindings
                .sortedByDescending { it.startLine ?: 0 }
                .joinToString("\n\n") { finding ->
                    val message = finding.message ?: "no message"
                    val codeSnippet = extractCodeSnippet(fileLines, finding)
                    "  - $message\n$codeSnippet"
                }
            "[$ruleId]$configSnippet\n$lines"
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
        return "    ```kotlin\n    $code\n    ```"
    }

    private fun buildConfigLine(
        finding: Finding,
        detektConfig: DetektConfigContext?
    ): String {
        if (detektConfig == null) return ""
        val ruleId = finding.ruleId.takeIf { it.isNotBlank() } ?: return ""
        val snippet = detektConfig.ruleSnippet(ruleId) ?: return ""
        return "\n  ```yaml\n  $snippet\n  ```"
    }
}
