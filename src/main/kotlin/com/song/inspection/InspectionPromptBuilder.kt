package com.song.inspection

internal class InspectionPromptBuilder {
    fun build(path: String, findings: List<InspectionFinding>): String {
        val findingsByRule = findings
            .groupBy { it.ruleId.takeIf { id -> id.isNotBlank() } ?: "unknown" }
            .entries
            .sortedByDescending { (_, ruleFindings) ->
                ruleFindings.maxOf { it.startLine ?: 0 }
            }

        val blocks = findingsByRule.joinToString("\n\n") { (ruleId, ruleFindings) ->
            val lines = ruleFindings
                .sortedByDescending { it.startLine ?: 0 }
                .joinToString("\n") { finding ->
                    val location = "${finding.startLine ?: "?"}:${finding.startColumn ?: "?"}"
                    val message = finding.message ?: "no message"
                    "  - $location: $message"
                }
            "[$ruleId]\n$lines"
        }

        return """
$path

$blocks
""".trimIndent()
    }
}
