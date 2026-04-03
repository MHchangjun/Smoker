package com.song.inspection

class InspectionFilter {
    fun apply(findings: List<InspectionFinding>): List<InspectionFinding> {
        return findings.filterNot(::shouldExclude)
    }

    private fun shouldExclude(finding: InspectionFinding): Boolean {
        val ruleId = finding.ruleId
        val message = finding.message.orEmpty()
        val path = finding.absolutePath.orEmpty()

        if (EXCLUDED_RULE_IDS.any { it.equals(ruleId, ignoreCase = true) }) return true
        if (EXCLUDED_RULE_PREFIXES.any { ruleId.startsWith(it, ignoreCase = true) }) return true
        if (EXCLUDED_RULE_KEYWORDS.any { ruleId.contains(it, ignoreCase = true) }) return true
        if (EXCLUDED_MESSAGE_KEYWORDS.any { message.contains(it, ignoreCase = true) }) return true
        if (EXCLUDED_PATH_PATTERNS.any { path.contains(it, ignoreCase = true) }) return true
        if (finding.level.equals("TYPO", ignoreCase = true)) return true
        if (finding.level.equals("GRAMMAR_ERROR", ignoreCase = true)) return true

        return false
    }

    private companion object {
        val EXCLUDED_RULE_IDS = setOf(
            "XmlHighlighting",
            "Annotator",
            "SpellCheckingInspection",
            "GrazieInspection",
            "GrazieStyle",
            "YAMLSchemaValidation",
            "UndefinedAction",
            "UndefinedParamsPresent",
            "VulnerableLibrariesLocal",
            "HttpUrlsUsage"
        )

        val EXCLUDED_RULE_PREFIXES = setOf(
            "JS",
            "Css",
            "Html",
            "Json",
            "Gr"
        )

        val EXCLUDED_RULE_KEYWORDS = setOf(
            "SchemaValidation",
            "Proofreading",
            "Grazie",
            "Spell",
            "Xml",
            "Yaml",
            "Markdown",
            "GitHub",
            "URI"
        )

        val EXCLUDED_MESSAGE_KEYWORDS = setOf(
            "URI is not registered",
            "Unbound namespace prefix",
            "Typo:",
            "grammar",
            "Spelling:",
            "Unknown HTML tag",
            "Unknown HTTP header",
            "Incorrect MIME Type"
        )

        val EXCLUDED_PATH_PATTERNS = setOf(
            "/src/main/res/",
            "/src/test/resources/",
            "/src/androidTest/resources/",
            ".github/workflows/",
            ".github/payload/"
        )
    }
}
