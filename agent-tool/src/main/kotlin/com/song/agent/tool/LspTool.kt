package com.song.agent.tool

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription
import com.intellij.lang.LanguageDocumentation
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiShortNamesCache
import com.intellij.psi.search.searches.DefinitionsScopedSearch
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File

class LspTool(
    private val project: Project,
) : Tool<LspTool.Args, LspTool.Result>(
    argsSerializer = Args.serializer(),
    resultSerializer = Result.serializer(),
    name = ToolNames.LSP,
    description = """
Language Server Protocol (LSP) tool for code intelligence: definitions, references, hover, symbols.

Usage:
- ALWAYS use LSP as the PRIMARY tool for code intelligence queries when available. Do NOT use grep_search or glob first.
- goToDefinition, findReferences, goToImplementation, hover require filePath + line + character (1-based).
- hover returns documentation/type info for the symbol at the given position (LSP textDocument/hover).
- workspaceSymbol requires query (use when user asks "where is X defined?" without specifying a file).
""".trimIndent()
) {
    @Serializable
    data class Args(
        @property:LLMDescription("LSP operation to execute.")
        val operation: LspOperation,
        @property:LLMDescription("File path (absolute).")
        val filePath: String = "",
        @property:LLMDescription("1-based line number for the target location.")
        val line: Int? = null,
        @property:LLMDescription("1-based character/column number for the target location.")
        val character: Int? = null,
        @property:LLMDescription("Include the declaration itself when looking up references.")
        val includeDeclaration: Boolean? = null,
        @property:LLMDescription("Symbol query for workspace symbol search.")
        val query: String? = null,
        @property:LLMDescription("Optional maximum number of results to return.")
        val limit: Int? = null,
    )

    @Serializable
    enum class LspOperation {
        @SerialName("goToDefinition")
        GO_TO_DEFINITION,
        @SerialName("findReferences")
        FIND_REFERENCES,
        @SerialName("documentSymbol")
        DOCUMENT_SYMBOL,
        @SerialName("workspaceSymbol")
        WORKSPACE_SYMBOL,
        @SerialName("goToImplementation")
        GO_TO_IMPLEMENTATION,
        @SerialName("hover")
        HOVER,
    }

    @Serializable
    data class Result(val content: String)

    override suspend fun execute(args: Args): Result {
        val limit = args.limit ?: DEFAULT_LIMIT
        return try {
            when (args.operation) {
                LspOperation.GO_TO_DEFINITION -> goToDefinition(args, limit)
                LspOperation.FIND_REFERENCES -> findReferences(args, limit)
                LspOperation.DOCUMENT_SYMBOL -> documentSymbol(args, limit)
                LspOperation.WORKSPACE_SYMBOL -> workspaceSymbol(args, limit)
                LspOperation.GO_TO_IMPLEMENTATION -> goToImplementation(args, limit)
                LspOperation.HOVER -> hover(args)
            }
        } catch (e: Exception) {
            Result("LSP ${args.operation.name} failed: ${e.message ?: "unknown error"}")
        }
    }

    private fun goToDefinition(args: Args, limit: Int): Result {
        requirePosition(args)
        val locations = ReadAction.compute<List<Loc>, Throwable> {
            val ctx = openContext(args.filePath) ?: return@compute emptyList()
            val offset = toOffset(ctx.document, args.line!!, args.character!!) ?: return@compute emptyList()
            resolveTargets(ctx.psiFile, offset).mapNotNull { locationOf(it) }
        }
        if (locations.isEmpty()) return Result("No definitions found.")
        return Result(
            buildString {
                appendLine("Definitions:")
                locations.take(limit).forEachIndexed { i, loc ->
                    appendLine("${i + 1}. ${loc.format()}")
                }
            }.trim()
        )
    }

    private fun findReferences(args: Args, limit: Int): Result {
        requirePosition(args)
        val includeDeclaration = args.includeDeclaration ?: false
        val locations = ReadAction.compute<List<Loc>, Throwable> {
            val ctx = openContext(args.filePath) ?: return@compute emptyList()
            val offset = toOffset(ctx.document, args.line!!, args.character!!) ?: return@compute emptyList()
            val target = resolveTargets(ctx.psiFile, offset).firstOrNull()
                ?: declarationAt(ctx.psiFile, offset)
                ?: return@compute emptyList()

            val refs = ReferencesSearch.search(target).findAll().mapNotNull {
                locationOf(it.element, it.rangeInElement.startOffset)
            }
            val all = if (includeDeclaration) listOfNotNull(locationOf(target)) + refs else refs
            all.filterNot { isGeneratedPath(it.path) }
                .distinctBy { Triple(it.path, it.line, it.col) }
        }
        if (locations.isEmpty()) return Result("No references found.")
        return Result(
            buildString {
                appendLine("References:")
                locations.take(limit).forEachIndexed { i, loc ->
                    appendLine("${i + 1}. ${loc.format()}")
                }
            }.trim()
        )
    }

    private fun documentSymbol(args: Args, limit: Int): Result {
        requireFilePath(args)
        val rendered = ReadAction.compute<String?, Throwable> {
            val ctx = openContext(args.filePath) ?: return@compute null
            val sb = StringBuilder()
            sb.appendLine("Document symbols:")
            val counter = intArrayOf(0)
            renderSymbols(ctx.psiFile, ctx.document, depth = 0, sb = sb, counter = counter, limit = limit)
            if (counter[0] == 0) null else sb.toString().trim()
        }
        return Result(rendered ?: "No document symbols found.")
    }

    private fun workspaceSymbol(args: Args, limit: Int): Result {
        val query = args.query
            ?: return Result("LSP workspaceSymbol failed: query is required.")
        if (query.isBlank()) return Result("LSP workspaceSymbol failed: query is required.")

        val rendered = ReadAction.compute<String, Throwable> {
            val cache = PsiShortNamesCache.getInstance(project)
            val scope = GlobalSearchScope.projectScope(project)
            val collected = mutableListOf<SymbolHit>()

            fun collect(name: String, kind: String, elements: Array<out PsiElement>) {
                if (collected.size >= limit) return
                elements.forEach { el ->
                    if (collected.size >= limit) return@forEach
                    val loc = locationOf(el) ?: return@forEach
                    val displayName = (el as? PsiNamedElement)?.name ?: name
                    collected += SymbolHit(displayName, kind, containerOf(el), loc)
                }
            }

            cache.allClassNames
                .filter { it.contains(query, ignoreCase = true) }
                .forEach { collect(it, "Class", cache.getClassesByName(it, scope) as Array<out PsiElement>) }

            if (collected.size < limit) {
                cache.allMethodNames
                    .filter { it.contains(query, ignoreCase = true) }
                    .forEach { collect(it, "Method", cache.getMethodsByName(it, scope) as Array<out PsiElement>) }
            }

            if (collected.size < limit) {
                cache.allFieldNames
                    .filter { it.contains(query, ignoreCase = true) }
                    .forEach { collect(it, "Field", cache.getFieldsByName(it, scope) as Array<out PsiElement>) }
            }

            if (collected.isEmpty()) {
                "No symbols found for query \"$query\"."
            } else {
                buildString {
                    appendLine("Found ${collected.size} symbol(s) for query \"$query\":")
                    collected.forEachIndexed { i, hit ->
                        val container = hit.container?.let { " in $it" } ?: ""
                        appendLine("${i + 1}. ${hit.name} (${hit.kind})$container - ${hit.location.format()}")
                    }
                }.trim()
            }
        }
        return Result(rendered)
    }

    private fun goToImplementation(args: Args, limit: Int): Result {
        requirePosition(args)
        val locations = ReadAction.compute<List<Loc>, Throwable> {
            val ctx = openContext(args.filePath) ?: return@compute emptyList()
            val offset = toOffset(ctx.document, args.line!!, args.character!!) ?: return@compute emptyList()
            val target = resolveTargets(ctx.psiFile, offset).firstOrNull()
                ?: declarationAt(ctx.psiFile, offset)
                ?: return@compute emptyList()
            DefinitionsScopedSearch.search(target).findAll().mapNotNull { locationOf(it) }
        }
        if (locations.isEmpty()) return Result("No implementations found.")
        return Result(
            buildString {
                appendLine("Implementations:")
                locations.take(limit).forEachIndexed { i, loc ->
                    appendLine("${i + 1}. ${loc.format()}")
                }
            }.trim()
        )
    }

    private fun hover(args: Args): Result {
        requirePosition(args)
        val content = ReadAction.compute<String?, Throwable> {
            val ctx = openContext(args.filePath) ?: return@compute null
            val offset = toOffset(ctx.document, args.line!!, args.character!!) ?: return@compute null
            val originalElement = ctx.psiFile.findElementAt(offset) ?: return@compute null
            val target = resolveTargets(ctx.psiFile, offset).firstOrNull()
                ?: declarationAt(ctx.psiFile, offset)
                ?: originalElement
            val provider = LanguageDocumentation.INSTANCE.forLanguage(target.language)
                ?: return@compute null
            val quickInfo = runCatching { provider.getQuickNavigateInfo(target, originalElement) }.getOrNull()
            val doc = runCatching { provider.generateDoc(target, originalElement) }.getOrNull()
            listOfNotNull(quickInfo, doc)
                .map(::stripHtml)
                .filter { it.isNotBlank() }
                .joinToString("\n\n")
                .ifBlank { null }
        }
        return if (content == null) Result("No hover information found.")
        else Result("Hover:\n$content")
    }

    private fun isGeneratedPath(path: String): Boolean {
        val normalized = path.replace('\\', '/')
        return GENERATED_PATH_SEGMENTS.any { normalized.contains(it) }
    }

    private fun stripHtml(html: String): String =
        html.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("</p>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("<[^>]+>"), "")
            .replace("&nbsp;", " ")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()

    private fun resolveTargets(psiFile: PsiFile, offset: Int): List<PsiElement> {
        val ref = psiFile.findReferenceAt(offset)
        if (ref != null) {
            return if (ref is PsiPolyVariantReference) {
                ref.multiResolve(false).mapNotNull { it.element }
            } else {
                listOfNotNull(ref.resolve())
            }
        }
        return listOfNotNull(declarationAt(psiFile, offset))
    }

    private fun declarationAt(psiFile: PsiFile, offset: Int): PsiElement? {
        val element = psiFile.findElementAt(offset) ?: return null
        return PsiTreeUtil.getParentOfType(element, PsiNameIdentifierOwner::class.java, false)
            ?: PsiTreeUtil.getParentOfType(element, PsiNamedElement::class.java, false)
    }

    private fun renderSymbols(
        parent: PsiElement,
        document: Document,
        depth: Int,
        sb: StringBuilder,
        counter: IntArray,
        limit: Int,
    ) {
        for (child in parent.children) {
            if (counter[0] >= limit) return
            val info = symbolInfoOf(child)
            if (info != null) {
                val line = lineOf(child, document) ?: continue
                val prefix = "  ".repeat(depth)
                sb.append(prefix).append(info.name).append(" (").append(info.kind).append(") line ")
                    .append(line).append('\n')
                counter[0]++
                renderSymbols(child, document, depth + 1, sb, counter, limit)
            } else {
                renderSymbols(child, document, depth, sb, counter, limit)
            }
        }
    }

    private fun symbolInfoOf(element: PsiElement): SymbolInfo? {
        if (element !is PsiNamedElement) return null
        val name = element.name ?: return null
        val kind = kindOf(element) ?: return null
        return SymbolInfo(name, kind)
    }

    private fun kindOf(element: PsiElement): String? {
        val cls = element.javaClass.simpleName
        return when (cls) {
            "KtClass" -> "Class"
            "KtObjectDeclaration" -> "Object"
            "KtNamedFunction" -> "Function"
            "KtProperty" -> "Property"
            "KtParameter" -> null
            "KtTypeAlias" -> "TypeAlias"
            "KtEnumEntry" -> "EnumMember"
            "KtClassInitializer" -> null
            "KtSecondaryConstructor", "KtPrimaryConstructor" -> "Constructor"
            "PsiClassImpl" -> "Class"
            "PsiMethodImpl" -> "Method"
            "PsiFieldImpl" -> "Field"
            else -> {
                // Fallback: identify common IntelliJ PSI parents by API marker types we already imported.
                when {
                    cls.startsWith("Kt") && cls.endsWith("Declaration") -> "Declaration"
                    else -> null
                }
            }
        }
    }

    private fun containerOf(element: PsiElement): String? {
        val parent = PsiTreeUtil.getParentOfType(element, PsiNamedElement::class.java, true) ?: return null
        return parent.name
    }

    private fun openContext(filePath: String): Ctx? {
        if (filePath.isBlank()) return null
        val target = File(filePath).takeIf { it.isAbsolute } ?: return null
        val vFile: VirtualFile = LocalFileSystem.getInstance().findFileByIoFile(target)
            ?: LocalFileSystem.getInstance().refreshAndFindFileByIoFile(target)
            ?: return null
        val psiFile = PsiManager.getInstance(project).findFile(vFile) ?: return null
        val document = FileDocumentManager.getInstance().getDocument(vFile) ?: return null
        return Ctx(psiFile, document)
    }

    private fun toOffset(document: Document, line: Int, character: Int): Int? {
        val lineIdx = (line - 1).coerceAtLeast(0)
        if (lineIdx >= document.lineCount) return null
        val lineStart = document.getLineStartOffset(lineIdx)
        val lineEnd = document.getLineEndOffset(lineIdx)
        val col = (character - 1).coerceAtLeast(0)
        return (lineStart + col).coerceAtMost(lineEnd)
    }

    private fun locationOf(element: PsiElement, extraOffset: Int = 0): Loc? {
        val file = element.containingFile?.virtualFile ?: return null
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return null
        val anchor = (element as? PsiNameIdentifierOwner)?.nameIdentifier ?: element
        val offset = (anchor.textOffset + extraOffset).coerceIn(0, document.textLength)
        val line = document.getLineNumber(offset)
        val col = offset - document.getLineStartOffset(line)
        return Loc(file.path, line + 1, col + 1)
    }

    private fun lineOf(element: PsiElement, document: Document): Int? {
        val anchor = (element as? PsiNameIdentifierOwner)?.nameIdentifier ?: element
        val offset = anchor.textOffset
        if (offset < 0 || offset > document.textLength) return null
        return document.getLineNumber(offset) + 1
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

    private data class Ctx(val psiFile: PsiFile, val document: Document)
    private data class Loc(val path: String, val line: Int, val col: Int) {
        fun format() = "$path:$line:$col"
    }
    private data class SymbolInfo(val name: String, val kind: String)
    private data class SymbolHit(val name: String, val kind: String, val container: String?, val location: Loc)

    companion object {
        private const val DEFAULT_LIMIT = 20
        private val GENERATED_PATH_SEGMENTS = listOf(
            "/build/",
            "/generated/",
            "/.gradle/",
            "/out/",
        )
    }
}
