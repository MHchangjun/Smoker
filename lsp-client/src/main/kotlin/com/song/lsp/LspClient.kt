package com.song.lsp

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

    // kotlin-lsp is pull-only; this notification is never sent. Implemented as no-op
    // to satisfy the LanguageClient interface contract.
    override fun publishDiagnostics(diagnostics: PublishDiagnosticsParams?) {}

    // kotlin-lsp does not emit $/progress notifications (verified against the kotlin-lsp
    // source: no WorkDoneProgress emission anywhere). Implemented as no-ops to satisfy
    // the LanguageClient interface contract.
    override fun createProgress(params: WorkDoneProgressCreateParams): CompletableFuture<Void> {
        return CompletableFuture.completedFuture(null)
    }

    override fun notifyProgress(params: ProgressParams?) {}

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
        requireCapability("textDocument/diagnostic")
        ensureFileSync(filePath)
        val uri = fileUri(filePath)
        val pullStart = System.currentTimeMillis()
        val params = DocumentDiagnosticParams(TextDocumentIdentifier(uri))
        val report = withTimeout(PULL_TIMEOUT) {
            server.textDocumentService.diagnostic(params).await()
        }
        val items = if (report.isRelatedFullDocumentDiagnosticReport) {
            report.relatedFullDocumentDiagnosticReport?.items ?: emptyList()
        } else emptyList()
        log.fine("LSP pull diagnostics [$uri] returned ${items.size} in ${System.currentTimeMillis() - pullStart}ms")
        return items
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
            fileTracker.markOpened(uri, content)
            return
        }

        if (!fileTracker.hasChanged(uri, content)) return

        val version = fileTracker.incrementVersion(uri, content)
        server.textDocumentService.didChange(
            DidChangeTextDocumentParams(
                VersionedTextDocumentIdentifier(uri, version),
                listOf(TextDocumentContentChangeEvent(content))
            )
        )
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
                // kotlin-lsp uses pull-based diagnostics — must be advertised so the
                // server enables the textDocument/diagnostic endpoint.
                diagnostic = DiagnosticCapabilities().apply {
                    dynamicRegistration = false
                    relatedDocumentSupport = false
                }
            }
            // kotlin-lsp gates `$/progress` notifications on this capability — without it
            // the server skips workDoneProgress entirely (matches what we see in the
            // trace: "did not emit workDoneProgress").
            window = WindowClientCapabilities().apply {
                workDoneProgress = true
            }
        }
        val rootUriStr = fileUri(projectRoot.toAbsolutePath().normalize().toString())
        val params = InitializeParams().apply {
            processId = ProcessHandle.current().pid().toInt()
            rootUri = rootUriStr
            this.capabilities = capabilities
            workspaceFolders = listOf(
                WorkspaceFolder(rootUriStr, projectRoot.fileName?.toString().orEmpty())
            )
            // kotlin-lsp 's closed-source initialize handler reads `buildTools` from
            // initializationOptions to pick a workspace importer (Gradle/Maven). Without
            // this payload the server skips workspace import — modules and source roots
            // never get attached, so ProblemHighlightFilter blocks the K2/FIR compiler
            // diagnostics provider and only PSI-only inspections come back.
            // Format mirrors kotlin-vscode/src/lspClient.ts:294.
            initializationOptions = mapOf(
                "buildTools" to mapOf(rootUriStr to "gradle")
            )
        }

        val result = withTimeout(5.minutes) {
            server.initialize(params).await()
        }
        serverCapabilities = result.capabilities
        log.info("LSP server capabilities: definition=${result.capabilities.definitionProvider != null}, " +
            "hover=${result.capabilities.hoverProvider != null}, " +
            "diagnostic=${result.capabilities.diagnosticProvider != null}")

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
            // kotlin-lsp's `initialize` blocks until Gradle/Maven import + workspace model
            // load is complete, so by the time verify() runs the project is already ready
            // for diagnostic queries. No additional warmup needed.
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
        private val PULL_TIMEOUT: Duration = 30.seconds

        fun fileUri(path: String): String {
            val normalized = if (path.startsWith("/")) path else "/$path"
            return "file://$normalized"
        }

        fun uriToPath(uri: String): String {
            return uri.removePrefix("file://")
        }
    }
}
