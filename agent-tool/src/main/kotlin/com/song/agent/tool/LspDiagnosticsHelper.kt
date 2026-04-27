package com.song.agent.tool

import com.song.lsp.LspClient
import com.song.lsp.LspDiagnosticSeverity
import org.eclipse.lsp4j.Diagnostic

private const val MAX_DIAGNOSTICS_REPORTED = 20

internal suspend fun runPostEditDiagnostics(filePath: String, lspClient: LspClient?): String {
    if (lspClient == null || !lspClient.initialized) return ""
    val diags = try {
        lspClient.diagnostics(filePath)
    } catch (e: Exception) {
        println("Error during LSP diagnostics: ${e.message}")
        return ""
    }

    return formatDiagnosticsSummary(diags)
}

private fun formatDiagnosticsSummary(diags: List<Diagnostic>): String {
    println(diags.joinToString("\n"))

    if (diags.isEmpty()) return "\n\n[diagnostics] no issues"
    return buildString {
        append("\n\n[diagnostics] ")
        append(diags.size)
        appendLine(" issue(s):")
        diags.take(MAX_DIAGNOSTICS_REPORTED).forEachIndexed { i, diag ->
            val severity = LspDiagnosticSeverity.nameOf(diag.severity)
            val position = "${diag.range.start.line + 1}:${diag.range.start.character + 1}"
            val code = diag.code?.get()?.let { " ($it)" } ?: ""
            val source = if (diag.source != null) " [${diag.source}]" else ""
            appendLine("${i + 1}. [${severity.uppercase()}] $position$code$source: ${diag.message}")
        }
        if (diags.size > MAX_DIAGNOSTICS_REPORTED) {
            append("(${diags.size - MAX_DIAGNOSTICS_REPORTED} more)")
        }
    }.trimEnd()
}
