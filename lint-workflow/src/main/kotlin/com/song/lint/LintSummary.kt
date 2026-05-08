package com.song.lint

import com.song.sarif.Finding

class LintSummaryPrinter {
    fun print(context: LintRunContext, findings: List<Finding>) {
        println("Lint task=${context.task}")
        if (findings.isEmpty()) {
            println("No findings reported by lint.")
            return
        }

        val byLevel = findings.groupingBy { it.level ?: "unspecified" }.eachCount()
        val byRule = findings.groupingBy { it.ruleId }.eachCount()
            .entries
            .sortedByDescending { it.value }
            .take(5)
            .joinToString(", ") { "${it.key}=${it.value}" }
        println("By level: ${byLevel.entries.sortedBy { it.key }.joinToString(", ") { "${it.key}=${it.value}" }}")
        println("Top rules: $byRule")
    }
}
