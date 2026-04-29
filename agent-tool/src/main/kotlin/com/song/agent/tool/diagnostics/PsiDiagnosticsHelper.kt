package com.song.agent.tool.diagnostics

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer.DaemonListener
import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerEx
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import kotlinx.coroutines.CompletableDeferred
import kotlin.time.Duration.Companion.seconds
import java.util.concurrent.atomic.AtomicBoolean

private const val MAX_DIAGNOSTICS_REPORTED = 20
private val DIAGNOSTICS_TIMEOUT = 30.seconds
private val MIN_REPORTED_SEVERITY = HighlightSeverity.ERROR
private val IGNORED_INSPECTION_IDS = setOf(
    "FunctionName",
    "PackageDirectoryMismatch",
)

internal suspend fun runPostEditDiagnostics(
    project: Project,
    vFile: VirtualFile,
    focusLineRange: IntRange? = null,
    baseline: DiagnosticsBaseline? = null,
): String {
    if (project.isDisposed) return ""
    println(
        "[smoker-diag] start file=${vFile.path} focusLineRange=full requestedRange=$focusLineRange baseline=${baseline?.issues?.size ?: 0}"
    )

    val dumbService = DumbService.getInstance(project)
    if (dumbService.isDumb) {
        println("[smoker-diag] project is dumb; waiting for smart mode")
        dumbService.waitForSmartMode()
    }

    reloadEditorDocumentFromDisk(vFile)

    val ctx = ReadAction.compute<DiagnosticsContext?, Throwable> {
        val psiFile = PsiManager.getInstance(project).findFile(vFile) ?: return@compute null
        val document = FileDocumentManager.getInstance().getDocument(vFile) ?: return@compute null
        DiagnosticsContext(psiFile, document)
    } ?: return "\n\n[diagnostics] unavailable"
    println("[smoker-diag] context ready psi=${ctx.psiFile.virtualFile.path} textLength=${ctx.document.textLength}")

    val issues = waitForFreshDiagnostics(project, ctx)
    println("[smoker-diag] collected issues=${issues.size}")

    val filteredIssues = baseline?.let {
        diffNewIssues(ctx.document, issues, it)
    } ?: issues
    println("[smoker-diag] new issues after baseline diff=${filteredIssues.size}")

    return ReadAction.compute<String, Throwable> {
        formatDiagnosticsSummary(ctx.document, filteredIssues)
    }
}

internal suspend fun captureDiagnosticsBaseline(
    project: Project,
    vFile: VirtualFile,
): DiagnosticsBaseline? {
    if (project.isDisposed) return null
    println("[smoker-diag] capture baseline file=${vFile.path}")

    val dumbService = DumbService.getInstance(project)
    if (dumbService.isDumb) {
        println("[smoker-diag] project is dumb; waiting for smart mode before baseline")
        dumbService.waitForSmartMode()
    }

    reloadEditorDocumentFromDisk(vFile)

    val ctx = ReadAction.compute<DiagnosticsContext?, Throwable> {
        val psiFile = PsiManager.getInstance(project).findFile(vFile) ?: return@compute null
        val document = FileDocumentManager.getInstance().getDocument(vFile) ?: return@compute null
        DiagnosticsContext(psiFile, document)
    } ?: return null

    val issues = waitForFreshDiagnostics(project, ctx)
    val snapshots = ReadAction.compute<List<DiagnosticSnapshot>, Throwable> {
        issues.map { buildDiagnosticSnapshot(ctx.document, it) }
    }
    println("[smoker-diag] baseline captured issues=${snapshots.size}")
    return DiagnosticsBaseline(snapshots)
}

private suspend fun waitForFreshDiagnostics(
    project: Project,
    ctx: DiagnosticsContext,
): List<HighlightInfo> {
    val daemon = DaemonCodeAnalyzerEx.getInstanceEx(project)
    val finishedSignal = CompletableDeferred<Unit>()
    val connection = project.messageBus.connect()
    val started = AtomicBoolean(false)
    val targetEditors = ensureTargetEditors(project, ctx.psiFile.virtualFile)
    println("[smoker-diag] targetEditors=${targetEditors.size} file=${ctx.psiFile.virtualFile.path}")

    fun tryComplete(force: Boolean = false) {
        if (finishedSignal.isCompleted) return
        val finished = runCatching {
            ReadAction.compute<Boolean, Throwable> {
                daemon.isErrorAnalyzingFinished(ctx.psiFile)
            }
        }.getOrDefault(false)
        println("[smoker-diag] tryComplete force=$force started=${started.get()} finished=$finished")
        if (finished && (force || started.get())) {
            finishedSignal.complete(Unit)
        }
    }

    fun isRelevant(fileEditors: Collection<FileEditor>): Boolean {
        val editorFiles = fileEditors.mapNotNull { editorVirtualFile(it) }
        val relevant = ctx.psiFile.virtualFile in editorFiles
        println(
            "[smoker-diag] isRelevant editors=${fileEditors.size} relevant=$relevant editorFiles=${editorFiles.joinToString { it.path }}"
        )
        return relevant
    }

    connection.subscribe(
        DaemonCodeAnalyzer.DAEMON_EVENT_TOPIC,
        object : DaemonListener {
            override fun daemonStarting(fileEditors: Collection<FileEditor>) {
                println("[smoker-diag] daemonStarting editors=${fileEditors.size}")
                if (isRelevant(fileEditors)) {
                    started.set(true)
                    println("[smoker-diag] daemonStarting matched target file")
                }
            }

            override fun daemonFinished() {
                println("[smoker-diag] daemonFinished(no editors)")
                tryComplete()
            }

            override fun daemonFinished(fileEditors: Collection<FileEditor>) {
                println("[smoker-diag] daemonFinished editors=${fileEditors.size}")
                if (isRelevant(fileEditors)) {
                    tryComplete(force = true)
                }
            }

            override fun daemonCanceled(reason: String, fileEditors: Collection<FileEditor>) {
                println("[smoker-diag] daemonCanceled reason=$reason editors=${fileEditors.size}")
                if (isRelevant(fileEditors)) {
                    tryComplete(force = true)
                }
            }
        }
    )

    try {
        ApplicationManager.getApplication().invokeAndWait {
            PsiDocumentManager.getInstance(project).commitDocument(ctx.document)
            println("[smoker-diag] committed document; restarting daemon")
            DaemonCodeAnalyzer.getInstance(project).restart(ctx.psiFile)
        }
        runCatching {
            kotlinx.coroutines.withTimeout(DIAGNOSTICS_TIMEOUT) {
                finishedSignal.await()
            }
        }.onFailure {
            println("[smoker-diag] wait timeout or failure=${it.message}")
        }
    } finally {
        println("[smoker-diag] disconnecting daemon listener")
        connection.disconnect()
    }

    return collectCachedDiagnostics(project, ctx.document)
}

private fun editorVirtualFile(editor: FileEditor): VirtualFile? {
    return when (editor) {
        is TextEditor -> FileDocumentManager.getInstance().getFile(editor.editor.document)
        else -> null
    }
}

private fun ensureTargetEditors(project: Project, vFile: VirtualFile): Set<FileEditor> {
    var editors = emptySet<FileEditor>()
    ApplicationManager.getApplication().invokeAndWait {
        val fileEditorManager = FileEditorManager.getInstance(project)
        editors = fileEditorManager.getAllEditors(vFile).toSet()
        println("[smoker-diag] existingEditors=${editors.size} file=${vFile.path}")
        if (editors.isEmpty()) {
            val openedEditors = fileEditorManager.openFile(vFile, false).toSet()
            println("[smoker-diag] openFile invoked openedEditors=${openedEditors.size} file=${vFile.path}")
            editors = if (openedEditors.isNotEmpty()) {
                openedEditors
            } else {
                fileEditorManager.getAllEditors(vFile).toSet()
            }
            println("[smoker-diag] editorsAfterOpen=${editors.size} file=${vFile.path}")
        }
    }
    return editors
}

private fun collectCachedDiagnostics(
    project: Project,
    document: Document,
): List<HighlightInfo> {
    return ReadAction.compute<List<HighlightInfo>, Throwable> {
        val infos = mutableListOf<HighlightInfo>()
        DaemonCodeAnalyzerEx.processHighlights(
            document,
            project,
            MIN_REPORTED_SEVERITY,
            0,
            document.textLength,
        ) { info ->
            if (shouldReport(info)) {
                infos += info
            }
            true
        }
        println("[smoker-diag] processHighlights focusLineRange=full totalFiltered=${infos.size}")
        infos.forEachIndexed { index, info ->
            val line = document.getLineNumber(info.startOffset) + 1
            println(
                "[smoker-diag] info[$index] severity=${info.severity} line=$line toolId=${info.inspectionToolId} desc=${info.description}"
            )
        }
        infos
            .distinctBy { listOf(it.startOffset, it.endOffset, it.severity.name, it.description.orEmpty()) }
            .sortedWith(compareBy<HighlightInfo> { it.startOffset }.thenByDescending { it.severity.myVal })
    }
}

private fun shouldReport(
    info: HighlightInfo,
): Boolean {
    if (info.severity < MIN_REPORTED_SEVERITY) return false

    val inspectionId = info.inspectionToolId
    if (inspectionId != null && inspectionId in IGNORED_INSPECTION_IDS) return false
    return true
}

private fun reloadEditorDocumentFromDisk(vFile: VirtualFile) {
    ApplicationManager.getApplication().invokeAndWait {
        val fileDocumentManager = FileDocumentManager.getInstance()
        val document = fileDocumentManager.getCachedDocument(vFile) ?: return@invokeAndWait
        if (!fileDocumentManager.isDocumentUnsaved(document)) {
            fileDocumentManager.reloadFromDisk(document)
        }
    }
}

private data class DiagnosticsContext(
    val psiFile: com.intellij.psi.PsiFile,
    val document: Document,
)

internal data class DiagnosticsBaseline(
    val issues: List<DiagnosticSnapshot>,
)

internal data class DiagnosticSnapshot(
    val severity: String,
    val inspectionToolId: String?,
    val description: String,
    val highlightText: String,
)

private fun diffNewIssues(
    document: Document,
    issues: List<HighlightInfo>,
    baseline: DiagnosticsBaseline,
): List<HighlightInfo> {
    val remaining = baseline.issues
        .groupingBy { it }
        .eachCount()
        .toMutableMap()

    return issues.filter { info ->
        val snapshot = buildDiagnosticSnapshot(document, info)
        val count = remaining[snapshot] ?: 0
        if (count > 0) {
            remaining[snapshot] = count - 1
            false
        } else {
            true
        }
    }
}

private fun buildDiagnosticSnapshot(
    document: Document,
    info: HighlightInfo,
): DiagnosticSnapshot {
    val endOffset = info.endOffset.coerceAtMost(document.textLength).coerceAtLeast(info.startOffset)
    val highlightText = document.getText(com.intellij.openapi.util.TextRange(info.startOffset, endOffset))
        .trim()
        .replace(Regex("\\s+"), " ")
    return DiagnosticSnapshot(
        severity = info.severity.name,
        inspectionToolId = info.inspectionToolId,
        description = info.description.orEmpty(),
        highlightText = highlightText,
    )
}

private fun formatDiagnosticsSummary(document: Document, diags: List<HighlightInfo>): String {
    if (diags.isEmpty()) return "\n\n[diagnostics] no issues"
    return buildString {
        append("\n\n")
        append("[diagnostics] ")
        append(diags.size)
        appendLine(" issue(s):")
        diags.take(MAX_DIAGNOSTICS_REPORTED).forEachIndexed { i, info ->
            val line = document.getLineNumber(info.startOffset) + 1
            val column = info.startOffset - document.getLineStartOffset(line - 1) + 1
            val severity = info.severity.name.uppercase()
            val description = info.description ?: ""
            appendLine("${i + 1}. [$severity] $line:$column: $description")
        }
        if (diags.size > MAX_DIAGNOSTICS_REPORTED) {
            append("(${diags.size - MAX_DIAGNOSTICS_REPORTED} more)")
        }
    }.trimEnd()
}
