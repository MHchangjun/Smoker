package com.song.lsp

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withTimeout
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.Launcher
import org.eclipse.lsp4j.launch.LSPLauncher
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.LanguageServer
import java.io.Closeable
import java.io.File
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger
import kotlin.io.path.walk
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class LspClient(
    private val processManager: LspProcessManager,
    private val projectRoot: Path
) : LanguageClient, Closeable {
    private val log = Logger.getLogger(LspClient::class.java.simpleName)
    private lateinit var server: LanguageServer
    private lateinit var launcher: Launcher<LanguageServer>
    private val fileTracker = LspFileTracker()

    // Diagnostics cache (from publishDiagnostics notifications)
    private val diagnosticsCache = ConcurrentHashMap<String, List<Diagnostic>>()
    private val diagnosticsWaiters = ConcurrentHashMap<String, CompletableDeferred<List<Diagnostic>>>()

    @Volatile
    var initialized: Boolean = false
        private set

    private var serverCapabilities: ServerCapabilities? = null

    // --- LanguageClient implementation ---

    override fun telemetryEvent(obj: Any?) {}
    override fun logMessage(message: MessageParams?) {
        message?.let { log.fine("LSP server: [${it.type}] ${it.message}") }
    }

    override fun showMessage(messageParams: MessageParams?) {}
    override fun showMessageRequest(requestParams: ShowMessageRequestParams?): CompletableFuture<MessageActionItem> {
        return CompletableFuture.completedFuture(null)
    }

    override fun publishDiagnostics(diagnostics: PublishDiagnosticsParams?) {
        diagnostics ?: return
        val uri = diagnostics.uri
        val diags = diagnostics.diagnostics
        diagnosticsCache[uri] = diags
        diagnosticsWaiters.remove(uri)?.complete(diags)
    }

    // --- Lifecycle ---

    suspend fun start() {
        val (input, output) = processManager.start()
        launcher = LSPLauncher.createClientLauncher(this, input, output)
        server = launcher.remoteProxy
        launcher.startListening()
        initialize()
    }

    suspend fun stop() {
        if (initialized) {
            try {
                server.shutdown().await()
                server.exit()
            } catch (_: Exception) {
            }
            initialized = false
        }
        processManager.stop()
        fileTracker.clear()
        diagnosticsCache.clear()
        diagnosticsWaiters.clear()
    }

    override fun close() {
        if (initialized) {
            try {
                server.shutdown()?.get(5, java.util.concurrent.TimeUnit.SECONDS)
                server.exit()
            } catch (_: Exception) {
            }
            processManager.stop()
            fileTracker.clear()
            initialized = false
        }
    }

    // --- LSP Methods ---

    suspend fun goToDefinition(filePath: String, line: Int, character: Int): List<Location> {
        requireCapability("textDocument/definition")
        ensureFileSync(filePath)
        val params = DefinitionParams(
            TextDocumentIdentifier(fileUri(filePath)),
            Position(line - 1, character - 1)
        )
        val result = server.textDocumentService.definition(params).await() ?: return emptyList()
        return if (result.isLeft) {
            result.left.filterIsInstance<Location>()
        } else {
            result.right.map { link -> Location(link.targetUri, link.targetRange) }
        }
    }

    suspend fun findReferences(filePath: String, line: Int, character: Int, includeDeclaration: Boolean = false): List<Location> {
        requireCapability("textDocument/references")
        ensureFileSync(filePath)
        val params = ReferenceParams(
            TextDocumentIdentifier(fileUri(filePath)),
            Position(line - 1, character - 1),
            ReferenceContext(includeDeclaration)
        )
        @Suppress("UNCHECKED_CAST")
        return server.textDocumentService.references(params).await() as? List<Location> ?: emptyList()
    }

    suspend fun hover(filePath: String, line: Int, character: Int): String? {
        requireCapability("textDocument/hover")
        ensureFileSync(filePath)
        val params = HoverParams(
            TextDocumentIdentifier(fileUri(filePath)),
            Position(line - 1, character - 1)
        )
        val result = server.textDocumentService.hover(params).await() ?: return null
        return extractHoverContent(result)
    }

    suspend fun documentSymbol(filePath: String): List<DocumentSymbol> {
        ensureFileSync(filePath)
        val params = DocumentSymbolParams(TextDocumentIdentifier(fileUri(filePath)))
        val result = server.textDocumentService.documentSymbol(params).await() ?: return emptyList()
        return result.mapNotNull { either ->
            if (either.isRight) either.right else null
        }
    }

    suspend fun workspaceSymbol(query: String): List<SymbolInformation> {
        val result = server.workspaceService.symbol(WorkspaceSymbolParams(query)).await() ?: return emptyList()
        return if (result.isLeft) {
            result.left.filterIsInstance<SymbolInformation>()
        } else {
            // WorkspaceSymbol list — convert to SymbolInformation
            result.right.map { ws ->
                SymbolInformation(ws.name, ws.kind, ws.location?.left ?: Location(), ws.containerName)
            }
        }
    }

    suspend fun goToImplementation(filePath: String, line: Int, character: Int): List<Location> {
        requireCapability("textDocument/implementation")
        ensureFileSync(filePath)
        val params = ImplementationParams(
            TextDocumentIdentifier(fileUri(filePath)),
            Position(line - 1, character - 1)
        )
        val result = server.textDocumentService.implementation(params).await() ?: return emptyList()
        return if (result.isLeft) {
            result.left.filterIsInstance<Location>()
        } else {
            result.right.map { link -> Location(link.targetUri, link.targetRange) }
        }
    }

    suspend fun diagnostics(filePath: String): List<Diagnostic> {
        ensureFileSync(filePath)
        val uri = fileUri(filePath)
        // Try pull-model diagnostics first
        return try {
            val params = DocumentDiagnosticParams(TextDocumentIdentifier(uri))
            val report = server.textDocumentService.diagnostic(params).await()
            if (report.isRelatedFullDocumentDiagnosticReport) {
                report.relatedFullDocumentDiagnosticReport?.items ?: emptyList()
            } else {
                diagnosticsCache[uri] ?: emptyList()
            }
        } catch (_: Exception) {
            // Fallback to cached publishDiagnostics notifications
            diagnosticsCache[uri] ?: emptyList()
        }
    }

    suspend fun openFileForDiagnostics(filePath: String) {
        ensureFileSync(filePath)
    }

    suspend fun openAndAwaitDiagnostics(filePath: String, timeout: Duration = 30.seconds): List<Diagnostic> {
        ensureFileSync(filePath)
        val uri = fileUri(filePath)
        // Already cached?
        diagnosticsCache[uri]?.let { if (it.isNotEmpty()) return it }

        val waiter = CompletableDeferred<List<Diagnostic>>()
        diagnosticsWaiters[uri] = waiter
        return try {
            withTimeout(timeout) { waiter.await() }
        } catch (_: Exception) {
            diagnosticsCache[uri] ?: emptyList()
        } finally {
            diagnosticsWaiters.remove(uri)
        }
    }

    fun getAllCachedDiagnostics(): Map<String, List<Diagnostic>> = diagnosticsCache.toMap()

    fun getCachedDiagnostics(uri: String): List<Diagnostic> = diagnosticsCache[uri] ?: emptyList()

    fun clearDiagnosticsCache() {
        diagnosticsCache.clear()
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
        requireCapability("textDocument/codeAction")
        ensureFileSync(filePath)
        val context = CodeActionContext(diagnostics).apply {
            if (codeActionKinds != null) only = codeActionKinds
        }
        val params = CodeActionParams(
            TextDocumentIdentifier(fileUri(filePath)),
            Range(
                Position(startLine - 1, startCharacter - 1),
                Position(endLine - 1, endCharacter - 1)
            ),
            context
        )
        val result = server.textDocumentService.codeAction(params).await() ?: return emptyList()
        return result.mapNotNull { either ->
            if (either.isRight) either.right else null
        }
    }

    /**
     * 특정 diagnostic에 대한 QuickFix code action을 가져온다.
     */
    suspend fun getQuickFixes(filePath: String, diagnostic: Diagnostic): List<CodeAction> {
        requireCapability("textDocument/codeAction")
        ensureFileSync(filePath)
        val params = CodeActionParams(
            TextDocumentIdentifier(fileUri(filePath)),
            diagnostic.range,
            CodeActionContext(listOf(diagnostic), listOf(CodeActionKind.QuickFix))
        )
        val result = server.textDocumentService.codeAction(params).await() ?: return emptyList()
        return result.mapNotNull { either ->
            if (either.isRight) either.right else null
        }
    }

    /**
     * CodeAction을 적용한다.
     * 1) WorkspaceEdit가 있으면 파일에 직접 적용
     * 2) Command가 있으면 서버에서 실행
     */
    suspend fun applyAction(action: CodeAction) {
        action.edit?.let { applyWorkspaceEdit(it) }
        action.command?.let { cmd ->
            server.workspaceService.executeCommand(
                ExecuteCommandParams(cmd.command, cmd.arguments)
            ).await()
        }
    }

    private fun applyWorkspaceEdit(edit: WorkspaceEdit) {
        val changes = edit.changes ?: return
        for ((uri, edits) in changes) {
            val path = uriToPath(uri)
            val file = File(path)
            if (!file.exists()) continue

            val lines = file.readText().lines().toMutableList()
            // 아래에서 위로 적용하여 offset 보존
            val sorted = edits.sortedByDescending { it.range.start.line }
            for (textEdit in sorted) {
                applyTextEdit(lines, textEdit)
            }
            file.writeText(lines.joinToString("\n"))
        }
    }

    private fun applyTextEdit(lines: MutableList<String>, edit: TextEdit) {
        val startLine = edit.range.start.line
        val startChar = edit.range.start.character
        val endLine = edit.range.end.line
        val endChar = edit.range.end.character

        if (startLine >= lines.size) return

        val prefix = if (startChar <= lines[startLine].length) {
            lines[startLine].substring(0, startChar)
        } else lines[startLine]

        val suffix = if (endLine < lines.size && endChar <= lines[endLine].length) {
            lines[endLine].substring(endChar)
        } else ""

        val removeCount = (endLine - startLine + 1).coerceAtMost(lines.size - startLine)
        repeat(removeCount) {
            if (startLine < lines.size) lines.removeAt(startLine)
        }

        val newContent = prefix + edit.newText + suffix
        val newLines = newContent.split("\n")
        newLines.reversed().forEach { line ->
            lines.add(startLine, line)
        }
    }

    // --- File Sync ---

    private fun ensureFileSync(filePath: String) {
        val uri = fileUri(filePath)
        val content = File(filePath).readText()

        if (!fileTracker.isOpen(uri)) {
            server.textDocumentService.didOpen(
                DidOpenTextDocumentParams(
                    TextDocumentItem(uri, "kotlin", 1, content)
                )
            )
            fileTracker.markOpened(uri)
        } else {
            val version = fileTracker.incrementVersion(uri)
            server.textDocumentService.didChange(
                DidChangeTextDocumentParams(
                    VersionedTextDocumentIdentifier(uri, version),
                    listOf(TextDocumentContentChangeEvent(content))
                )
            )
        }
    }

    // --- Initialize ---

    private suspend fun initialize() {
        val capabilities = ClientCapabilities().apply {
            textDocument = TextDocumentClientCapabilities().apply {
                synchronization = SynchronizationCapabilities().apply {
                    didSave = true
                    dynamicRegistration = false
                }
                codeAction = CodeActionCapabilities().apply {
                    dynamicRegistration = false
                }
            }
        }
        val params = InitializeParams().apply {
            processId = ProcessHandle.current().pid().toInt()
            rootUri = fileUri(projectRoot.toAbsolutePath().normalize().toString())
            this.capabilities = capabilities
        }

        val result = withTimeout(5.minutes) {
            server.initialize(params).await()
        }
        serverCapabilities = result.capabilities
        log.info("LSP server capabilities: definition=${result.capabilities.definitionProvider != null}, " +
            "hover=${result.capabilities.hoverProvider != null}")

        server.initialized(InitializedParams())
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
            val filePath = testFile.toAbsolutePath().normalize().toString()
            ensureFileSync(filePath)

            // Basic syntax check — documentSymbol
            val symbolParams = DocumentSymbolParams(TextDocumentIdentifier(fileUri(filePath)))
            server.textDocumentService.documentSymbol(symbolParams).await()
            log.info("LSP verify: documentSymbol OK")

            // Semantic check — try goToDefinition on a known symbol
            if (supports("textDocument/definition")) {
                try {
                    val content = testFile.toFile().readText()
                    val lines = content.lines()
                    val targetLine = lines.indexOfFirst { line ->
                        val trimmed = line.trim()
                        trimmed.startsWith("class ") || trimmed.startsWith("fun ") ||
                            trimmed.startsWith("object ") || trimmed.startsWith("interface ")
                    }
                    if (targetLine >= 0) {
                        val line = lines[targetLine]
                        val keyword = line.trimStart()
                        val nameStart = keyword.indexOfFirst { it == ' ' } + 1
                        val char = line.indexOf(keyword[nameStart])
                        val defParams = DefinitionParams(
                            TextDocumentIdentifier(fileUri(filePath)),
                            Position(targetLine, char)
                        )
                        server.textDocumentService.definition(defParams).await()
                        log.info("LSP verify: goToDefinition OK (semantic analysis working)")
                    }
                } catch (e: Exception) {
                    log.warning("LSP verify: goToDefinition failed — semantic analysis may be limited: ${e.message}")
                }
            }

            log.info("LSP verify: OK")
        } catch (e: Exception) {
            log.warning("LSP verify failed: ${e.message}")
            initialized = false
        }
    }

    // --- Capability Check ---

    fun supports(method: String): Boolean {
        val caps = serverCapabilities ?: return false
        return when (method) {
            "textDocument/definition" -> caps.definitionProvider?.let {
                it.isLeft && it.left == true || it.isRight
            } ?: false
            "textDocument/references" -> caps.referencesProvider?.let {
                it.isLeft && it.left == true || it.isRight
            } ?: false
            "textDocument/hover" -> caps.hoverProvider?.let {
                it.isLeft && it.left == true || it.isRight
            } ?: false
            "textDocument/documentSymbol" -> caps.documentSymbolProvider?.let {
                it.isLeft && it.left == true || it.isRight
            } ?: false
            "textDocument/implementation" -> caps.implementationProvider != null
            "textDocument/diagnostic" -> caps.diagnosticProvider != null
            "textDocument/codeAction" -> caps.codeActionProvider?.let {
                it.isLeft && it.left == true || it.isRight
            } ?: false
            else -> true
        }
    }

    private fun requireCapability(method: String) {
        if (!supports(method)) {
            throw LspException("LSP server does not support '$method'")
        }
    }

    // --- Helpers ---

    private fun extractHoverContent(hover: Hover): String? {
        val contents = hover.contents
        return when {
            contents.isLeft -> {
                contents.left.joinToString("\n") { item ->
                    if (item.isLeft) item.left else item.right.value
                }
            }
            contents.isRight -> {
                contents.right.value
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
