package com.song.lsp

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.*
import java.io.Closeable
import java.io.File
import java.nio.file.Path
import java.util.logging.Logger
import kotlin.io.path.walk
import kotlin.time.Duration.Companion.minutes

class LspClient(
    private val processManager: LspProcessManager,
    private val projectRoot: Path
) : Closeable {
    private val log = Logger.getLogger(LspClient::class.java.simpleName)
    private lateinit var transport: JsonRpcTransport
    private val fileTracker = LspFileTracker()
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    var initialized: Boolean = false
        private set

    suspend fun start() {
        val (input, output) = processManager.start()
        transport = JsonRpcTransport(input, output)
        transport.startReading()
        initialize()
    }

    suspend fun stop() {
        if (initialized) {
            try {
                transport.request("shutdown", JsonNull)
                transport.notify("exit", JsonNull)
            } catch (_: Exception) {
            }
            initialized = false
        }
        transport.close()
        processManager.stop()
        fileTracker.clear()
    }

    override fun close() {
        if (initialized) {
            try {
                transport.close()
            } catch (_: Exception) {
            }
            processManager.stop()
            fileTracker.clear()
            initialized = false
        }
    }

    // --- LSP Methods ---

    suspend fun goToDefinition(filePath: String, line: Int, character: Int): List<Location> {
        ensureFileSync(filePath)
        val params = textDocumentPositionParams(filePath, line, character)
        val result = transport.request("textDocument/definition", params) ?: return emptyList()
        return parseLocations(result)
    }

    suspend fun findReferences(filePath: String, line: Int, character: Int, includeDeclaration: Boolean = false): List<Location> {
        ensureFileSync(filePath)
        val params = json.encodeToJsonElement(
            ReferenceParams(
                textDocument = TextDocumentIdentifier(uri = fileUri(filePath)),
                position = Position(line = line - 1, character = character - 1),
                context = ReferenceContext(includeDeclaration = includeDeclaration)
            )
        )
        val result = transport.request("textDocument/references", params) ?: return emptyList()
        return parseLocations(result)
    }

    suspend fun hover(filePath: String, line: Int, character: Int): String? {
        ensureFileSync(filePath)
        val params = textDocumentPositionParams(filePath, line, character)
        val result = transport.request("textDocument/hover", params) ?: return null
        if (result is JsonNull) return null
        val hover = json.decodeFromJsonElement(HoverResult.serializer(), result)
        return extractHoverContent(hover.contents)
    }

    suspend fun documentSymbol(filePath: String): List<DocumentSymbol> {
        ensureFileSync(filePath)
        val params = json.encodeToJsonElement(
            DocumentSymbolParams(textDocument = TextDocumentIdentifier(uri = fileUri(filePath)))
        )
        val result = transport.request("textDocument/documentSymbol", params) ?: return emptyList()
        return try {
            json.decodeFromJsonElement(ListSerializer(DocumentSymbol.serializer()), result)
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun workspaceSymbol(query: String): List<SymbolInformation> {
        val params = json.encodeToJsonElement(WorkspaceSymbolParams(query = query))
        val result = transport.request("workspace/symbol", params) ?: return emptyList()
        return try {
            json.decodeFromJsonElement(ListSerializer(SymbolInformation.serializer()), result)
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun goToImplementation(filePath: String, line: Int, character: Int): List<Location> {
        ensureFileSync(filePath)
        val params = textDocumentPositionParams(filePath, line, character)
        val result = transport.request("textDocument/implementation", params) ?: return emptyList()
        return parseLocations(result)
    }

    suspend fun prepareCallHierarchy(filePath: String, line: Int, character: Int): List<CallHierarchyItem> {
        ensureFileSync(filePath)
        val params = textDocumentPositionParams(filePath, line, character)
        val result = transport.request("textDocument/prepareCallHierarchy", params) ?: return emptyList()
        return try {
            json.decodeFromJsonElement(ListSerializer(CallHierarchyItem.serializer()), result)
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun incomingCalls(item: CallHierarchyItem): List<CallHierarchyIncomingCall> {
        val params = json.encodeToJsonElement(CallHierarchyIncomingCallsParams.serializer(), CallHierarchyIncomingCallsParams(item = item))
        val result = transport.request("callHierarchy/incomingCalls", params) ?: return emptyList()
        return try {
            json.decodeFromJsonElement(ListSerializer(CallHierarchyIncomingCall.serializer()), result)
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun outgoingCalls(item: CallHierarchyItem): List<CallHierarchyOutgoingCall> {
        val params = json.encodeToJsonElement(CallHierarchyOutgoingCallsParams.serializer(), CallHierarchyOutgoingCallsParams(item = item))
        val result = transport.request("callHierarchy/outgoingCalls", params) ?: return emptyList()
        return try {
            json.decodeFromJsonElement(ListSerializer(CallHierarchyOutgoingCall.serializer()), result)
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun diagnostics(filePath: String): List<Diagnostic> {
        ensureFileSync(filePath)
        // Try pull-model diagnostics first
        val params = json.encodeToJsonElement(
            DocumentDiagnosticParams.serializer(),
            DocumentDiagnosticParams(textDocument = TextDocumentIdentifier(uri = fileUri(filePath)))
        )
        return try {
            val result = transport.request("textDocument/diagnostic", params)
            if (result != null) {
                val items = result.jsonObject["items"]
                if (items != null) {
                    json.decodeFromJsonElement(ListSerializer(Diagnostic.serializer()), items)
                } else {
                    emptyList()
                }
            } else {
                emptyList()
            }
        } catch (_: Exception) {
            // Fallback to cached publishDiagnostics notifications
            transport.getCachedDiagnostics(fileUri(filePath))
        }
    }

    suspend fun workspaceDiagnostics(): Map<String, List<Diagnostic>> {
        // Collect all cached diagnostics from publishDiagnostics notifications
        // kotlin-language-server does not typically support workspace/diagnostic pull
        return try {
            val params = json.encodeToJsonElement(
                kotlinx.serialization.json.JsonObject.serializer(),
                kotlinx.serialization.json.JsonObject(mapOf(
                    "previousResultIds" to kotlinx.serialization.json.JsonArray(emptyList())
                ))
            )
            val result = transport.request("workspace/diagnostic", params)
            if (result != null) {
                val items = result.jsonObject["items"]
                if (items != null) {
                    val reports = items as? kotlinx.serialization.json.JsonArray ?: return emptyMap()
                    val map = mutableMapOf<String, List<Diagnostic>>()
                    for (report in reports) {
                        val obj = report.jsonObject
                        val uri = obj["uri"]?.jsonPrimitive?.content ?: continue
                        val diags = obj["items"]?.let {
                            json.decodeFromJsonElement(ListSerializer(Diagnostic.serializer()), it)
                        } ?: emptyList()
                        map[uri] = diags
                    }
                    map
                } else {
                    emptyMap()
                }
            } else {
                emptyMap()
            }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    suspend fun codeActions(
        filePath: String,
        startLine: Int,
        startCharacter: Int,
        endLine: Int,
        endCharacter: Int,
        diagnostics: List<Diagnostic> = emptyList(),
        codeActionKinds: List<String>? = null
    ): List<CodeAction> {
        ensureFileSync(filePath)
        val params = json.encodeToJsonElement(
            CodeActionParams.serializer(),
            CodeActionParams(
                textDocument = TextDocumentIdentifier(uri = fileUri(filePath)),
                range = Range(
                    start = Position(line = startLine - 1, character = startCharacter - 1),
                    end = Position(line = endLine - 1, character = endCharacter - 1)
                ),
                context = CodeActionContext(
                    diagnostics = diagnostics,
                    only = codeActionKinds
                )
            )
        )
        val result = transport.request("textDocument/codeAction", params) ?: return emptyList()
        return try {
            json.decodeFromJsonElement(ListSerializer(CodeAction.serializer()), result)
        } catch (_: Exception) {
            emptyList()
        }
    }

    // --- File Sync ---

    private suspend fun ensureFileSync(filePath: String) {
        val uri = fileUri(filePath)
        val content = File(filePath).readText()

        if (!fileTracker.isOpen(uri)) {
            val params = json.encodeToJsonElement(
                DidOpenTextDocumentParams(
                    textDocument = TextDocumentItem(
                        uri = uri,
                        languageId = "kotlin",
                        version = 1,
                        text = content
                    )
                )
            )
            transport.notify("textDocument/didOpen", params)
            fileTracker.markOpened(uri)
        } else {
            val version = fileTracker.incrementVersion(uri)
            val params = json.encodeToJsonElement(
                DidChangeTextDocumentParams(
                    textDocument = VersionedTextDocumentIdentifier(uri = uri, version = version),
                    contentChanges = listOf(TextDocumentContentChangeEvent(text = content))
                )
            )
            transport.notify("textDocument/didChange", params)
        }
    }

    // --- Initialize ---

    private suspend fun initialize() {
        val params = json.encodeToJsonElement(
            InitializeParams(
                processId = ProcessHandle.current().pid().toInt(),
                rootUri = fileUri(projectRoot.toAbsolutePath().normalize().toString())
            )
        )
        transport.request("initialize", params, timeout = 5.minutes)
        transport.notify("initialized", JsonObject(emptyMap()))
        initialized = true
        verify()
    }

    private suspend fun verify() {
        val testFile = projectRoot.walk()
            .firstOrNull { it.toString().endsWith(".kt") }
            ?: run {
                log.warning("LSP verify: no .kt file found in project")
                initialized = false
                return
            }

        try {
            val uri = fileUri(testFile.toAbsolutePath().normalize().toString())
            val content = testFile.toFile().readText()
            val openParams = json.encodeToJsonElement(
                DidOpenTextDocumentParams(
                    textDocument = TextDocumentItem(
                        uri = uri, languageId = "kotlin", version = 1, text = content
                    )
                )
            )
            transport.notify("textDocument/didOpen", openParams)
            fileTracker.markOpened(uri)

            val symbolParams = json.encodeToJsonElement(
                DocumentSymbolParams(textDocument = TextDocumentIdentifier(uri = uri))
            )
            transport.request("textDocument/documentSymbol", symbolParams)
            log.info("LSP verify: OK")
        } catch (e: Exception) {
            log.warning("LSP verify failed: ${e.message}")
            initialized = false
        }
    }

    // --- Helpers ---

    private fun textDocumentPositionParams(filePath: String, line: Int, character: Int): JsonElement {
        return json.encodeToJsonElement(
            TextDocumentPositionParams(
                textDocument = TextDocumentIdentifier(uri = fileUri(filePath)),
                position = Position(line = line - 1, character = character - 1) // convert 1-based to 0-based
            )
        )
    }

    private fun parseLocations(element: JsonElement): List<Location> {
        return try {
            when (element) {
                is JsonArray -> json.decodeFromJsonElement(ListSerializer(Location.serializer()), element)
                is JsonObject -> listOf(json.decodeFromJsonElement(Location.serializer(), element))
                else -> emptyList()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun extractHoverContent(contents: JsonElement): String? {
        return when (contents) {
            is JsonPrimitive -> contents.content
            is JsonObject -> {
                // MarkupContent: { kind: "markdown"|"plaintext", value: "..." }
                contents["value"]?.jsonPrimitive?.content
            }
            is JsonArray -> {
                // List of MarkedString
                contents.mapNotNull { item ->
                    when (item) {
                        is JsonPrimitive -> item.content
                        is JsonObject -> item["value"]?.jsonPrimitive?.content
                        else -> null
                    }
                }.joinToString("\n")
            }
            else -> null
        }
    }

    companion object {
        fun fileUri(path: String): String {
            val normalized = if (path.startsWith("/")) path else "/$path"
            return "file://$normalized"
        }

        fun uriToPath(uri: String): String {
            return uri.removePrefix("file://")
        }
    }
}
