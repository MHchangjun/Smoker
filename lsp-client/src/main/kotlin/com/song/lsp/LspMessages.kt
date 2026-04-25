package com.song.lsp

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

// --- JSON-RPC ---

@Serializable
data class JsonRpcRequest(
    val jsonrpc: String = "2.0",
    val id: Int,
    val method: String,
    val params: JsonElement = JsonNull
)

@Serializable
data class JsonRpcNotification(
    val jsonrpc: String = "2.0",
    val method: String,
    val params: JsonElement = JsonNull
)

@Serializable
data class JsonRpcResponse(
    val jsonrpc: String = "2.0",
    val id: Int? = null,
    val result: JsonElement? = null,
    val error: JsonRpcError? = null
)

@Serializable
data class JsonRpcError(
    val code: Int,
    val message: String,
    val data: JsonElement? = null
)

// --- LSP Types ---

@Serializable
data class Position(
    val line: Int,
    val character: Int
)

@Serializable
data class Range(
    val start: Position,
    val end: Position
)

@Serializable
data class Location(
    val uri: String,
    val range: Range
)

@Serializable
data class TextDocumentIdentifier(
    val uri: String
)

@Serializable
data class VersionedTextDocumentIdentifier(
    val uri: String,
    val version: Int
)

@Serializable
data class TextDocumentItem(
    val uri: String,
    val languageId: String,
    val version: Int,
    val text: String
)

@Serializable
data class TextDocumentPositionParams(
    val textDocument: TextDocumentIdentifier,
    val position: Position
)

@Serializable
data class ReferenceParams(
    val textDocument: TextDocumentIdentifier,
    val position: Position,
    val context: ReferenceContext = ReferenceContext()
)

@Serializable
data class ReferenceContext(
    val includeDeclaration: Boolean = true
)

@Serializable
data class DidOpenTextDocumentParams(
    val textDocument: TextDocumentItem
)

@Serializable
data class DidCloseTextDocumentParams(
    val textDocument: TextDocumentIdentifier
)

@Serializable
data class DidChangeTextDocumentParams(
    val textDocument: VersionedTextDocumentIdentifier,
    val contentChanges: List<TextDocumentContentChangeEvent>
)

@Serializable
data class TextDocumentContentChangeEvent(
    val text: String
)

@Serializable
data class DocumentSymbolParams(
    val textDocument: TextDocumentIdentifier
)

@Serializable
data class WorkspaceSymbolParams(
    val query: String
)

// --- Initialize ---

@Serializable
data class InitializeParams(
    val processId: Int? = null,
    val rootUri: String,
    val capabilities: ClientCapabilities = ClientCapabilities()
)

@Serializable
data class ClientCapabilities(
    val textDocument: TextDocumentClientCapabilities? = TextDocumentClientCapabilities()
)

@Serializable
data class TextDocumentClientCapabilities(
    val synchronization: TextDocumentSyncClientCapabilities? = TextDocumentSyncClientCapabilities(),
    val callHierarchy: CallHierarchyClientCapabilities? = CallHierarchyClientCapabilities(),
    val codeAction: CodeActionClientCapabilities? = CodeActionClientCapabilities()
)

@Serializable
data class CallHierarchyClientCapabilities(
    val dynamicRegistration: Boolean = false
)

@Serializable
data class CodeActionClientCapabilities(
    val dynamicRegistration: Boolean = false
)

@Serializable
data class TextDocumentSyncClientCapabilities(
    val didSave: Boolean = true,
    val dynamicRegistration: Boolean = false
)

// --- Hover ---

@Serializable
data class HoverResult(
    val contents: JsonElement, // can be string, MarkedString, or MarkupContent
    val range: Range? = null
)

// --- Document Symbol ---

@Serializable
data class DocumentSymbol(
    val name: String,
    val kind: Int,
    val range: Range,
    val selectionRange: Range,
    val detail: String? = null,
    val children: List<DocumentSymbol>? = null
)

@Serializable
data class SymbolInformation(
    val name: String,
    val kind: Int,
    val location: Location,
    val containerName: String? = null
)

// --- Call Hierarchy ---

@Serializable
data class CallHierarchyItem(
    val name: String,
    val kind: Int,
    val detail: String? = null,
    val uri: String,
    val range: Range,
    val selectionRange: Range,
    val data: JsonElement? = null
)

@Serializable
data class CallHierarchyIncomingCall(
    val from: CallHierarchyItem,
    val fromRanges: List<Range>
)

@Serializable
data class CallHierarchyOutgoingCall(
    val to: CallHierarchyItem,
    val fromRanges: List<Range>
)

@Serializable
data class CallHierarchyIncomingCallsParams(
    val item: CallHierarchyItem
)

@Serializable
data class CallHierarchyOutgoingCallsParams(
    val item: CallHierarchyItem
)

// --- Diagnostics ---

@Serializable
data class Diagnostic(
    val range: Range,
    val severity: Int? = null,
    val code: String? = null,
    val source: String? = null,
    val message: String
)

@Serializable
data class DocumentDiagnosticParams(
    val textDocument: TextDocumentIdentifier
)

@Serializable
data class PublishDiagnosticsParams(
    val uri: String,
    val diagnostics: List<Diagnostic>
)

object DiagnosticSeverity {
    const val ERROR = 1
    const val WARNING = 2
    const val INFORMATION = 3
    const val HINT = 4

    private val names = mapOf(
        ERROR to "Error", WARNING to "Warning",
        INFORMATION to "Information", HINT to "Hint"
    )

    fun nameOf(severity: Int?): String = names[severity] ?: "Unknown"
}

// --- Code Actions ---

@Serializable
data class CodeAction(
    val title: String,
    val kind: String? = null,
    val diagnostics: List<Diagnostic>? = null,
    val isPreferred: Boolean? = null,
    val edit: WorkspaceEdit? = null
)

@Serializable
data class WorkspaceEdit(
    val changes: Map<String, List<TextEdit>>? = null
)

@Serializable
data class TextEdit(
    val range: Range,
    val newText: String
)

@Serializable
data class CodeActionParams(
    val textDocument: TextDocumentIdentifier,
    val range: Range,
    val context: CodeActionContext
)

@Serializable
data class CodeActionContext(
    val diagnostics: List<Diagnostic> = emptyList(),
    val only: List<String>? = null
)

// --- Symbol Kind ---
object SymbolKind {
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
}
