package com.song.screen.datasource

import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiImportStatementBase
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiReference
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.MethodReferencesSearch
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.InheritanceUtil
import com.intellij.psi.util.PsiTreeUtil
import com.song.screen.ClassTargetResolver
import com.song.screen.datasource.DataSourceLeafCatalog.COMPOSABLE_ANNOTATION_SHORT_NAME
import com.song.screen.datasource.DataSourceLeafCatalog.METHOD_LEAVES
import com.song.screen.datasource.DataSourceLeafCatalog.TYPE_LEAVES
import com.song.screen.datasource.DataSourceLeafCatalog.UI_EXCLUDE_SUPERTYPES
import com.song.screen.datasource.DataSourceLeafCatalog.UI_SUPERTYPES
import org.jetbrains.kotlin.asJava.toLightMethods
import org.jetbrains.kotlin.psi.KtAnonymousInitializer
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassBody
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtConstructor
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtImportDirective
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty
import java.util.ArrayDeque

/**
 * Two-phase UI → data-source reachability scanner.
 *
 * **Phase 1 — seed building (per leaf TYPE / leaf METHOD).**
 * Walk every project-scope reference to each leaf:
 *  - Reference inside a method/ctor body or signature → that method becomes a
 *    *leaf-touching* seed.
 *  - Reference at the field/property declaration in a class → if that class is
 *    UI, emit a [LeafMatchKind.TYPE_DECL] violation immediately. Either way,
 *    follow references to that field/property and mark every accessing method
 *    as leaf-touching. This catches the wrapper-with-field pattern where
 *    `lateinit var prefs: SharedPreferences` lives at class level and methods
 *    call `prefs.getString(...)` without the type appearing in the method body.
 *
 * **Phase 2 — reverse BFS through the call graph.**
 * From the seed set, expand backward via [MethodReferencesSearch] /
 * [ReferencesSearch]:
 *  - Node = `(method, depth, parent, leafId)`. The visited set is keyed by
 *    PSI method identity so each method is processed once.
 *  - When the polled method's containing class is UI (or it is a top-level
 *    `@Composable`), record a method violation and **do not** expand further
 *    — the user fixes that method, the rest of the chain falls.
 *  - When the containing class extends `ViewModel` (correct architectural
 *    boundary), stop silently.
 *  - Depth-cap at [MAX_DEPTH] to avoid runaway expansion in monorepos.
 *
 * This eliminates the class-level over-approximation that plagued the
 * previous design: a Fragment that calls `StatsManager.sendEvent(...)` is
 * not flagged unless `sendEvent` itself transitively reaches a leaf-touching
 * method through real call edges.
 *
 * Caller must execute inside a smart-mode `ReadAction`.
 */
class UiDataSourceScanner(
    private val project: Project,
    private val classTargetResolver: ClassTargetResolver,
) {

    fun scan(log: (String) -> Unit = {}): List<UiDataSourceViolation> {
        val callerScope = GlobalSearchScope.projectScope(project)
        val resolveScope = GlobalSearchScope.allScope(project)
        val facade = JavaPsiFacade.getInstance(project)

        warnIfProjectLikelyUnsynced(log)

        // Seeds and visited entries are keyed by (method, leafId) so that each
        // leaf gets an independent reverse BFS. A hub method like
        // ConvertUserHelper.onKidsClick — which touches both NetworkUtil.isOnline
        // (→ ConnectivityManager) AND DataProvider.request (→ retrofit2.Call) —
        // must participate in two separate chains. A method-only visited set
        // would mark it visited from the first leaf and silently drop every
        // other leaf's chain passing through it.
        val leafTouchingMethods = HashMap<Pair<PsiElement, String>, MethodNode>()
        val typeDeclViolations = ArrayList<UiDataSourceViolation>()

        // ---- Phase 1: seed building --------------------------------------

        var typeRefs = 0
        for (fqn in TYPE_LEAVES) {
            val psi = facade.findClass(fqn, resolveScope) ?: continue
            try {
                ReferencesSearch.search(psi, callerScope).forEach { ref ->
                    typeRefs++
                    processTypeLeafRef(ref, fqn, callerScope, leafTouchingMethods, typeDeclViolations)
                }
            } catch (_: IndexNotReadyException) {
                log("[ui-datasource] index not ready for $fqn; skipping")
            }
        }
        log("[ui-datasource] phase1.type: refs=$typeRefs, seeds=${leafTouchingMethods.size}, type-decl=${typeDeclViolations.size}")

        var methodRefs = 0
        val seedsAfterType = leafTouchingMethods.size
        for ((typeFqn, methodNames) in METHOD_LEAVES) {
            val owner = facade.findClass(typeFqn, resolveScope) ?: continue
            for (methodName in methodNames) {
                for (method in owner.findMethodsByName(methodName, /* checkBases = */ true)) {
                    try {
                        MethodReferencesSearch.search(method, callerScope, /* strict = */ false)
                            .forEach { ref ->
                                methodRefs++
                                processMethodLeafRef(ref, "$typeFqn#$methodName", leafTouchingMethods)
                            }
                    } catch (_: IndexNotReadyException) {
                        log("[ui-datasource] index not ready for $typeFqn#$methodName; skipping")
                    }
                }
            }
        }
        log("[ui-datasource] phase1.method: refs=$methodRefs, new seeds=${leafTouchingMethods.size - seedsAfterType}")

        // ---- Phase 2: reverse BFS ----------------------------------------

        val visited = HashSet<Pair<PsiElement, String>>(leafTouchingMethods.keys)
        val queue = ArrayDeque<MethodNode>(leafTouchingMethods.values)
        val methodViolations = ArrayList<UiDataSourceViolation>()

        var iterations = 0
        var calleesChecked = 0
        while (queue.isNotEmpty()) {
            iterations++
            if (typeDeclViolations.size + methodViolations.size > MAX_VIOLATIONS) {
                log("[ui-datasource] violation cap ($MAX_VIOLATIONS) reached, stopping BFS")
                break
            }
            val node = queue.poll()
            if (node.depth > MAX_DEPTH) continue

            val cls = containingClassOf(node.element)
            if (cls != null && isExcludedSink(cls)) continue   // ViewModel: silent stop

            val uiSupertype = cls?.let { matchUiSupertype(it) }
            val composable = (uiSupertype == null) && (cls == null) && isTopLevelComposable(node.element)

            if (uiSupertype != null || composable) {
                methodViolations += buildMethodViolation(node, cls, uiSupertype, composable)
                continue   // do not expand past UI
            }

            val refs = try {
                searchCallersOf(node.element, callerScope)
            } catch (_: IndexNotReadyException) {
                continue
            }

            for (ref in refs) {
                calleesChecked++
                val element = ref.element
                if (isImportSite(element)) continue
                if (isTestSource(element)) continue
                val caller = findEnclosingMethod(element) ?: continue
                val visitKey = caller to node.leafId
                if (visitKey in visited) continue
                if (isGeneratedMethod(caller)) continue
                visited.add(visitKey)
                queue += MethodNode(
                    element = caller,
                    callSite = element,
                    leafId = node.leafId,
                    leafIsMethod = node.leafIsMethod,
                    depth = node.depth + 1,
                    parent = node,
                )
            }
        }
        log(
            "[ui-datasource] phase2: iterations=$iterations, callers=$calleesChecked, " +
                "visited=${visited.size}, method violations=${methodViolations.size}",
        )

        val all = typeDeclViolations + methodViolations
        val deduped = all
            .groupBy { Triple(it.callerFile, it.callerLine, it.leaf) }
            .values
            .map { group -> group.minBy { it.hopDistance } }
        log("[ui-datasource] result: ${deduped.size} unique (${all.size} pre-dedupe)")
        return deduped
    }

    /**
     * Sanity-check the IDE project model. If the sandbox opened the project
     * before Gradle import completed, only a fraction of modules are
     * registered with the IntelliJ module manager and `ReferencesSearch`
     * silently returns empty for anything in unimported modules — producing
     * a misleading "clean" scan with large blind spots. We surface a warning
     * if the module count looks suspicious so the user can re-sync before
     * trusting the results.
     */
    private fun warnIfProjectLikelyUnsynced(log: (String) -> Unit) {
        val modules = com.intellij.openapi.module.ModuleManager.getInstance(project).modules
        if (modules.size < SUSPICIOUS_MODULE_THRESHOLD) {
            log(
                "[ui-datasource] WARN: IDE has ${modules.size} module(s) registered " +
                    "— if this is a multi-module Gradle project, run 'Sync Project with " +
                    "Gradle Files' before scanning (otherwise unsynced modules' wrappers " +
                    "are invisible to ReferencesSearch and chains break silently).",
            )
        }
    }

    // ---- Phase 1 helpers --------------------------------------------------

    private fun processTypeLeafRef(
        ref: PsiReference,
        leafFqn: String,
        scope: GlobalSearchScope,
        leafTouchingMethods: MutableMap<Pair<PsiElement, String>, MethodNode>,
        typeDeclViolations: MutableList<UiDataSourceViolation>,
    ) {
        val element = ref.element
        if (isImportSite(element)) return
        if (isTestSource(element)) return

        // Inside a method/ctor body or signature: this method touches the leaf.
        val enclosingMethod = findEnclosingMethod(element)
        if (enclosingMethod != null) {
            markLeafTouching(enclosingMethod, element, leafFqn, leafIsMethod = false, leafTouchingMethods)
            return
        }

        // At class level (field / property declaration). Two effects:
        //  1. If the containing class is UI → record TYPE_DECL violation
        //  2. Find every method that accesses this field → mark as leaf-touching
        val field = findEnclosingFieldOrProperty(element) ?: return
        val cls = nearestContainingClass(element)
        if (cls != null) {
            if (isExcludedSink(cls)) return   // ViewModel holds it — correct
            val uiSupertype = matchUiSupertype(cls)
            if (uiSupertype != null) {
                typeDeclViolations += buildTypeDeclViolation(element, cls, uiSupertype, leafFqn)
            }
        }

        try {
            ReferencesSearch.search(field, scope).forEach { fieldRef ->
                val fieldRefElement = fieldRef.element
                if (isImportSite(fieldRefElement)) return@forEach
                if (isTestSource(fieldRefElement)) return@forEach
                val method = findEnclosingMethod(fieldRefElement) ?: return@forEach
                markLeafTouching(method, fieldRefElement, leafFqn, leafIsMethod = false, leafTouchingMethods)
            }
        } catch (_: IndexNotReadyException) {
        }
    }

    private fun processMethodLeafRef(
        ref: PsiReference,
        leafId: String,
        leafTouchingMethods: MutableMap<Pair<PsiElement, String>, MethodNode>,
    ) {
        val element = ref.element
        if (isImportSite(element)) return
        if (isTestSource(element)) return
        val enclosingMethod = findEnclosingMethod(element) ?: return
        markLeafTouching(enclosingMethod, element, leafId, leafIsMethod = true, leafTouchingMethods)
    }

    private fun markLeafTouching(
        method: PsiElement,
        touchSite: PsiElement,
        leafId: String,
        leafIsMethod: Boolean,
        leafTouchingMethods: MutableMap<Pair<PsiElement, String>, MethodNode>,
    ) {
        val key = method to leafId
        if (key in leafTouchingMethods) return
        if (isGeneratedMethod(method)) return
        leafTouchingMethods[key] = MethodNode(
            element = method,
            callSite = touchSite,
            leafId = leafId,
            leafIsMethod = leafIsMethod,
            depth = 0,
            parent = null,
        )

        // Propagate the seed up the override chain (within project scope).
        //
        // When the leaf-touching method is an override — e.g. an enum
        // constant's anonymous `getResponse` whose signature pins down
        // `Call<UserResponse>` — polymorphic call sites like
        // `queryType.getResponse(...)` statically resolve to the super
        // interface method (`WPlayQuery.getResponse`), not the override.
        // `MethodReferencesSearch` on the override alone can miss these.
        // Marking the super(s) as seeds lets the BFS find those call sites.
        //
        // Limited to project-scope supers so framework supers (Activity.onCreate,
        // Object.equals, etc.) — which would explode the search — are excluded.
        val projectScope = GlobalSearchScope.projectScope(project)
        for (sup in supersInProject(method, projectScope)) {
            val supKey = sup to leafId
            if (supKey in leafTouchingMethods) continue
            if (isGeneratedMethod(sup)) continue
            leafTouchingMethods[supKey] = MethodNode(
                element = sup,
                callSite = touchSite,
                leafId = leafId,
                leafIsMethod = leafIsMethod,
                depth = 0,
                parent = null,
            )
        }
    }

    /**
     * All transitive super methods (overridden interface/parent class methods)
     * that live inside the project's own source — framework / library supers
     * are filtered out to avoid polluting the seed set.
     */
    private fun supersInProject(method: PsiElement, projectScope: GlobalSearchScope): Set<PsiMethod> {
        val direct: List<PsiMethod> = when (method) {
            is PsiMethod -> method.findSuperMethods().toList()
            is KtDeclaration -> {
                val lights = try { method.toLightMethods() } catch (_: Throwable) { emptyList() }
                lights.flatMap { it.findSuperMethods().toList() }
            }
            else -> emptyList()
        }
        if (direct.isEmpty()) return emptySet()
        val all = LinkedHashSet<PsiMethod>()
        val queue = ArrayDeque(direct)
        while (queue.isNotEmpty()) {
            val s = queue.poll()
            if (!all.add(s)) continue
            for (ss in s.findSuperMethods()) queue += ss
        }
        return all.asSequence()
            .filter { sup ->
                val vf = sup.containingFile?.virtualFile ?: return@filter false
                projectScope.contains(vf)
            }
            .toSet()
    }

    // ---- PSI structural helpers ------------------------------------------

    private fun findEnclosingMethod(element: PsiElement): PsiElement? {
        PsiTreeUtil.getParentOfType(element, PsiMethod::class.java, false)?.let { return it }
        PsiTreeUtil.getParentOfType(element, KtNamedFunction::class.java, false)?.let { return it }
        PsiTreeUtil.getParentOfType(element, KtConstructor::class.java, false)?.let { return it }
        // init blocks: attribute to the primary constructor when present.
        val init = PsiTreeUtil.getParentOfType(element, KtAnonymousInitializer::class.java, false)
        if (init != null) {
            val ktClass = PsiTreeUtil.getParentOfType(init, KtClass::class.java, false)
            return ktClass?.primaryConstructor
        }
        return null
    }

    private fun findEnclosingFieldOrProperty(element: PsiElement): PsiElement? {
        PsiTreeUtil.getParentOfType(element, PsiField::class.java, false)?.let { return it }
        val ktProperty = PsiTreeUtil.getParentOfType(element, KtProperty::class.java, false)
        if (ktProperty != null && isClassOrFileLevel(ktProperty)) return ktProperty
        val ktParam = PsiTreeUtil.getParentOfType(element, KtParameter::class.java, false)
        if (ktParam != null && ktParam.hasValOrVar()) {
            // `class Foo(val prefs: SharedPreferences)` — parameter is also a property.
            return ktParam
        }
        return null
    }

    private fun isClassOrFileLevel(property: KtProperty): Boolean {
        val parent = property.parent
        return parent is KtFile || parent is KtClassBody
    }

    private fun nearestContainingClass(element: PsiElement): PsiClass? {
        val ktClass = PsiTreeUtil.getParentOfType(element, KtClassOrObject::class.java, false)
        if (ktClass != null) {
            val fqn = ktClass.fqName?.asString() ?: return null
            return try {
                JavaPsiFacade.getInstance(project).findClass(fqn, GlobalSearchScope.allScope(project))
            } catch (_: IndexNotReadyException) {
                null
            }
        }
        return PsiTreeUtil.getParentOfType(element, PsiClass::class.java, false)
    }

    private fun containingClassOf(method: PsiElement): PsiClass? {
        if (method is PsiMethod) return method.containingClass
        val ktCls = PsiTreeUtil.getParentOfType(method, KtClassOrObject::class.java, false) ?: return null
        val fqn = ktCls.fqName?.asString() ?: return null
        return try {
            JavaPsiFacade.getInstance(project).findClass(fqn, GlobalSearchScope.allScope(project))
        } catch (_: IndexNotReadyException) {
            null
        }
    }

    private fun isTopLevelComposable(method: PsiElement): Boolean {
        if (method !is KtNamedFunction) return false
        if (PsiTreeUtil.getParentOfType(method, KtClassOrObject::class.java, false) != null) return false
        return method.annotationEntries.any {
            it.shortName?.asString() == COMPOSABLE_ANNOTATION_SHORT_NAME
        }
    }

    /**
     * Find every reference to [method]. For Kotlin declarations, route through
     * their **light methods** so Kotlin object members / @JvmStatic functions
     * are discovered consistently regardless of caller language. `ReferencesSearch`
     * directly on a `KtNamedFunction` misses some call sites in practice (most
     * notably members of Kotlin `object` declarations called from Kotlin code
     * outside the object), so we always go through the synthetic PsiMethod.
     */
    private fun searchCallersOf(method: PsiElement, scope: GlobalSearchScope): Collection<PsiReference> = when (method) {
        is PsiMethod -> MethodReferencesSearch.search(method, scope, /* strict = */ false).findAll()
        is KtDeclaration -> {
            val lightMethods: List<PsiMethod> = try {
                method.toLightMethods()
            } catch (_: Throwable) {
                emptyList()
            }
            if (lightMethods.isEmpty()) {
                ReferencesSearch.search(method, scope).findAll()
            } else {
                val results = LinkedHashMap<PsiElement, PsiReference>()
                for (lm in lightMethods) {
                    MethodReferencesSearch.search(lm, scope, /* strict = */ false).forEach { ref ->
                        results.putIfAbsent(ref.element, ref)
                    }
                }
                results.values
            }
        }
        else -> ReferencesSearch.search(method, scope).findAll()
    }

    private fun matchUiSupertype(cls: PsiClass): String? {
        if (UI_EXCLUDE_SUPERTYPES.any { safeInherits(cls, it) }) return null
        return UI_SUPERTYPES.firstOrNull { safeInherits(cls, it) }
    }

    private fun isExcludedSink(cls: PsiClass): Boolean =
        UI_EXCLUDE_SUPERTYPES.any { safeInherits(cls, it) }

    private fun safeInherits(cls: PsiClass, baseFqn: String): Boolean = try {
        InheritanceUtil.isInheritor(cls, baseFqn)
    } catch (_: IndexNotReadyException) {
        false
    }

    private fun isGeneratedMethod(method: PsiElement): Boolean {
        val cls = containingClassOf(method) ?: return false
        return classTargetResolver.isGeneratedClass(cls)
    }

    private fun isImportSite(element: PsiElement): Boolean =
        PsiTreeUtil.getParentOfType(element, KtImportDirective::class.java, false) != null ||
            PsiTreeUtil.getParentOfType(element, PsiImportStatementBase::class.java, false) != null

    private fun isTestSource(element: PsiElement): Boolean {
        val path = element.containingFile?.virtualFile?.path ?: return false
        return path.contains("/src/test/") || path.contains("/src/androidTest/")
    }

    // ---- Violation building ----------------------------------------------

    private fun buildTypeDeclViolation(
        site: PsiElement,
        uiClass: PsiClass,
        uiSupertype: String,
        leafFqn: String,
    ): UiDataSourceViolation {
        val file = site.containingFile
        val (line, excerpt) = locate(file, site)
        val uiHop = ViolationHop(
            displayName = uiClass.name ?: leafFqn.substringAfterLast('.'),
            classFqn = uiClass.qualifiedName,
            methodName = null,
            filePath = uiClass.containingFile?.virtualFile?.path,
        )
        val leafHop = ViolationHop(
            displayName = leafFqn.substringAfterLast('.'),
            classFqn = leafFqn,
            methodName = null,
            filePath = null,
        )
        return UiDataSourceViolation(
            leaf = leafFqn,
            matchKind = LeafMatchKind.TYPE_DECL,
            callerClassFqn = uiClass.qualifiedName,
            callerKind = kindLabel(uiSupertype),
            callerFile = file?.virtualFile?.path ?: "(unknown)",
            callerLine = line,
            callerExcerpt = excerpt,
            chain = listOf(uiHop, leafHop),
            hopDistance = 1,
        )
    }

    private fun buildMethodViolation(
        node: MethodNode,
        uiClass: PsiClass?,
        uiSupertype: String?,
        composable: Boolean,
    ): UiDataSourceViolation {
        val callSite = node.callSite
        val file = callSite.containingFile
        val (line, excerpt) = locate(file, callSite)

        val callerKind = when {
            composable -> "Composable"
            uiSupertype != null -> kindLabel(uiSupertype)
            else -> "Unknown"
        }
        val chain = buildChain(node)
        val matchKind = when {
            composable && node.leafIsMethod -> LeafMatchKind.COMPOSABLE_METHOD
            composable -> LeafMatchKind.COMPOSABLE_TYPE
            node.leafIsMethod -> LeafMatchKind.METHOD
            else -> LeafMatchKind.TYPE
        }
        return UiDataSourceViolation(
            leaf = node.leafId,
            matchKind = matchKind,
            callerClassFqn = uiClass?.qualifiedName,
            callerKind = callerKind,
            callerFile = file?.virtualFile?.path ?: "(unknown)",
            callerLine = line,
            callerExcerpt = excerpt,
            chain = chain,
            hopDistance = chain.size - 1,
        )
    }

    /** Walk parent pointers from UI hit back to the seed and append the leaf as the final hop. */
    private fun buildChain(uiNode: MethodNode): List<ViolationHop> {
        val hops = ArrayList<ViolationHop>()
        var cur: MethodNode? = uiNode
        while (cur != null) {
            hops += hopForMethod(cur)
            cur = cur.parent
        }
        hops += hopForLeaf(uiNode.leafId, uiNode.leafIsMethod)
        return hops
    }

    private fun hopForMethod(node: MethodNode): ViolationHop {
        val cls = containingClassOf(node.element)
        val className = cls?.name
        val methodName = methodNameOf(node.element)
        val display = if (className != null) "$className.$methodName" else methodName
        return ViolationHop(
            displayName = display,
            classFqn = cls?.qualifiedName,
            methodName = methodName,
            filePath = node.element.containingFile?.virtualFile?.path,
        )
    }

    private fun hopForLeaf(leafId: String, isMethod: Boolean): ViolationHop {
        if (isMethod) {
            val typeFqn = leafId.substringBeforeLast('#')
            val methodName = leafId.substringAfterLast('#')
            return ViolationHop(
                displayName = "${typeFqn.substringAfterLast('.')}#$methodName",
                classFqn = typeFqn,
                methodName = methodName,
                filePath = null,
            )
        }
        return ViolationHop(
            displayName = leafId.substringAfterLast('.'),
            classFqn = leafId,
            methodName = null,
            filePath = null,
        )
    }

    private fun methodNameOf(method: PsiElement): String = when (method) {
        is PsiMethod -> if (method.isConstructor) "<init>" else method.name
        is KtNamedFunction -> method.name ?: "<anonymous>"
        is KtConstructor<*> -> "<init>"
        else -> "?"
    }

    private fun kindLabel(supertypeFqn: String): String = when (supertypeFqn) {
        "android.app.Activity" -> "Activity"
        "androidx.fragment.app.Fragment",
        "android.app.Fragment",
        "androidx.leanback.app.Fragment" -> "Fragment"
        "android.view.View" -> "View"
        "androidx.recyclerview.widget.RecyclerView.Adapter" -> "Adapter"
        "androidx.recyclerview.widget.RecyclerView.ViewHolder" -> "ViewHolder"
        else -> supertypeFqn.substringAfterLast('.')
    }

    private fun locate(file: PsiFile?, element: PsiElement): Pair<Int, String> {
        val doc = file?.viewProvider?.document ?: return -1 to element.text.take(EXCERPT_MAX)
        val offset = element.textOffset
        if (offset < 0 || offset > doc.textLength) return -1 to element.text.take(EXCERPT_MAX)
        val lineIdx = doc.getLineNumber(offset)
        val start = doc.getLineStartOffset(lineIdx)
        val end = doc.getLineEndOffset(lineIdx)
        return (lineIdx + 1) to doc.getText(TextRange(start, end)).trim().take(EXCERPT_MAX)
    }

    // ---- BFS frontier node -----------------------------------------------

    private data class MethodNode(
        /** PsiMethod / KtNamedFunction / KtConstructor — the carrier method itself. */
        val element: PsiElement,
        /** PSI element where the OUTGOING call (or, for seeds, the leaf ref) occurs in [element]. */
        val callSite: PsiElement,
        /** Leaf identifier: type FQN or `Type#method`. */
        val leafId: String,
        val leafIsMethod: Boolean,
        val depth: Int,
        val parent: MethodNode?,
    )

    companion object {
        /** Cap chain length. Real chains stay under ~5; this is a safety net. */
        private const val MAX_DEPTH = 8

        /** Hard cap to bound memory in pathological cases (very widely-used utilities). */
        private const val MAX_VIOLATIONS = 10_000

        private const val EXCERPT_MAX = 200

        /**
         * If the IDE has fewer than this many modules, suspect an incomplete
         * Gradle sync. Any non-trivial Android project has > 5 modules.
         */
        private const val SUSPICIOUS_MODULE_THRESHOLD = 5
    }
}
