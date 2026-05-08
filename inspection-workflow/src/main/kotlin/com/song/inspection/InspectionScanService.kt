package com.song.inspection

import com.intellij.codeInspection.GlobalInspectionContext
import com.intellij.codeInspection.InspectionEngine
import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ex.LocalInspectionToolWrapper
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.profile.codeInspection.InspectionProjectProfileManager
import com.intellij.psi.PsiManager
import com.song.sarif.Finding
import java.io.PrintWriter
import java.io.StringWriter

data class InspectionScanResult(
    val findings: List<Finding>,
)

class InspectionScanService(
    private val project: Project,
) {
    fun scan(context: InspectionRunContext): InspectionScanResult {
        println(
            "[inspection-scan] start inspections=${context.inspectionIds.joinToString(",")} " +
                "root=${context.projectRoot.absolutePath}"
        )
        try {
            val dumb = DumbService.getInstance(project)
            println("[inspection-scan] dumb=${dumb.isDumb}; waiting for smart mode...")
            dumb.waitForSmartMode()
            println("[inspection-scan] smart mode reached")
        } catch (t: Throwable) {
            logException("scan:waitForSmartMode", t)
            throw t
        }

        try {
            registerEntryPointAnnotations()
        } catch (t: Throwable) {
            logException("scan:registerEntryPointAnnotations", t)
        }

        val baseDir = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(context.projectRoot)
            ?: error("project root not found in VFS: ${context.projectRoot}")

        val sourceFiles = try {
            collectSourceFiles(baseDir)
        } catch (t: Throwable) {
            logException("scan:collectSourceFiles", t)
            throw t
        }
        println("[inspection-scan] target files=${sourceFiles.size}")

        val profile = try {
            InspectionProjectProfileManager.getInstance(project).currentProfile
        } catch (t: Throwable) {
            logException("scan:profileLookup", t)
            throw t
        }
        val globalCtx: GlobalInspectionContext = try {
            InspectionManager.getInstance(project).createNewGlobalContext()
        } catch (t: Throwable) {
            logException("scan:createGlobalContext", t)
            throw t
        }

        val findings = ArrayList<Finding>(256)
        for (id in context.inspectionIds) {
            val wrapper = try {
                profile.getInspectionTool(id, project)
            } catch (t: Throwable) {
                logException("scan:getInspectionTool id=$id", t)
                continue
            }
            if (wrapper == null) {
                println("[inspection-scan] skip $id (not found in current profile)")
                continue
            }
            if (wrapper !is LocalInspectionToolWrapper) {
                println("[inspection-scan] skip $id (not a local inspection: ${wrapper.javaClass.simpleName})")
                continue
            }

            var perInspection = 0
            var perFileFailures = 0
            for (vf in sourceFiles) {
                val fileFindings = try {
                    inspectFileToFindings(vf, wrapper, globalCtx)
                } catch (e: IndexNotReadyException) {
                    perFileFailures++
                    println("[inspection-scan] IndexNotReady id=$id file=${vf.path} msg=${e.message}")
                    emptyList()
                } catch (t: Throwable) {
                    perFileFailures++
                    logException("scan:inspectFileToFindings id=$id file=${vf.path}", t)
                    emptyList()
                }
                findings += fileFindings
                perInspection += fileFindings.size
            }
            println("[inspection-scan] ===== $id findings=$perInspection perFileFailures=$perFileFailures =====")
        }

        println("[inspection-scan] total findings=${findings.size}")
        return InspectionScanResult(findings = findings)
    }

    private fun collectSourceFiles(baseDir: VirtualFile): List<VirtualFile> {
        val fileIndex = ProjectFileIndex.getInstance(project)
        val out = ArrayList<VirtualFile>(512)
        ReadAction.compute<Unit, Throwable> {
            VfsUtilCore.iterateChildrenRecursively(baseDir, null) { vf ->
                if (!vf.isDirectory
                    && vf.extension == "kt"
                    && fileIndex.isInContent(vf)
                    && !fileIndex.isExcluded(vf)
                    && !fileIndex.isInTestSourceContent(vf)
                ) {
                    out += vf
                }
                true
            }
        }
        return out
    }

    private fun inspectFileToFindings(
        vf: VirtualFile,
        wrapper: LocalInspectionToolWrapper,
        globalCtx: GlobalInspectionContext,
    ): List<Finding> {
        return ReadAction.compute<List<Finding>, Throwable> {
            val psi = PsiManager.getInstance(project).findFile(vf) ?: return@compute emptyList()
            val descriptors = InspectionEngine.runInspectionOnFile(psi, wrapper, globalCtx)
            if (descriptors.isEmpty()) return@compute emptyList()
            val document = FileDocumentManager.getInstance().getDocument(vf)
            val absPath = vf.path
            descriptors.map { toFinding(it, wrapper, absPath, document) }
        }
    }

    private fun toFinding(
        descriptor: ProblemDescriptor,
        wrapper: LocalInspectionToolWrapper,
        absolutePath: String,
        document: Document?,
    ): Finding {
        val ruleId = wrapper.shortName
        val level = wrapper.defaultLevel.severity.name
        val message = stripHtml(descriptor.descriptionTemplate)

        val element = descriptor.psiElement
        val textOffset = element?.textRange?.startOffset ?: -1
        val endOffset = element?.textRange?.endOffset ?: textOffset

        val (startLine, startColumn) = lineColumn(document, textOffset, descriptor.lineNumber)
        val (endLine, endColumn) = lineColumn(document, endOffset, descriptor.lineNumber)

        return Finding(
            ruleId = ruleId,
            level = level,
            message = message,
            uriBaseId = null,
            uri = null,
            absolutePath = absolutePath,
            startLine = startLine,
            startColumn = startColumn,
            endLine = endLine,
            endColumn = endColumn,
        )
    }

    private fun lineColumn(document: Document?, offset: Int, fallbackLine: Int): Pair<Int?, Int?> {
        if (document == null || offset < 0 || offset > document.textLength) {
            val fallback = if (fallbackLine >= 0) fallbackLine + 1 else null
            return fallback to null
        }
        val line = document.getLineNumber(offset)
        val col = offset - document.getLineStartOffset(line)
        return (line + 1) to (col + 1)
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

    private fun registerEntryPointAnnotations() {
        InspectionEntryPoints.register(project)
    }

    private fun logException(stage: String, t: Throwable) {
        val sw = StringWriter()
        t.printStackTrace(PrintWriter(sw))
        println("[inspection-scan] EXCEPTION at $stage: ${t.javaClass.name}: ${t.message}")
        println(sw.toString().trimEnd())
    }
}
