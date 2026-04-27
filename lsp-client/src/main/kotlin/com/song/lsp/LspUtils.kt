package com.song.lsp

import org.eclipse.lsp4j.DiagnosticSeverity

class LspException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

object LspDiagnosticSeverity {
    const val ERROR = 1
    const val WARNING = 2
    const val INFORMATION = 3
    const val HINT = 4

    private val names = mapOf(
        ERROR to "Error", WARNING to "Warning",
        INFORMATION to "Information", HINT to "Hint"
    )

    fun nameOf(severity: Int?): String = names[severity] ?: "Unknown"

    fun nameOf(severity: DiagnosticSeverity?): String = when (severity) {
        DiagnosticSeverity.Error -> "Error"
        DiagnosticSeverity.Warning -> "Warning"
        DiagnosticSeverity.Information -> "Information"
        DiagnosticSeverity.Hint -> "Hint"
        null -> "Unknown"
    }

    fun toInt(severity: DiagnosticSeverity?): Int = when (severity) {
        DiagnosticSeverity.Error -> ERROR
        DiagnosticSeverity.Warning -> WARNING
        DiagnosticSeverity.Information -> INFORMATION
        DiagnosticSeverity.Hint -> HINT
        null -> 0
    }
}

object LspSymbolKind {
    private val names = mapOf(
        1 to "File", 2 to "Module", 3 to "Namespace", 4 to "Package",
        5 to "Class", 6 to "Method", 7 to "Property", 8 to "Field",
        9 to "Constructor", 10 to "Enum", 11 to "Interface", 12 to "Function",
        13 to "Variable", 14 to "Constant", 15 to "String", 16 to "Number",
        17 to "Boolean", 18 to "Array", 19 to "Object", 20 to "Key",
        21 to "Null", 22 to "EnumMember", 23 to "Struct", 24 to "Event",
        25 to "Operator", 26 to "TypeParameter"
    )

    fun nameOf(kind: Int): String = names[kind] ?: "Unknown($kind)"

    fun nameOf(kind: org.eclipse.lsp4j.SymbolKind?): String =
        if (kind != null) nameOf(kind.value) else "Unknown"
}
