package com.song.lint

import com.song.sarif.Finding
import java.io.File

class LintPromptBuilder {
    fun build(path: String, findings: List<Finding>): String {
        val file = File(path)
        val fileLines = runCatching { file.readLines() }.getOrDefault(emptyList())
        val ruleId = findings.firstOrNull()?.ruleId.orEmpty()
        val plural = findings.size > 1
        val issueWord = if (plural) "issues" else "issue"
        val findingsHeader = if (plural) "**Findings:**" else "**Finding:**"
        val language = languageHint(path)

        return buildString {
            appendLine("Fix the following Android Lint $issueWord in `$path`.")
            appendLine()
            appendLine("**Rule:** $ruleId")
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
                    appendLine(extractCodeSnippet(fileLines, finding, language))
                }
        }.trimEnd()
    }

    private fun policyFor(ruleId: String): String? = when (ruleId) {
        "NewApi", "InlinedApi" ->
            "Wrap the call in `if (Build.VERSION.SDK_INT >= ...)` or use the AndroidX/Compat alternative. " +
                "Do NOT raise the module's `minSdk`."
        "ObsoleteSdkInt" ->
            "The `Build.VERSION.SDK_INT` guard is unnecessary because minSdk already covers it. " +
                "Remove the `if` and keep only the body of the satisfied branch."
        "UnusedResources" ->
            "Delete the unused resource definition. If you suspect cross-module usage, add `tools:keep` " +
                "to a `<resources>` element instead of suppressing."
        "HardcodedText" ->
            "Move the literal into `res/values/strings.xml` and reference it via `@string/...`. " +
                "Pick a snake_case identifier matching the existing strings.xml conventions."
        "TypographyDashes", "TypographyEllipsis", "TypographyQuotes" ->
            "Replace the ASCII characters with the typographic equivalents directly in the resource value."
        "SetTextI18n" ->
            "Move the literal text into `strings.xml` and replace `setText(\"...\")` with `setText(getString(R.string.X))`."
        "Deprecation" ->
            "Replace the deprecated API with its recommended replacement. Confirm the replacement exists at " +
                "the module's compileSdk before substituting."
        "ContentDescription" ->
            "Add an `android:contentDescription` attribute. If the view is decorative, set it to `@null` " +
                "and add `tools:ignore=\"ContentDescription\"` only as a last resort."
        else -> null
    }

    private fun languageHint(path: String): String = when (File(path).extension.lowercase()) {
        "kt", "kts" -> "kotlin"
        "java" -> "java"
        "xml" -> "xml"
        "gradle" -> "groovy"
        else -> ""
    }

    private fun extractCodeSnippet(fileLines: List<String>, finding: Finding, language: String): String {
        val startLine = finding.startLine ?: return ""
        if (fileLines.isEmpty()) return ""
        val endLine = finding.endLine ?: startLine
        val start = (startLine - 1).coerceIn(0, fileLines.lastIndex)
        val end = (endLine - 1).coerceIn(start, fileLines.lastIndex)
        val code = fileLines.subList(start, end + 1).joinToString("\n")
        return "```$language\n$code\n```"
    }
}
