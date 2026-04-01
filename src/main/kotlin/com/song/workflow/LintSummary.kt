package com.song.workflow

import com.song.sarif.Finding

internal class LintSummaryPrinter {
    fun print(context: LintRunContext, findings: List<Finding>) {
        println("Module: ${context.module} task=${context.task}")
        if (findings.isEmpty()) {
            println("No findings reported by detekt.")
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
