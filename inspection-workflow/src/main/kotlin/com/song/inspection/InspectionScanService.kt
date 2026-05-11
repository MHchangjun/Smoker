package com.song.inspection

import com.intellij.analysis.AnalysisScope
import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.ex.InspectionManagerEx
import com.intellij.codeInspection.ex.InspectionProfileImpl
import com.intellij.codeInspection.ex.InspectionToolRegistrar
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.profile.codeInspection.InspectionProjectProfileManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
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
            InspectionEntryPoints.register(project)
        } catch (t: Throwable) {
            logException("scan:registerEntryPointAnnotations", t)
        }

        // Force VFS refresh so the project root is visible to AnalysisScope.
        LocalFileSystem.getInstance().refreshAndFindFileByIoFile(context.projectRoot)
            ?: error("project root not found in VFS: ${context.projectRoot}")

        val subsetProfile = try {
            buildSubsetProfile(context.inspectionIds)
        } catch (t: Throwable) {
            logException("scan:buildSubsetProfile", t)
            throw t
        }

        val enabledInProfile = subsetProfile.allTools.filter { it.isEnabled }.map { it.tool.shortName }
        println("[inspection-scan] subset profile enabled (${enabledInProfile.size}): ${enabledInProfile.joinToString(",")}")

        val managerEx = InspectionManager.getInstance(project) as InspectionManagerEx
        val streaming = StreamingInspectionContext(project, managerEx.contentManager)
        streaming.setExternalProfile(subsetProfile)

        val scope = AnalysisScope(project).apply { setIncludeTestSource(false) }
        println("[inspection-scan] scope: type=${scope.scopeType} valid=${scope.isValid} fileCount=${scope.fileCount}")

        // launchInspections schedules a Task.Backgroundable internally; the launching call
        // itself wants the EDT for ProgressManager. notifyInspectionsFinished then fires from
        // the inspection task thread, which unblocks await() on the workflow runner thread.
        try {
            ApplicationManager.getApplication().invokeAndWait {
                streaming.run(scope)
            }
        } catch (t: Throwable) {
            logException("scan:launchInspections", t)
            throw t
        }

        val raw = try {
            streaming.await()
        } catch (t: Throwable) {
            logException("scan:await", t)
            throw t
        }
        println("[inspection-scan] raw findings=${raw.size}")

        val filtered = filterUnusedSymbolFalsePositives(raw)
        println("[inspection-scan] total findings=${filtered.size}")
        return InspectionScanResult(findings = filtered)
    }

    /**
     * Clones the project profile, then disables every tool except the requested IDs. Same approach
     * the IDE itself uses internally for "Run Inspection By Name" — keeps tool config (severity,
     * scope overrides, custom annotations) identical to the user's profile while limiting blast radius.
     */
    private fun buildSubsetProfile(ids: List<String>): InspectionProfileImpl {
        val baseProfile = InspectionProjectProfileManager.getInstance(project).currentProfile
        val subset = InspectionProfileImpl(
            "smoker-subset",
            InspectionToolRegistrar.getInstance(),
            baseProfile.profileManager,
        )
        subset.copyFrom(baseProfile)
        val targetIds = ids.toSet()
        for (id in targetIds) {
            subset.setToolEnabled(id, true, project)
        }
        for (state in subset.allTools) {
            val id = state.tool.shortName
            if (id !in targetIds) {
                subset.setToolEnabled(id, false, project)
            }
        }
        return subset
    }

    /**
     * Temporary safety net: even with EntryPointsManager wired up, Kotlin's UnusedSymbolInspection
     * is a LocalInspectionTool that can miss cross-file references. Drop it once parity with the
     * old per-file path is confirmed across a few real projects.
     */
    private fun filterUnusedSymbolFalsePositives(findings: List<Finding>): List<Finding> {
        val unusedSymbolId = InspectionRule.UNUSED_SYMBOL.id
        if (findings.none { it.ruleId == unusedSymbolId }) return findings
        val kept = ArrayList<Finding>(findings.size)
        var dropped = 0
        for (f in findings) {
            if (f.ruleId != unusedSymbolId) {
                kept += f
                continue
            }
            if (hasProjectReference(f)) {
                dropped++
                println("[inspection-scan] UnusedSymbol skip referenced file=${f.absolutePath} line=${f.startLine}")
            } else {
                kept += f
            }
        }
        if (dropped > 0) println("[inspection-scan] UnusedSymbol dropped $dropped post-filter")
        return kept
    }

    private fun hasProjectReference(finding: Finding): Boolean {
        val path = finding.absolutePath ?: return false
        val line = finding.startLine ?: return false
        val col = finding.startColumn ?: 1
        val vf = LocalFileSystem.getInstance().findFileByPath(path) ?: return false
        return ReadAction.compute<Boolean, Throwable> {
            val psi = PsiManager.getInstance(project).findFile(vf) ?: return@compute false
            val doc = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(vf)
                ?: return@compute false
            val lineIndex = (line - 1).coerceIn(0, (doc.lineCount - 1).coerceAtLeast(0))
            val offset = (doc.getLineStartOffset(lineIndex) + (col - 1)).coerceIn(0, doc.textLength)
            val element = psi.findElementAt(offset) ?: return@compute false
            val target = declarationElement(element) ?: return@compute false
            ReferencesSearch.search(target, GlobalSearchScope.projectScope(project), false).findFirst() != null
        }
    }

    private fun declarationElement(element: PsiElement?): PsiElement? {
        if (element == null || !element.isValid) return null
        val parent = element.parent
        if (parent is PsiNameIdentifierOwner && parent.nameIdentifier == element) {
            return parent
        }
        return element
    }

    private fun logException(stage: String, t: Throwable) {
        val sw = StringWriter()
        t.printStackTrace(PrintWriter(sw))
        println("[inspection-scan] EXCEPTION at $stage: ${t.javaClass.name}: ${t.message}")
        println(sw.toString().trimEnd())
    }
}
