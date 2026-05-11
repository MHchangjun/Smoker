package com.song.inspection

import com.intellij.analysis.AnalysisScope
import com.intellij.codeInspection.CommonProblemDescriptor
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ex.GlobalInspectionContextImpl
import com.intellij.codeInspection.ex.InspectionProfileImpl
import com.intellij.codeInspection.ex.InspectionToolWrapper
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.NotNullLazyValue
import com.intellij.ui.content.ContentManager
import com.song.sarif.Finding
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Wraps the IDE's batch inspection pipeline so it can be driven programmatically.
 *
 * Note on the view: we intentionally do NOT override addView. Earlier attempts no-op'd both
 * overloads to suppress the Inspection Results tool window, but that left myView null while
 * platform code (DefaultInspectionToolPresentation.addProblemElement) queued deferred
 * `getView().addProblemDescriptors(...)` calls — once `myViewInitialized` was marked done,
 * every queued callback hit getView() == null and threw NPE per descriptor (200+ SEVERE
 * log entries during a real run). Letting super.addView store the view fixes the NPE; the
 * Inspection Results tab appears alongside our Smoker tool window without stealing focus.
 *
 * Run is gated on notifyInspectionsFinished, which fires after RefManager finalization and
 * post-run activities — so getPresentation(...) is safe to harvest at that point.
 */
class StreamingInspectionContext(
    project: Project,
    contentManager: NotNullLazyValue<out ContentManager>,
) : GlobalInspectionContextImpl(project, contentManager) {

    private val finished = CompletableFuture<List<Finding>>()

    public override fun notifyInspectionsFinished(scope: AnalysisScope) {
        var harvested: List<Finding> = emptyList()
        try {
            super.notifyInspectionsFinished(scope)
        } finally {
            try {
                harvested = harvest()
            } catch (t: Throwable) {
                println("[streaming-context] harvest failed: ${t.javaClass.name}: ${t.message}")
            }
            finished.complete(harvested)
        }
    }

    fun run(scope: AnalysisScope) {
        launchInspections(scope)
    }

    fun await(timeoutSeconds: Long = 600): List<Finding> =
        finished.get(timeoutSeconds, TimeUnit.SECONDS)

    private fun harvest(): List<Finding> {
        val out = ArrayList<Finding>(256)
        val seen = HashSet<String>(256)
        val profile = currentProfile as? InspectionProfileImpl ?: return emptyList()
        for (tools in profile.allTools) {
            if (!tools.isEnabled) continue
            val wrapper = tools.tool
            val presentation = getPresentation(wrapper)
            val descriptors = try {
                presentation.problemDescriptors
            } catch (t: Throwable) {
                println("[streaming-context] presentation harvest failed for ${wrapper.shortName}: ${t.message}")
                continue
            }
            for (desc in descriptors) {
                val finding = toFinding(desc, wrapper) ?: continue
                val key = "${finding.ruleId}|${finding.absolutePath}|${finding.startLine}|${finding.startColumn}|${finding.message}"
                if (seen.add(key)) out += finding
            }
        }
        return out
    }

    private fun toFinding(
        desc: CommonProblemDescriptor,
        wrapper: InspectionToolWrapper<*, *>,
    ): Finding? {
        val pd = desc as? ProblemDescriptor ?: return null
        return ReadAction.compute<Finding?, Throwable> {
            val element = pd.psiElement ?: return@compute null
            val file = element.containingFile?.virtualFile ?: return@compute null
            val doc: Document? = FileDocumentManager.getInstance().getDocument(file)
            val tr = element.textRange
            val (sl, sc) = lineCol(doc, tr?.startOffset ?: -1)
            val (el, ec) = lineCol(doc, tr?.endOffset ?: -1)
            Finding(
                ruleId = wrapper.shortName,
                level = wrapper.defaultLevel.severity.name,
                message = stripHtml(pd.descriptionTemplate),
                uriBaseId = null,
                uri = null,
                absolutePath = file.path,
                startLine = sl,
                startColumn = sc,
                endLine = el,
                endColumn = ec,
            )
        }
    }

    private fun lineCol(doc: Document?, offset: Int): Pair<Int?, Int?> {
        if (doc == null || offset < 0 || offset > doc.textLength) return null to null
        val line = doc.getLineNumber(offset)
        return (line + 1) to (offset - doc.getLineStartOffset(line) + 1)
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
}
