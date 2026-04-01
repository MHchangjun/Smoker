package com.song.workflow

import com.song.detekt.DetektConfigContext
import com.song.sarif.Finding

internal class LintPromptBuilder {
    fun build(path: String, findings: List<Finding>, detektConfig: DetektConfigContext?): String {
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
                .joinToString("\n") { finding ->
                    val location = "${finding.startLine ?: "?"}:${finding.startColumn ?: "?"}"
                    val message = finding.message ?: "no message"
                    "  - $location: $message"
                }
            "[$ruleId]$configSnippet\n$lines"
        }

        return """
$path

$blocks
""".trimIndent()
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
