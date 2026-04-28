package com.song.agent.tool.diagnostics

import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerEx
import com.intellij.codeInsight.daemon.impl.DaemonProgressIndicator
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInsight.daemon.impl.HighlightingSessionImpl
import com.intellij.codeInsight.multiverse.CodeInsightContextManager
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.util.Computable
import com.intellij.openapi.util.ProperTextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager

private const val MAX_DIAGNOSTICS_REPORTED = 20
private val MIN_REPORTED_SEVERITY = HighlightSeverity.WEAK_WARNING

// Run daemon passes synchronously against the current PSI/document pair so the
// tool can return the same class of issues the editor shows without waiting for
// background daemon polling.
internal fun runPostEditDiagnostics(project: Project, vFile: VirtualFile): String {
    if (project.isDisposed) return ""
    DumbService.getInstance(project).waitForSmartMode()

    reloadEditorDocumentFromDisk(vFile)

    val ctx = ReadAction.compute<DiagnosticsContext?, Throwable> {
        val psiFile = PsiManager.getInstance(project).findFile(vFile) ?: return@compute null
        val document = FileDocumentManager.getInstance().getDocument(vFile) ?: return@compute null
        DiagnosticsContext(psiFile, document)
    } ?: return "\n\n[diagnostics] unavailable"

    val daemon = DaemonCodeAnalyzerEx.getInstanceEx(project)

    // Commit the edited document before running the main highlighting passes.
    ApplicationManager.getApplication().invokeAndWait {
        PsiDocumentManager.getInstance(project).commitDocument(ctx.document)
    }

    val issues = try {
        val indicator = DaemonProgressIndicator()
        indicator.start()
        try {
            ProgressManager.getInstance().runProcess(
                Computable {
                    val codeInsightContext = ReadAction.compute<com.intellij.codeInsight.multiverse.CodeInsightContext, Throwable> {
                        CodeInsightContextManager.getInstance(project).getCodeInsightContext(ctx.psiFile.viewProvider)
                    }
                    val visibleRange = ProperTextRange.create(0, ctx.document.textLength)
                    val colorScheme = EditorColorsManager.getInstance().globalScheme
                    var collected = emptyList<HighlightInfo>()
                    HighlightingSessionImpl.runInsideHighlightingSession(
                        ctx.psiFile,
                        codeInsightContext,
                        colorScheme,
                        visibleRange,
                        false,
                    ) {
                        collected = ReadAction.compute<List<HighlightInfo>, Throwable> {
                            daemon.runMainPasses(ctx.psiFile, ctx.document, indicator)
                                .asSequence()
                                .filter { it.severity >= MIN_REPORTED_SEVERITY }
                                .distinctBy { listOf(it.startOffset, it.endOffset, it.severity.name, it.description.orEmpty()) }
                                .sortedWith(compareBy<HighlightInfo> { it.startOffset }.thenByDescending { it.severity.myVal })
                                .toList()
                        }
                    }
                    collected
                },
                indicator
            )
        } finally {
            indicator.stop()
        }
    } catch (e: Throwable) {
        return "\n\n[diagnostics] failed: ${e.message.orEmpty()}"
    }

    return ReadAction.compute<String, Throwable> {
        formatDiagnosticsSummary(ctx.document, issues)
    }
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

private fun formatDiagnosticsSummary(document: Document, diags: List<HighlightInfo>): String {
    if (diags.isEmpty()) return "\n\n[diagnostics] no issues"
    return buildString {
        append("\n\n")
        append("[diagnostics]")
        append(' ')
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
