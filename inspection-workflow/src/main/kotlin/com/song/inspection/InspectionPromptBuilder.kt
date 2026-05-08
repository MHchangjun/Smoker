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

    private fun policyFor(ruleId: String): String? = when (ruleId) {
        "UnusedSymbol" -> """
            Delete the declaration only after confirming it is genuinely unreachable. This
            inspection has known false positives from XML references, annotation processors,
            and cross-module callers.

            ### Verify (in order; stop on any keep signal)

            1. `lsp findReferences` on the symbol with `includeDeclaration: false`.
               Any result → keep.

            2. Quick signal check on the declaration itself — if any of these, keep:
               - Annotation implying runtime/external use: `@Keep`, `@Inject`, `@Provides`,
                 `@Binds`, `@Module`, `@HiltViewModel`, `@AndroidEntryPoint`, `@Serializable`,
                 `@SerializedName`, `@Json`, `@JvmField`, `@JvmStatic`, `@JvmName`,
                 `@VisibleForTesting`, `@Composable` with `@Preview`, or any KSP/kapt
                 annotation used in this project.
               - `override`, `operator`, satisfies an interface/abstract member, or is a
                 sealed subtype.
               - Entry point (manifest-declared, top-level `main`, JNI `external`).
               - `public`/`internal` declaration in a module consumed by other modules
                 (LSP index may not span all callers).

            3. `grep` for the symbol's simple name in non-Kotlin sources only:
               `**/*.xml`, `**/*.gradle{,.kts}`, `**/*.toml`, `**/proguard-*.pro`.
               Any hit that plausibly resolves to this symbol → keep.

            ### Action

            - All three pass → delete the declaration, remove imports that become unused,
              delete the file if it becomes empty.
            - Anything uncertain → skip the finding. Do NOT add `@Suppress`.

            ### After editing

            Re-run LSP diagnostics on the edited file. Diagnostic set must be empty.
        """.trimIndent()
        else -> null
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