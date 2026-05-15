package com.song.screen.host

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag
import com.song.screen.HostVia

/**
 * Walk a navigation graph XML and emit hosted destination children
 * (`<fragment|activity|dialog android:name>`). Recurses into nested
 * `<navigation>` blocks and follows `<include app:graph>` references.
 *
 * Caller must be inside a `ReadAction`.
 */
class NavGraphHostScanner(private val layoutScanner: LayoutXmlHostScanner) {

    /**
     * @param topLevelVia per the originating context — when the nav graph was attached
     *   by a NavHostFragment in a layout, pass [HostVia.NAV_HOST_TRANSPARENT]; when
     *   we reached the graph by direct nav-graph cross-include, pass [HostVia.NAV_GRAPH_DEST].
     *   Destinations inside nested `<navigation>` always emit NAV_GRAPH_DEST.
     */
    fun scan(xml: XmlFile, topLevelVia: HostVia = HostVia.NAV_GRAPH_DEST): List<HostedChild> {
        val root = xml.rootTag ?: return emptyList()
        val sourceFile = xml.virtualFile?.path.orEmpty()
        val out = ArrayList<HostedChild>()
        val visited = HashSet<String>()
        visited += sourceFile
        walk(root, sourceFile, topLevelVia, isTopLevel = true, out = out, visitedNavFiles = visited)
        return out
    }

    private fun walk(
        tag: XmlTag,
        sourceFile: String,
        topLevelVia: HostVia,
        isTopLevel: Boolean,
        out: MutableList<HostedChild>,
        visitedNavFiles: MutableSet<String>,
    ) {
        for (child in tag.subTags) {
            when (child.name) {
                "fragment", "activity", "dialog" -> {
                    val name = child.getAttributeValue("android:name")
                    if (!name.isNullOrBlank()) {
                        val via = if (isTopLevel) topLevelVia else HostVia.NAV_GRAPH_DEST
                        out += HostedChild(name, via, sourceFile)
                    }
                }
                "include" -> {
                    val graphAttr = child.getAttributeValue("app:graph") ?: child.getAttributeValue("graph")
                    val vf = graphAttr?.let { layoutScanner.resolveResourceXml(it, "navigation") }
                    if (vf != null && visitedNavFiles.add(vf.path)) {
                        val included = layoutScanner.toXmlFile(vf) ?: continue
                        val innerRoot = included.rootTag ?: continue
                        walk(innerRoot, vf.path, topLevelVia, isTopLevel = true, out = out, visitedNavFiles = visitedNavFiles)
                    }
                }
                "navigation" -> {
                    walk(child, sourceFile, topLevelVia, isTopLevel = false, out = out, visitedNavFiles = visitedNavFiles)
                }
            }
        }
    }
}
