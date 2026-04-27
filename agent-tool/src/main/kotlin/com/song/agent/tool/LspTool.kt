package com.song.agent.tool

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription
import com.song.lsp.LspClient
import com.song.lsp.LspClient.Companion.uriToPath
import com.song.lsp.LspDiagnosticSeverity
import com.song.lsp.LspSymbolKind
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.eclipse.lsp4j.Diagnostic
import java.util.concurrent.ConcurrentHashMap

class LspTool(
    private val lspClient: LspClient
) : Tool<LspTool.Args, LspTool.Result>(
    argsSerializer = Args.serializer(),
    resultSerializer = Result.serializer(),
    name = ToolNames.LSP,
    description = """
Language Server Protocol (LSP) tool for code intelligence: definitions, references, symbols, diagnostics, and code actions.

Usage:
- ALWAYS use LSP as the PRIMARY tool for code intelligence queries when available. Do NOT use grep_search or glob first.
- goToDefinition, findReferences, goToImplementation require filePath + line + character (1-based).
- documentSymbol and diagnostics require filePath.
- workspaceSymbol requires query (use when user asks "where is X defined?" without specifying a file).
- codeActions require filePath + range (line/character + endLine/endCharacter). To get QuickFix for a specific diagnostic, first call diagnostics on the file, then pass the 1-based diagnosticIndex from that result.
""".trimIndent()
) {
    // Cache of the last diagnostics list shown to the LLM, keyed by filePath.
    // CODE_ACTIONS resolves diagnosticIndex against this so the LLM never has to construct lsp4j JSON.
    private val lastDiagnostics = ConcurrentHashMap<String, List<Diagnostic>>()

    @Serializable
    data class Args(
        @param:LLMDescription("LSP operation to execute.")
        val operation: LspOperation,
        @param:LLMDescription("File path (absolute or workspace-relative).")
        val filePath: String = "",
        @param:LLMDescription("1-based line number for the target location.")
        val line: Int? = null,
        @param:LLMDescription("1-based character/column number for the target location.")
        val character: Int? = null,
        @param:LLMDescription("1-based end line number for range-based operations.")
        val endLine: Int? = null,
        @param:LLMDescription("1-based end character for range-based operations.")
        val endCharacter: Int? = null,
        @param:LLMDescription("Include the declaration itself when looking up references.")
        val includeDeclaration: Boolean? = null,
        @param:LLMDescription("Symbol query for workspace symbol search.")
        val query: String? = null,
        @param:LLMDescription("Optional maximum number of results to return.")
        val limit: Int? = null,
        @param:LLMDescription("1-based index into the most recent diagnostics result for this filePath. Used by codeActions to target a specific diagnostic for QuickFix.")
        val diagnosticIndex: Int? = null,
        @param:LLMDescription("Filter code actions by kind (quickfix, refactor, etc.).")
        val codeActionKinds: List<String>? = null
    )

    @Serializable
    enum class LspOperation {
        @SerialName("goToDefinition") GO_TO_DEFINITION,
        @SerialName("findReferences") FIND_REFERENCES,
        @SerialName("documentSymbol") DOCUMENT_SYMBOL,
        @SerialName("workspaceSymbol") WORKSPACE_SYMBOL,
        @SerialName("goToImplementation") GO_TO_IMPLEMENTATION,
        @SerialName("diagnostics") DIAGNOSTICS,
        @SerialName("codeActions") CODE_ACTIONS
    }

    @Serializable
    data class Result(
        val content: String
    )

    private val operationToMethod = mapOf(
        LspOperation.GO_TO_DEFINITION to "textDocument/definition",
        LspOperation.FIND_REFERENCES to "textDocument/references",
        LspOperation.DOCUMENT_SYMBOL to "textDocument/documentSymbol",
        LspOperation.GO_TO_IMPLEMENTATION to "textDocument/implementation",
        LspOperation.DIAGNOSTICS to "textDocument/diagnostic",
        LspOperation.CODE_ACTIONS to "textDocument/codeAction",
    )

    override suspend fun execute(args: Args): Result {
        if (!lspClient.initialized) {
            return Result("LSP is unavailable (LSP disabled or not initialized).")
        }

        val method = operationToMethod[args.operation]
        if (method != null && !lspClient.supports(method)) {
            return Result("LSP server does not support '${args.operation.name}'. The kotlin-language-server may not have this capability enabled, or the project's Gradle classpath may not be fully resolved. Check LSP stderr log for details.")
        }

        val limit = args.limit ?: DEFAULT_LIMIT
        val op = args.operation

        // line/character are 1-based here. LspClient converts to 0-based internally before sending to the server.
        return try {
            when (op) {
                LspOperation.GO_TO_DEFINITION -> {
                    requirePosition(args)
                    val locations = lspClient.goToDefinition(args.filePath, args.line!!, args.character!!)
                    if (locations.isEmpty()) return Result("No definitions found.")
                    Result(
                        buildString {
                            appendLine("Definitions:")
                            locations.take(limit).forEachIndexed { i, loc ->
                                appendLine("${i + 1}. ${formatLocation(loc)}")
                            }
                        }.trim()
                    )
                }

                LspOperation.FIND_REFERENCES -> {
                    requirePosition(args)
                    val locations = lspClient.findReferences(
                        args.filePath, args.line!!, args.character!!,
                        includeDeclaration = args.includeDeclaration ?: false
                    )
                    if (locations.isEmpty()) return Result("No references found.")
                    Result(
                        buildString {
                            appendLine("References:")
                            locations.take(limit).forEachIndexed { i, loc ->
                                appendLine("${i + 1}. ${formatLocation(loc)}")
                            }
                        }.trim()
                    )
                }
                LspOperation.DOCUMENT_SYMBOL -> {
                    requireFilePath(args)
                    val symbols = lspClient.documentSymbol(args.filePath)
                    if (symbols.isEmpty()) return Result("No document symbols found.")
                    Result(
                        buildString {
                            appendLine("Document symbols:")
                            var count = 0
                            fun render(sym: org.eclipse.lsp4j.DocumentSymbol, indent: Int) {
                                if (count >= limit) return
                                val prefix = "  ".repeat(indent)
                                val kind = LspSymbolKind.nameOf(sym.kind)
                                val detail = if (sym.detail != null) " — ${sym.detail}" else ""
                                appendLine("${prefix}${sym.name} ($kind) line ${sym.selectionRange.start.line + 1}$detail")
                                count++
                                sym.children?.forEach { render(it, indent + 1) }
                            }
                            symbols.forEach { render(it, 0) }
                        }.trim()
                    )
                }

                LspOperation.WORKSPACE_SYMBOL -> {
                    val query = args.query
                        ?: return Result("LSP workspaceSymbol failed: query is required.")
                    val symbols = lspClient.workspaceSymbol(query)
                    if (symbols.isEmpty()) return Result("No symbols found for query \"$query\".")
                    val sliced = symbols.take(limit)
                    Result(
                        buildString {
                            appendLine("Found ${sliced.size} of ${symbols.size} symbols for query \"$query\":")
                            sliced.forEachIndexed { i, sym ->
                                val kind = LspSymbolKind.nameOf(sym.kind)
                                val container = if (sym.containerName != null) " in ${sym.containerName}" else ""
                                val path = uriToPath(sym.location.uri)
                                val line = sym.location.range.start.line + 1
                                appendLine("${i + 1}. ${sym.name} ($kind)$container - $path:$line")
                            }
                        }.trim()
                    )
                }

                LspOperation.GO_TO_IMPLEMENTATION -> {
                    requirePosition(args)
                    val locations = lspClient.goToImplementation(args.filePath, args.line!!, args.character!!)
                    if (locations.isEmpty()) return Result("No implementations found.")
                    Result(
                        buildString {
                            appendLine("Implementations:")
                            locations.take(limit).forEachIndexed { i, loc ->
                                appendLine("${i + 1}. ${formatLocation(loc)}")
                            }
                        }.trim()
                    )
                }

                LspOperation.DIAGNOSTICS -> {
                    requireFilePath(args)
                    val diags = lspClient.diagnostics(args.filePath)
                    if (diags.isEmpty()) {
                        lastDiagnostics.remove(args.filePath)
                        return Result("No diagnostics found.")
                    }
                    val sliced = diags.take(limit)
                    lastDiagnostics[args.filePath] = sliced
                    Result(
                        buildString {
                            appendLine("Diagnostics (${diags.size} issues):")
                            sliced.forEachIndexed { i, diag ->
                                val severity = LspDiagnosticSeverity.nameOf(diag.severity)
                                val position = "${diag.range.start.line + 1}:${diag.range.start.character + 1}"
                                val code = diag.code?.get()?.let { " ($it)" } ?: ""
                                val source = if (diag.source != null) " [${diag.source}]" else ""
                                appendLine("${i + 1}. [${severity.uppercase()}] $position$code$source: ${diag.message}")
                            }
                        }.trim()
                    )
                }

                LspOperation.CODE_ACTIONS -> {
                    requirePosition(args)
                    val diagnostics: List<Diagnostic> = args.diagnosticIndex?.let { idx ->
                        val cached = lastDiagnostics[args.filePath]
                            ?: return Result("CODE_ACTIONS: no cached diagnostics for ${args.filePath}. Call diagnostics first.")
                        val diag = cached.getOrNull(idx - 1)
                            ?: return Result("CODE_ACTIONS: diagnosticIndex $idx out of range (1..${cached.size}).")
                        listOf(diag)
                    } ?: emptyList()

                    // When targeting a diagnostic, use its range so the server returns matching QuickFixes.
                    val target = diagnostics.firstOrNull()?.range
                    val startLine = target?.let { it.start.line + 1 } ?: args.line!!
                    val startChar = target?.let { it.start.character + 1 } ?: args.character!!
                    val endLine = target?.let { it.end.line + 1 } ?: args.endLine ?: args.line!!
                    val endChar = target?.let { it.end.character + 1 } ?: args.endCharacter ?: args.character!!

                    val actions = lspClient.codeActions(
                        args.filePath, startLine, startChar, endLine, endChar,
                        diagnostics = diagnostics,
                        codeActionKinds = args.codeActionKinds
                    )
                    if (actions.isEmpty()) return Result("No code actions available.")
                    Result(
                        buildString {
                            appendLine("Code actions:")
                            actions.take(limit).forEachIndexed { i, action ->
                                val kind = if (action.kind != null) " [${action.kind}]" else ""
                                val preferred = if (action.isPreferred == true) " ★" else ""
                                val hasEdit = if (action.edit != null) " (has edit)" else ""
                                appendLine("${i + 1}. ${action.title}$kind$preferred$hasEdit")
                            }
                        }.trim()
                    )
                }
            }
        } catch (e: Exception) {
            val message = "LSP ${op.name} failed: ${e.message ?: "unknown error"}"
            Result(message)
        }
    }

    private fun requirePosition(args: Args) {
        if (args.filePath.isBlank()) {
            throw ToolExecutionException("filePath is required for ${args.operation}.")
        }
        if (args.line == null || args.character == null) {
            throw ToolExecutionException("line and character are required for ${args.operation}.")
        }
    }

    private fun requireFilePath(args: Args) {
        if (args.filePath.isBlank()) {
            throw ToolExecutionException("filePath is required for ${args.operation}.")
        }
    }

    private fun formatLocation(loc: org.eclipse.lsp4j.Location): String {
        val path = uriToPath(loc.uri)
        val line = loc.range.start.line + 1
        val col = loc.range.start.character + 1
        return "$path:$line:$col"
    }

    companion object {
        private const val DEFAULT_LIMIT = 20
    }
}
