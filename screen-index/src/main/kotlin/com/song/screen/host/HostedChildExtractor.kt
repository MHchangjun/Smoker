package com.song.screen.host

import com.intellij.openapi.vfs.VirtualFile
import com.song.screen.ClassTarget
import com.song.screen.HostVia

/**
 * Given a host class, find all classes it directly hosts via XML.
 *
 * Pipeline:
 *  1. Scan the host class text for `R.layout.X` tokens, `R.navigation.Y` tokens,
 *     and `*Binding` references. Each becomes a layout / nav-graph candidate.
 *  2. For each layout XML: run [LayoutXmlHostScanner], collect direct children,
 *     then follow `<include layout>` to other layouts (BFS, visited).
 *  3. For each nav graph attached via NavHostFragment / `app:navGraph` /
 *     `R.navigation.X`: run [NavGraphHostScanner] (which itself follows
 *     `<include app:graph>` and nested `<navigation>`).
 *  4. Dedup by `childFqn`. Multiple via reasons collapse to the first one seen.
 *
 * v1 covers XML paths only — `FragmentManager.commit { ... }` / Compose
 * `setContent` / `DialogFragment.show()` will be added in later phases.
 *
 * Caller must execute inside a `ReadAction`.
 */
class HostedChildExtractor(
    private val layoutScanner: LayoutXmlHostScanner,
    private val navScanner: NavGraphHostScanner,
) {

    fun extract(host: ClassTarget.ClassRef): List<HostedChild> {
        val hostText = host.psiClass.containingFile?.text ?: return emptyList()
        val layoutNames = extractLayoutNames(hostText)
        val navNames = extractNavNames(hostText)

        val out = LinkedHashMap<String, HostedChild>()
        val visitedLayouts = HashSet<String>()
        val visitedNavGraphs = HashSet<String>()

        for (layoutName in layoutNames) {
            val vf = layoutScanner.resolveResourceXml("@layout/$layoutName", "layout") ?: continue
            walkLayout(vf, out, visitedLayouts, visitedNavGraphs)
        }

        for (navName in navNames) {
            val vf = layoutScanner.resolveResourceXml("@navigation/$navName", "navigation") ?: continue
            if (!visitedNavGraphs.add(vf.path)) continue
            val xml = layoutScanner.toXmlFile(vf) ?: continue
            for (child in navScanner.scan(xml, topLevelVia = HostVia.NAV_HOST_TRANSPARENT)) {
                out.putIfAbsent(child.childFqn, child)
            }
        }

        return out.values.toList()
    }

    private fun walkLayout(
        startVf: VirtualFile,
        out: MutableMap<String, HostedChild>,
        visitedLayouts: MutableSet<String>,
        visitedNavGraphs: MutableSet<String>,
    ) {
        val queue = ArrayDeque<VirtualFile>()
        queue += startVf
        visitedLayouts += startVf.path

        while (queue.isNotEmpty()) {
            val vf = queue.removeFirst()
            val xml = layoutScanner.toXmlFile(vf) ?: continue
            val result = layoutScanner.scan(xml)
            for (child in result.children) {
                out.putIfAbsent(child.childFqn, child)
            }
            for (included in result.includedLayouts) {
                if (visitedLayouts.add(included.path)) queue += included
            }
            for (navVf in result.attachedNavGraphs) {
                if (!visitedNavGraphs.add(navVf.path)) continue
                val navXml = layoutScanner.toXmlFile(navVf) ?: continue
                for (child in navScanner.scan(navXml, topLevelVia = HostVia.NAV_HOST_TRANSPARENT)) {
                    out.putIfAbsent(child.childFqn, child)
                }
            }
        }
    }

    private fun extractLayoutNames(text: String): Set<String> {
        val out = LinkedHashSet<String>()
        for (m in R_LAYOUT_REGEX.findAll(text)) out += m.groupValues[1]
        for (m in BINDING_REGEX.findAll(text)) {
            val prefix = m.groupValues[1]
            if (prefix.isNotBlank()) out += camelToSnake(prefix)
        }
        return out
    }

    private fun extractNavNames(text: String): Set<String> {
        val out = LinkedHashSet<String>()
        for (m in R_NAV_REGEX.findAll(text)) out += m.groupValues[1]
        return out
    }

    private fun camelToSnake(camel: String): String {
        if (camel.isEmpty()) return camel
        val sb = StringBuilder()
        for ((i, ch) in camel.withIndex()) {
            if (i > 0 && ch.isUpperCase()) sb.append('_')
            sb.append(ch.lowercaseChar())
        }
        return sb.toString()
    }

    companion object {
        private val R_LAYOUT_REGEX = Regex("\\bR\\.layout\\.([A-Za-z0-9_]+)")
        private val R_NAV_REGEX = Regex("\\bR\\.navigation\\.([A-Za-z0-9_]+)")
        private val BINDING_REGEX = Regex("\\b([A-Z][A-Za-z0-9]*)Binding\\b")
    }
}
