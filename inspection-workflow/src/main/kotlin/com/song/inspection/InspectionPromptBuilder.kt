package com.song.inspection

import com.song.sarif.Finding
import java.io.File

class InspectionPromptBuilder {
    fun build(path: String, findings: List<Finding>): String {
        val file = File(path)
        val fileLines = runCatching { file.readLines() }.getOrDefault(emptyList())
        val ruleId = findings.firstOrNull()?.ruleId.orEmpty()
        val plural = findings.size > 1
        val issueWord = if (plural) "issues" else "issue"
        val findingsHeader = if (plural) "## Findings" else "## Finding"

        return buildString {
            appendLine("Fix the following IDE inspection $issueWord in `$path`.")
            appendLine()
            appendLine("**Inspection:** $ruleId")
            appendLine()
            policyFor(ruleId)?.let {
                appendLine("## Fix policy")
                appendLine()
                appendLine(it)
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

    private fun policyFor(ruleId: String): String? {
        val rule = InspectionRule.fromId(ruleId) ?: return null
        return when (rule) {
            InspectionRule.UNUSED_SYMBOL -> """
Delete the declaration. The IDE inspection has already excluded all standard exemptions — if the symbol is in the findings, it is unused.

### Verify

1. `lsp findReferences` on the symbol with `includeDeclaration: false`.
   - 0 results → proceed.
   - ≥1 result → keep.
2. Grep the repo for the symbol name in non-Kotlin sources (XML, resource files, Gradle scripts).
   - Any match → keep.
   - No match → proceed.

### Action

- Both checks pass → delete the declaration, remove imports that become unused, delete the file if it becomes empty.
- Otherwise → skip.
            """.trimIndent()
            InspectionRule.UNUSED_VARIABLE -> """
Delete the unused local variable, parameter, or property only when doing so does not change side effects.

### Verify

1. If the initializer calls a function, reads mutable state, allocates an object with side effects, or performs I/O, keep the expression or rewrite it so side effects remain.
2. If the variable is part of a public/callback signature, skip.

### Action

- No side effects and not API-significant → remove the declaration.
- Side effects exist → remove only the unused binding and preserve the expression if needed.
            """.trimIndent()
            InspectionRule.UNUSED_EXPRESSION -> """
Remove the unused expression only when it has no side effects.

### Verify

1. Pure literal, name reference, or simple calculation → safe to remove.
2. Function calls, property accessors, assignments, increments, object construction, logging, metrics, or I/O may have side effects → skip unless the intended safe rewrite is obvious.

### Action

- Safe expression → delete it.
- Unclear side effects → skip.
            """.trimIndent()
            InspectionRule.CAN_BE_VAL -> """
Change `var` to `val`.

### Verify

1. Confirm the inspection points to a variable that is not reassigned.
2. Do not change generated code or public API solely for style if the file indicates code generation.

### Action

- Replace `var` with `val`.
            """.trimIndent()
            InspectionRule.REDUNDANT_SEMICOLON -> """
Remove the redundant semicolon.

### Action

- Delete only the semicolon reported by the inspection.
            """.trimIndent()
            InspectionRule.REDUNDANT_UNIT_RETURN_TYPE -> """
Remove the explicit `: Unit` return type.

### Verify

1. Keep explicit `: Unit` in public API declarations if the surrounding style intentionally documents API shape.
2. Otherwise remove it.

### Action

- Delete `: Unit` and leave the body unchanged.
            """.trimIndent()
            InspectionRule.REMOVE_EMPTY_CLASS_BODY -> """
Remove the empty class body.

### Action

- Replace an empty `{}` body with no body.
            """.trimIndent()
        }
    }

    private fun extractCodeSnippet(fileLines: List<String>, finding: Finding): String {
        val startLine = finding.startLine ?: return ""
        if (fileLines.isEmpty()) return ""
        val endLine = finding.endLine ?: startLine
        val start = (startLine - 1).coerceIn(0, fileLines.lastIndex)
        val end = (endLine - 1).coerceIn(start, fileLines.lastIndex)
        val code = fileLines.subList(start, end + 1).joinToString("\n")
        return "```kotlin\n$code\n```"
    }
}
