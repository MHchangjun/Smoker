package com.song.screen.host

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag
import com.song.screen.HostVia

/**
 * Scan a single layout XML and surface:
 *  - direct hosted children (Fragment / View class FQNs)
 *  - cross-references to other layout XMLs (`<include layout="@layout/...">`)
 *  - cross-references to nav graph XMLs (`<... app:navGraph="@navigation/...">`)
 *    — typically attached to `androidx.navigation.fragment.NavHostFragment`.
 *
 * NavHostFragment is NOT emitted as a child — when we detect it, we mark the
 * attached nav graph for resolution via [HostVia.NAV_HOST_TRANSPARENT].
 *
 * Caller must be inside a `ReadAction`.
 */
class LayoutXmlHostScanner(private val project: Project) {

    data class Result(
        val children: List<HostedChild>,
        val includedLayouts: List<VirtualFile>,
        val attachedNavGraphs: List<VirtualFile>,
    )

    fun scan(xml: XmlFile): Result {
        val root = xml.rootTag ?: return EMPTY
        val sourceFile = xml.virtualFile?.path.orEmpty()
        val children = ArrayList<HostedChild>()
        val included = ArrayList<VirtualFile>()
        val navGraphs = ArrayList<VirtualFile>()
        visit(root, sourceFile, children, included, navGraphs)
        return Result(children, included, navGraphs)
    }

    private fun visit(
        tag: XmlTag,
        sourceFile: String,
        children: MutableList<HostedChild>,
        included: MutableList<VirtualFile>,
        navGraphs: MutableList<VirtualFile>,
    ) {
        val tagName = tag.name

        // Detect navGraph attachment first — applies to any tag carrying it (typically NavHostFragment).
        val navGraphAttr = tag.getAttributeValue("app:navGraph") ?: tag.getAttributeValue("navGraph")
        if (!navGraphAttr.isNullOrBlank()) {
            findResourceXml(navGraphAttr, "navigation")?.let { navGraphs += it }
        }

        when {
            tagName.contains('.') -> {
                // Custom-view tag fully qualifies the class.
                children += HostedChild(tagName, HostVia.LAYOUT_CUSTOM_TAG, sourceFile)
            }
            tagName == "fragment" -> {
                val name = tag.getAttributeValue("android:name")
                if (!name.isNullOrBlank() && !name.isNavHostFragment()) {
                    children += HostedChild(name, HostVia.LAYOUT_FRAGMENT_TAG, sourceFile)
                }
            }
            tagName == "FragmentContainerView" || tagName.endsWith(".FragmentContainerView") -> {
                val name = tag.getAttributeValue("android:name")
                if (!name.isNullOrBlank() && !name.isNavHostFragment()) {
                    children += HostedChild(name, HostVia.LAYOUT_FRAGMENT_CONTAINER, sourceFile)
                }
            }
            tagName == "view" -> {
                val cls = tag.getAttributeValue("class")
                if (!cls.isNullOrBlank()) {
                    children += HostedChild(cls, HostVia.LAYOUT_VIEW_CLASS, sourceFile)
                }
            }
            tagName == "include" -> {
                val layoutAttr = tag.getAttributeValue("layout")
                if (!layoutAttr.isNullOrBlank()) {
                    findResourceXml(layoutAttr, "layout")?.let { included += it }
                }
                val graphAttr = tag.getAttributeValue("app:graph") ?: tag.getAttributeValue("graph")
                if (!graphAttr.isNullOrBlank()) {
                    findResourceXml(graphAttr, "navigation")?.let { navGraphs += it }
                }
            }
        }

        for (child in tag.subTags) {
            visit(child, sourceFile, children, included, navGraphs)
        }
    }

    private fun String.isNavHostFragment(): Boolean =
        this == "androidx.navigation.fragment.NavHostFragment" ||
            this == "androidx.navigation.fragment.NavHostFragment\$Companion"

    private fun findResourceXml(attr: String, expectedDir: String): VirtualFile? {
        val match = RES_REF_REGEX.find(attr) ?: return null
        if (match.groupValues[1] != expectedDir) return null
        val name = match.groupValues[2]
        val scope = GlobalSearchScope.projectScope(project)
        return FilenameIndex.getVirtualFilesByName("$name.xml", scope)
            .firstOrNull { it.parent?.name == expectedDir }
    }

    fun resolveResourceXml(attr: String, expectedDir: String): VirtualFile? = findResourceXml(attr, expectedDir)

    fun toXmlFile(vf: VirtualFile): XmlFile? =
        PsiManager.getInstance(project).findFile(vf) as? XmlFile

    companion object {
        private val EMPTY = Result(emptyList(), emptyList(), emptyList())
        private val RES_REF_REGEX = Regex("@(?:\\+)?(?:[a-zA-Z]+:)?(layout|navigation)/([A-Za-z0-9_]+)")
    }
}
