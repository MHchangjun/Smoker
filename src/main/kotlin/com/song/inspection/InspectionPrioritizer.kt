package com.song.inspection

internal class InspectionPrioritizer {
    fun prioritize(findings: List<InspectionFinding>): List<InspectionFinding> {
        return findings.sortedWith(priorityComparator)
    }

    private val priorityComparator = compareBy<InspectionFinding>(
        { domainRank(it) },
        { severityRank(it.level) },
        { it.absolutePath ?: "" },
        { it.startLine ?: Int.MAX_VALUE },
        { it.startColumn ?: Int.MAX_VALUE },
        { it.ruleId }
    )

    private fun domainRank(finding: InspectionFinding): Int {
        val ruleId = finding.ruleId
        return when {
            isProofreading(ruleId) -> 5
            isDeadCode(ruleId) -> 0
            isCorrectness(ruleId, finding.level) -> 1
            isRefactor(ruleId) -> 2
            isStyle(ruleId, finding.level) -> 3
            else -> 4
        }
    }

    private fun isDeadCode(ruleId: String): Boolean {
        return DEAD_CODE_RULE_KEYWORDS.any { keyword -> ruleId.contains(keyword, ignoreCase = true) }
    }

    private fun isCorrectness(ruleId: String, level: String?): Boolean {
        if (level.equals("ERROR", ignoreCase = true)) return true
        return CORRECTNESS_RULE_KEYWORDS.any { keyword -> ruleId.contains(keyword, ignoreCase = true) }
    }

    private fun isRefactor(ruleId: String): Boolean {
        return REFACTOR_RULE_KEYWORDS.any { keyword -> ruleId.contains(keyword, ignoreCase = true) }
    }

    private fun isStyle(ruleId: String, level: String?): Boolean {
        if (level.equals("WEAK WARNING", ignoreCase = true) || level.equals("STYLE_SUGGESTION", ignoreCase = true)) {
            return true
        }
        return STYLE_RULE_KEYWORDS.any { keyword -> ruleId.contains(keyword, ignoreCase = true) }
    }

    private fun isProofreading(ruleId: String): Boolean {
        return PROOFREADING_RULE_KEYWORDS.any { keyword -> ruleId.contains(keyword, ignoreCase = true) }
    }

    private fun severityRank(level: String?): Int {
        return when (level?.uppercase()) {
            "ERROR" -> 0
            "WARNING" -> 1
            "WEAK WARNING" -> 2
            "STYLE_SUGGESTION" -> 3
            "INFORMATION" -> 4
            "TYPO" -> 5
            "GRAMMAR_ERROR" -> 6
            else -> 7
        }
    }

    private companion object {
        val DEAD_CODE_RULE_KEYWORDS = listOf(
            "Unused",
            "Redundant",
            "Unnecessary",
            "DeprecatedIsStillUsed",
            "CheckTagEmptyBody"
        )

        val CORRECTNESS_RULE_KEYWORDS = listOf(
            "Unresolved",
            "Undefined",
            "Highlighting",
            "Annotator",
            "SchemaValidation",
            "Compliance",
            "Assignability",
            "RequiredLangAttribute",
            "UnknownAnchorTarget",
            "Vulnerable"
        )

        val REFACTOR_RULE_KEYWORDS = listOf(
            "ConfigurationAvoidance",
            "UseCompareMethod",
            "IfCanBeSwitch",
            "EnhancedSwitchMigration",
            "BackwardMigration",
            "Bitwise",
            "ShiftOutOfRange",
            "Mask"
        )

        val STYLE_RULE_KEYWORDS = listOf(
            "LocalVariableName",
            "Style",
            "Naming"
        )

        val PROOFREADING_RULE_KEYWORDS = listOf(
            "SpellCheckingInspection",
            "Grazie"
        )
    }
}
