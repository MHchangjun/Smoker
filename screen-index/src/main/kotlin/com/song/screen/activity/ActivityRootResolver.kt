package com.song.screen.activity

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ClassInheritorsSearch
import com.intellij.psi.xml.XmlFile
import com.song.screen.ClassTarget
import com.song.screen.ClassTargetResolver

/**
 * Enumerate the Activity FQNs that should become screen roots.
 *
 * Strategy:
 *  - Scan every `AndroidManifest.xml` in the project; collect declared `<activity>` FQNs.
 *  - Also find Activity subclasses via [ClassInheritorsSearch] — covers Activities
 *    declared by manifest merger only (libraries) and dynamic-feature activities.
 *  - Exclude non-main app module roots (test-app, sample-app, ...) by manifest
 *    location so we don't index sub-app entry points as primary screens.
 *
 * Caller must be inside a ReadAction.
 */
class ActivityRootResolver(
    private val project: Project,
    private val classTargetResolver: ClassTargetResolver,
) {

    data class ActivityRoot(
        val target: ClassTarget.ClassRef,
        val manifestPath: String?,
        val displayLabel: String?,
    )

    fun findActivityRoots(log: (String) -> Unit = {}): List<ActivityRoot> {
        val scope = GlobalSearchScope.projectScope(project)
        val excluded = computeNonMainAppRoots(scope, log)

        val fromManifests = scanManifests(scope, excluded, log)
        log("[screen-index] manifest activities=${fromManifests.size}")

        val subclassFqns = collectActivitySubclassFqns(scope, excluded)
        log("[screen-index] activity subclasses indexed=${subclassFqns.size}")

        val byFqn = LinkedHashMap<String, ActivityRoot>()
        for ((fqn, manifestPath, label) in fromManifests) {
            val target = classTargetResolver.resolveClass(fqn) ?: continue
            byFqn[fqn] = ActivityRoot(target, manifestPath, label)
        }
        for (fqn in subclassFqns) {
            if (byFqn.containsKey(fqn)) continue
            val target = classTargetResolver.resolveClass(fqn) ?: continue
            // Skip abstract base classes — they're inherited by real activities elsewhere.
            if (target.psiClass.hasModifierProperty(com.intellij.psi.PsiModifier.ABSTRACT)) continue
            byFqn[fqn] = ActivityRoot(target, manifestPath = null, displayLabel = null)
        }
        log("[screen-index] activity roots total=${byFqn.size}")
        return byFqn.values.toList()
    }

    // ---- manifest scan ----------------------------------------------------

    private data class ManifestActivity(val fqn: String, val manifestPath: String, val label: String?)

    private fun scanManifests(
        scope: GlobalSearchScope,
        excluded: Set<String>,
        log: (String) -> Unit,
    ): List<ManifestActivity> {
        val files = FilenameIndex.getVirtualFilesByName("AndroidManifest.xml", scope)
        if (files.isEmpty()) {
            log("[screen-index] no AndroidManifest.xml found")
            return emptyList()
        }
        val psiManager = PsiManager.getInstance(project)
        val out = ArrayList<ManifestActivity>()
        for (vf in files) {
            if (isExcluded(vf, excluded)) continue
            val xml = psiManager.findFile(vf) as? XmlFile ?: continue
            val root = xml.rootTag ?: continue
            val pkg = root.getAttributeValue("package").orEmpty()
            val app = root.findFirstSubTag("application") ?: continue
            for (activity in app.findSubTags("activity")) {
                val nameAttr = activity.getAttributeValue("android:name") ?: continue
                val fqn = resolveActivityFqn(nameAttr, pkg)
                val label = activity.getAttributeValue("android:label")
                    ?.takeUnless { it.isBlank() || it.startsWith("@") }
                out += ManifestActivity(fqn, vf.path, label)
            }
        }
        return out
    }

    private fun resolveActivityFqn(nameAttr: String, packageFromManifest: String): String {
        if (nameAttr.contains('.') && !nameAttr.startsWith(".")) return nameAttr
        if (packageFromManifest.isNotEmpty()) {
            return if (nameAttr.startsWith(".")) packageFromManifest + nameAttr
            else "$packageFromManifest.$nameAttr"
        }
        return nameAttr
    }

    // ---- inheritor scan ---------------------------------------------------

    private fun collectActivitySubclassFqns(
        scope: GlobalSearchScope,
        excluded: Set<String>,
    ): Set<String> {
        val facade = JavaPsiFacade.getInstance(project)
        val out = LinkedHashSet<String>()
        for (baseFqn in ClassTargetResolver.ACTIVITY_BASES) {
            val base = facade.findClass(baseFqn, GlobalSearchScope.allScope(project)) ?: continue
            for (sub in ClassInheritorsSearch.search(base, scope, true).findAll()) {
                if (isExcluded(sub.containingFile?.virtualFile, excluded)) continue
                sub.qualifiedName?.let { out += it }
            }
        }
        return out
    }

    // ---- sub-app exclusion (ported from old ScreenCandidateScanner) -------

    private fun computeNonMainAppRoots(scope: GlobalSearchScope, log: (String) -> Unit): Set<String> {
        val manifests = FilenameIndex.getVirtualFilesByName("AndroidManifest.xml", scope)
        if (manifests.size <= 1) return emptySet()
        val psiManager = PsiManager.getInstance(project)
        val appModuleRoots = LinkedHashSet<String>()
        for (vf in manifests) {
            val xml = psiManager.findFile(vf) as? XmlFile ?: continue
            val appTag = xml.rootTag?.findFirstSubTag("application") ?: continue
            val declaresActivity = appTag.findSubTags("activity").isNotEmpty()
            if (!declaresActivity) continue
            val moduleRoot = moduleRootOfManifest(vf) ?: continue
            appModuleRoots += moduleRoot
        }
        if (appModuleRoots.size <= 1) return emptySet()
        val main = appModuleRoots.minByOrNull { it.length } ?: return emptySet()
        val excluded = appModuleRoots.filter { it != main }.toSet()
        log("[screen-index] main app root=$main, excluded=${excluded.joinToString(",")}")
        return excluded
    }

    private fun moduleRootOfManifest(vf: VirtualFile): String? {
        val p = vf.path
        val idx = p.indexOf("/src/")
        return if (idx > 0) p.substring(0, idx) else null
    }

    private fun isExcluded(vf: VirtualFile?, excluded: Set<String>): Boolean {
        if (vf == null || excluded.isEmpty()) return false
        val p = vf.path
        return excluded.any { p == it || p.startsWith("$it/") }
    }
}
