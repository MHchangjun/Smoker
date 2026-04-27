package com.song.agent.tool

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription
import com.song.lsp.*
import com.song.lsp.LspClient.Companion.uriToPath
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class LspTool(
    private val lspClient: LspClient
) : Tool<LspTool.Args, LspTool.Result>(
    argsSerializer = Args.serializer(),
    resultSerializer = Result.serializer(),
    name = ToolNames.LSP,
    description = """Language Server Protocol (LSP) tool for code intelligence: definitions, references, hover, symbols, call hierarchy, diagnostics, and code actions.

  Usage:
  - ALWAYS use LSP as the PRIMARY tool for code intelligence queries when available. Do NOT use grep_search or glob first.
  - goToDefinition, findReferences, hover, goToImplementation, prepareCallHierarchy require filePath + line + character (1-based).
  - documentSymbol and diagnostics require filePath.
  - workspaceSymbol requires query (use when user asks "where is X defined?" without specifying a file).
  - incomingCalls/outgoingCalls require callHierarchyItem from prepareCallHierarchy.
  - workspaceDiagnostics needs no parameters.
  - codeActions require filePath + range (line/character + endLine/endCharacter) and diagnostics/context as needed."""
) {
    private val json = Json { ignoreUnknownKeys = true }

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
        @param:LLMDescription("Call hierarchy item for incoming/outgoing calls.")
        val callHierarchyItem: CallHierarchyItem? = null,
        @param:LLMDescription("Optional maximum number of results to return.")
        val limit: Int? = null,
        @param:LLMDescription("Diagnostics for code action context.")
        val diagnostics: List<Diagnostic>? = null,
        @param:LLMDescription("Filter code actions by kind (quickfix, refactor, etc.).")
        val codeActionKinds: List<String>? = null
    )

    @Serializable
    enum class LspOperation {
        @SerialName("goToDefinition") GO_TO_DEFINITION,
        @SerialName("findReferences") FIND_REFERENCES,
        @SerialName("hover") HOVER,
        @SerialName("documentSymbol") DOCUMENT_SYMBOL,
        @SerialName("workspaceSymbol") WORKSPACE_SYMBOL,
        @SerialName("goToImplementation") GO_TO_IMPLEMENTATION,
        @SerialName("prepareCallHierarchy") PREPARE_CALL_HIERARCHY,
        @SerialName("incomingCalls") INCOMING_CALLS,
        @SerialName("outgoingCalls") OUTGOING_CALLS,
        @SerialName("diagnostics") DIAGNOSTICS,
        @SerialName("workspaceDiagnostics") WORKSPACE_DIAGNOSTICS,
        @SerialName("codeActions") CODE_ACTIONS
    }

    @Serializable
    data class Result(
        val content: String
    )

    override suspend fun execute(args: Args): Result {
        if (!lspClient.initialized) {
            return Result("LSP is unavailable (LSP disabled or not initialized).")
        }

        val limit = args.limit ?: DEFAULT_LIMIT
        val op = args.operation

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

                LspOperation.HOVER -> {
                    requirePosition(args)
                    val info = lspClient.hover(args.filePath, args.line!!, args.character!!)
                    Result(info ?: "No hover information found.")
                }

                LspOperation.DOCUMENT_SYMBOL -> {
                    requireFilePath(args)
                    val symbols = lspClient.documentSymbol(args.filePath)
                    if (symbols.isEmpty()) return Result("No document symbols found.")
                    Result(
                        buildString {
                            appendLine("Document symbols:")
                            var count = 0
                            fun render(sym: DocumentSymbol, indent: Int) {
                                if (count >= limit) return
                                val prefix = "  ".repeat(indent)
                                val kind = SymbolKind.nameOf(sym.kind)
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
                                val kind = SymbolKind.nameOf(sym.kind)
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

                LspOperation.PREPARE_CALL_HIERARCHY -> {
                    requirePosition(args)
                    val items = lspClient.prepareCallHierarchy(args.filePath, args.line!!, args.character!!)
                    if (items.isEmpty()) return Result("No call hierarchy items found.")
                    val sliced = items.take(limit)
                    Result(
                        buildString {
                            appendLine("Call hierarchy items:")
                            sliced.forEachIndexed { i, item ->
                                val kind = SymbolKind.nameOf(item.kind)
                                val path = uriToPath(item.uri)
                                val line = item.selectionRange.start.line + 1
                                val detail = if (item.detail != null) " ${item.detail}" else ""
                                appendLine("${i + 1}. ${item.name} ($kind)$detail - $path:$line")
                            }
                            appendLine()
                            appendLine("Call hierarchy items (JSON):")
                            append(json.encodeToString(kotlinx.serialization.builtins.ListSerializer(CallHierarchyItem.serializer()), sliced))
                        }.trim()
                    )
                }

                LspOperation.INCOMING_CALLS -> {
                    val item = args.callHierarchyItem
                        ?: return Result("LSP incomingCalls failed: callHierarchyItem is required.")
                    val calls = lspClient.incomingCalls(item)
                    if (calls.isEmpty()) return Result("No incoming calls found for ${item.name}.")
                    Result(
                        buildString {
                            appendLine("Incoming calls for ${item.name}:")
                            calls.take(limit).forEachIndexed { i, call ->
                                val from = call.from
                                val kind = SymbolKind.nameOf(from.kind)
                                val path = uriToPath(from.uri)
                                val line = from.selectionRange.start.line + 1
                                val detail = if (from.detail != null) " ${from.detail}" else ""
                                val rangeSuffix = formatCallRanges(call.fromRanges)
                                appendLine("${i + 1}. ${from.name} ($kind)$detail - $path:$line$rangeSuffix")
                            }
                        }.trim()
                    )
                }

                LspOperation.OUTGOING_CALLS -> {
                    val item = args.callHierarchyItem
                        ?: return Result("LSP outgoingCalls failed: callHierarchyItem is required.")
                    val calls = lspClient.outgoingCalls(item)
                    if (calls.isEmpty()) return Result("No outgoing calls found for ${item.name}.")
                    Result(
                        buildString {
                            appendLine("Outgoing calls for ${item.name}:")
                            calls.take(limit).forEachIndexed { i, call ->
                                val to = call.to
                                val kind = SymbolKind.nameOf(to.kind)
                                val path = uriToPath(to.uri)
                                val line = to.selectionRange.start.line + 1
                                val detail = if (to.detail != null) " ${to.detail}" else ""
                                val rangeSuffix = formatCallRanges(call.fromRanges)
                                appendLine("${i + 1}. ${to.name} ($kind)$detail - $path:$line$rangeSuffix")
                            }
                        }.trim()
                    )
                }

                LspOperation.DIAGNOSTICS -> {
                    requireFilePath(args)
                    val diags = lspClient.diagnostics(args.filePath)
                    if (diags.isEmpty()) return Result("No diagnostics found.")
                    Result(
                        buildString {
                            appendLine("Diagnostics (${diags.size} issues):")
                            diags.take(limit).forEachIndexed { i, diag ->
                                val severity = DiagnosticSeverity.nameOf(diag.severity)
                                val position = "${diag.range.start.line + 1}:${diag.range.start.character + 1}"
                                val code = if (diag.code != null) " (${diag.code})" else ""
                                val source = if (diag.source != null) " [${diag.source}]" else ""
                                appendLine("${i + 1}. [${severity.uppercase()}] $position$code$source: ${diag.message}")
                            }
                        }.trim()
                    )
                }

                LspOperation.WORKSPACE_DIAGNOSTICS -> {
                    val fileDiags = lspClient.workspaceDiagnostics()
                    if (fileDiags.isEmpty()) return Result("No diagnostics found in the workspace.")
                    var totalIssues = 0
                    Result(
                        buildString {
                            fileDiags.entries.take(limit).forEach { (uri, diagnostics) ->
                                val path = uriToPath(uri)
                                appendLine("$path:")
                                diagnostics.forEach { diag ->
                                    val severity = DiagnosticSeverity.nameOf(diag.severity)
                                    val position = "${diag.range.start.line + 1}:${diag.range.start.character + 1}"
                                    val code = if (diag.code != null) " (${diag.code})" else ""
                                    appendLine("  [${severity.uppercase()}] $position$code: ${diag.message}")
                                    totalIssues++
                                }
                            }
                            appendLine()
                            append("Workspace diagnostics ($totalIssues issues in ${fileDiags.size} files)")
                        }.trim()
                    )
                }

                LspOperation.CODE_ACTIONS -> {
                    requirePosition(args)
                    val endLine = args.endLine ?: args.line!!
                    val endChar = args.endCharacter ?: args.character!!
                    val actions = lspClient.codeActions(
                        args.filePath, args.line!!, args.character!!, endLine, endChar,
                        diagnostics = args.diagnostics ?: emptyList(),
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

    private fun formatLocation(loc: Location): String {
        val path = uriToPath(loc.uri)
        val line = loc.range.start.line + 1
        val col = loc.range.start.character + 1
        return "$path:$line:$col"
    }

    private fun formatCallRanges(ranges: List<Range>): String {
        if (ranges.isEmpty()) return ""
        val shown = ranges.take(3).joinToString(", ") {
            "${it.start.line + 1}:${it.start.character + 1}"
        }
        val extra = if (ranges.size > 3) ", +${ranges.size - 3} more" else ""
        return " (calls at $shown$extra)"
    }

    companion object {
        private const val DEFAULT_LIMIT = 20
    }
}
