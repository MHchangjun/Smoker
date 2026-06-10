package com.song.screen.datasource

import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.MethodReferencesSearch
import com.intellij.psi.util.InheritanceUtil
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.asJava.toLightMethods
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClassLiteralExpression
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtLambdaArgument
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.KtTypeReference
import java.util.ArrayDeque

/**
 * PSI-driven resolver that maps a [UiDataSourceViolation] to a concrete
 * [MigrationTarget].
 *
 * Caller must execute inside a smart-mode `ReadAction`. The migration service
 * wraps the call accordingly so this class can use PSI freely.
 *
 * Two distinct paths:
 *
 *  1. **Non-Composable UI** — read the UI class PSI and harvest every
 *     ViewModel handle it already exposes: `by viewModels()`,
 *     `by activityViewModels()`, `ViewModelProvider(...)`, properties typed
 *     `XxxViewModel`. The agent can then reuse one of those targets instead
 *     of inventing a new ViewModel.
 *
 *  2. **Composable** — walk callers via `MethodReferencesSearch` and
 *     classify each call site. When the enclosing function is itself
 *     `@Composable` we recurse; otherwise we record the host:
 *      - inside `KtClassOrObject` extending Activity/Fragment/View → [HostCandidate.ClassOwner]
 *      - inside `composable/dialog/bottomSheet { ... }` NavGraph builder lambda → [HostCandidate.NavDestination]
 *      - `setContent { ... }` lambda → walk up to the enclosing Activity/Fragment class
 *      - otherwise → [HostCandidate.Unresolved] so the LLM still sees the call site.
 */
class MigrationTargetResolver(
    private val project: Project,
) {

    fun resolve(violation: UiDataSourceViolation): MigrationTarget {
        return when (violation.matchKind) {
            LeafMatchKind.COMPOSABLE_METHOD,
            LeafMatchKind.COMPOSABLE_TYPE -> resolveForComposable(violation)
            else -> resolveForUiClass(violation)
        }
    }

    // ---- non-Composable: harvest existing ViewModels on the UI class -----

    private fun resolveForUiClass(violation: UiDataSourceViolation): MigrationTarget {
        val fqn = violation.callerClassFqn ?: return MigrationTarget.NONE
        val cls = try {
            JavaPsiFacade.getInstance(project).findClass(fqn, GlobalSearchScope.allScope(project))
        } catch (_: IndexNotReadyException) {
            return MigrationTarget.NONE
        } ?: return MigrationTarget.NONE

        val ktClass = (cls.navigationElement as? KtClassOrObject) ?: run {
            return MigrationTarget.NONE
        }

        val existing = LinkedHashMap<String, ExistingViewModel>()

        // Pattern A: properties (KtProperty / KtParameter as property).
        // Collect those whose declared type ends with "ViewModel" OR whose
        // delegate call is one of `viewModels`, `activityViewModels`,
        // `hiltViewModel`, `viewModel`.
        ktClass.declarations.forEach { decl ->
            val prop = decl as? KtProperty ?: return@forEach
            val info = readViewModelFromProperty(prop) ?: return@forEach
            existing[info.signature()] = info
        }
        // Also constructor properties.
        ktClass.primaryConstructorParameters.forEach { param ->
            if (!param.hasValOrVar()) return@forEach
            val typeFqn = resolveTypeReferenceToFqn(param.typeReference) ?: return@forEach
            if (!looksLikeViewModelFqn(typeFqn)) return@forEach
            val accessor = param.text.replace('\n', ' ').trim()
            val (file, line) = locateClassFqn(typeFqn)
            existing.putIfAbsent(
                typeFqn,
                ExistingViewModel(
                    fqn = typeFqn,
                    accessor = accessor,
                    filePath = file,
                    fileLine = line,
                ),
            )
        }

        // Pattern B: ViewModelProvider(...).get(XxxViewModel::class.java) or
        // ViewModelProvider(this)[XxxViewModel::class.java].
        // Look for class-literal arguments whose target ends with ViewModel
        // appearing anywhere in the UI class body.
        PsiTreeUtil.findChildrenOfType(ktClass, KtClassLiteralExpression::class.java).forEach { lit ->
            val fqnHit = lit.receiverExpression?.text?.let { simple ->
                resolveSimpleNameInFile(lit.containingKtFile, simple)
            } ?: return@forEach
            if (!looksLikeViewModelFqn(fqnHit)) return@forEach
            val accessor = enclosingExpressionText(lit, MAX_ACCESSOR_LEN)
            val (file, line) = locateClassFqn(fqnHit)
            existing.putIfAbsent(
                fqnHit,
                ExistingViewModel(
                    fqn = fqnHit,
                    accessor = accessor,
                    filePath = file,
                    fileLine = line,
                ),
            )
        }

        return MigrationTarget(
            existingViewModels = existing.values.toList(),
            composableHosts = emptyList(),
        )
    }

    private fun readViewModelFromProperty(prop: KtProperty): ExistingViewModel? {
        // Case 1: explicit type → use that.
        val explicit = resolveTypeReferenceToFqn(prop.typeReference)
        if (explicit != null && looksLikeViewModelFqn(explicit)) {
            val (file, line) = locateClassFqn(explicit)
            return ExistingViewModel(
                fqn = explicit,
                accessor = prop.text.replace('\n', ' ').trim().take(MAX_ACCESSOR_LEN),
                filePath = file,
                fileLine = line,
            )
        }
        // Case 2: by viewModels<XxxViewModel>() / by hiltViewModel<XxxViewModel>() etc.
        val delegate = prop.delegate?.expression as? KtCallExpression ?: return null
        val calleeName = delegate.calleeExpression?.text ?: return null
        if (calleeName !in VIEWMODEL_DELEGATE_FNS) return null

        val typeArg = delegate.typeArguments.firstOrNull()?.typeReference
        val fqn = resolveTypeReferenceToFqn(typeArg)
        if (fqn != null && looksLikeViewModelFqn(fqn)) {
            val (file, line) = locateClassFqn(fqn)
            return ExistingViewModel(
                fqn = fqn,
                accessor = prop.text.replace('\n', ' ').trim().take(MAX_ACCESSOR_LEN),
                filePath = file,
                fileLine = line,
            )
        }
        // Case 3: delegate present but type erased. Still surface as a hint.
        return ExistingViewModel(
            fqn = null,
            accessor = prop.text.replace('\n', ' ').trim().take(MAX_ACCESSOR_LEN),
            filePath = null,
            fileLine = null,
        )
    }

    private fun looksLikeViewModelFqn(fqn: String): Boolean {
        if (fqn.endsWith("ViewModel")) return true
        return try {
            val cls = JavaPsiFacade.getInstance(project)
                .findClass(fqn, GlobalSearchScope.allScope(project)) ?: return false
            InheritanceUtil.isInheritor(cls, "androidx.lifecycle.ViewModel")
        } catch (_: IndexNotReadyException) {
            false
        }
    }

    private fun resolveTypeReferenceToFqn(type: KtTypeReference?): String? {
        val simple = type?.typeElement?.text?.substringBefore('<')?.trim() ?: return null
        // Heuristic: try imports/this-package resolution.
        val file = type.containingKtFile
        return resolveSimpleNameInFile(file, simple)
    }

    private fun resolveSimpleNameInFile(file: KtFile, name: String): String? {
        if (name.contains('.')) return name
        // Imports
        file.importDirectives.forEach { imp ->
            val imported = imp.importedFqName?.asString() ?: return@forEach
            if (imported.substringAfterLast('.') == name) return imported
        }
        // Same package
        val pkg = file.packageFqName.asString()
        val candidate = if (pkg.isEmpty()) name else "$pkg.$name"
        val resolveScope = GlobalSearchScope.allScope(project)
        return try {
            if (JavaPsiFacade.getInstance(project).findClass(candidate, resolveScope) != null) {
                candidate
            } else if (JavaPsiFacade.getInstance(project).findClass(name, resolveScope) != null) {
                name
            } else {
                null
            }
        } catch (_: IndexNotReadyException) {
            null
        }
    }

    private fun locateClassFqn(fqn: String): Pair<String?, Int?> {
        val cls = try {
            JavaPsiFacade.getInstance(project).findClass(fqn, GlobalSearchScope.allScope(project))
        } catch (_: IndexNotReadyException) {
            return null to null
        } ?: return null to null
        val file = cls.containingFile?.virtualFile?.path
        val anchor = cls.navigationElement ?: cls
        val line = lineNumberOf(cls.containingFile, anchor)
        return file to line
    }

    // ---- Composable: walk callers up to ViewModelStoreOwners --------------

    private fun resolveForComposable(violation: UiDataSourceViolation): MigrationTarget {
        val composableName = violation.chain.firstOrNull()?.methodName ?: return MigrationTarget.NONE
        val callerFile = violation.callerFile
        val rootFn = findKtFunctionByName(callerFile, composableName) ?: return MigrationTarget.NONE

        val hosts = LinkedHashMap<String, HostCandidate>()
        val visited = HashSet<KtNamedFunction>()
        val queue = ArrayDeque<ComposablePathNode>()
        queue += ComposablePathNode(fn = rootFn, chain = emptyList())

        var nodes = 0
        while (queue.isNotEmpty() && nodes < MAX_NODES) {
            val cur = queue.poll()
            if (!visited.add(cur.fn)) continue
            nodes++

            val refs = try {
                callersOf(cur.fn)
            } catch (_: IndexNotReadyException) {
                emptyList()
            }
            for (ref in refs) {
                val site = ref.element
                if (isImportSite(site)) continue
                val enclosingFn = PsiTreeUtil.getParentOfType(site, KtNamedFunction::class.java, false)

                // 1) Nav destination builder — `composable/dialog/bottomSheet/navigation(...) { ... }`
                val navBuilder = findEnclosingNavBuilder(site)
                if (navBuilder != null) {
                    val host = buildNavDestination(navBuilder, cur.chain.plus(hopFor(cur.fn)))
                    hosts.putIfAbsent(host.dedupeKey(), host)
                    continue
                }

                // 2) setContent { ... } — walk up to enclosing class.
                val setContent = findEnclosingSetContent(site)
                if (setContent != null) {
                    val owner = nearestUiClass(setContent)
                    if (owner != null) {
                        val host = ownerToHostCandidate(owner, viaSetContent = true, cur.chain.plus(hopFor(cur.fn)))
                        hosts.putIfAbsent(host.dedupeKey(), host)
                        continue
                    }
                }

                // 3) Enclosing function is @Composable — recurse upward.
                if (enclosingFn != null && isComposableFn(enclosingFn)) {
                    queue += ComposablePathNode(fn = enclosingFn, chain = cur.chain.plus(hopFor(cur.fn)))
                    continue
                }

                // 4) Enclosing class is a UI supertype — treat as direct owner.
                val cls = PsiTreeUtil.getParentOfType(site, KtClassOrObject::class.java, false)
                if (cls != null) {
                    val psiCls = jvmClassFor(cls)
                    val supertypeKind = psiCls?.let { uiSupertypeKindOf(it) }
                    if (supertypeKind != null) {
                        val host = HostCandidate.ClassOwner(
                            classFqn = cls.fqName?.asString() ?: psiCls.qualifiedName ?: "?",
                            classKind = supertypeKind,
                            filePath = cls.containingFile?.virtualFile?.path ?: "(unknown)",
                            line = lineNumberOf(cls.containingFile, cls) ?: -1,
                            viaSetContent = false,
                            callChain = cur.chain.plus(hopFor(cur.fn)),
                        )
                        hosts.putIfAbsent(host.dedupeKey(), host)
                        continue
                    }
                }

                // 5) Couldn't classify — surface as Unresolved so the call site is at least visible.
                val unresolved = HostCandidate.Unresolved(
                    filePath = site.containingFile?.virtualFile?.path ?: "(unknown)",
                    line = lineNumberOf(site.containingFile, site) ?: -1,
                    reason = if (enclosingFn != null) "non-composable, non-UI enclosing function `${enclosingFn.name}`"
                    else "no enclosing function found",
                    callChain = cur.chain.plus(hopFor(cur.fn)),
                )
                hosts.putIfAbsent(unresolved.dedupeKey(), unresolved)
            }
        }

        return MigrationTarget(
            existingViewModels = emptyList(),
            composableHosts = hosts.values.toList(),
        )
    }

    private fun findKtFunctionByName(filePath: String, fnName: String): KtNamedFunction? {
        val vf = com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(filePath) ?: return null
        val ktFile = com.intellij.psi.PsiManager.getInstance(project).findFile(vf) as? KtFile ?: return null
        return PsiTreeUtil.findChildrenOfType(ktFile, KtNamedFunction::class.java)
            .firstOrNull { it.name == fnName && PsiTreeUtil.getParentOfType(it, KtClassOrObject::class.java, true) == null }
    }

    private fun callersOf(fn: KtNamedFunction): Collection<com.intellij.psi.PsiReference> {
        val scope = GlobalSearchScope.projectScope(project)
        val lightMethods: List<PsiMethod> = try {
            fn.toLightMethods()
        } catch (_: Throwable) {
            emptyList()
        }
        if (lightMethods.isEmpty()) {
            return com.intellij.psi.search.searches.ReferencesSearch.search(fn, scope).findAll()
        }
        val results = LinkedHashMap<PsiElement, com.intellij.psi.PsiReference>()
        for (lm in lightMethods) {
            MethodReferencesSearch.search(lm, scope, /* strict = */ false).forEach { ref ->
                results.putIfAbsent(ref.element, ref)
            }
        }
        return results.values
    }

    private fun isComposableFn(fn: KtNamedFunction): Boolean =
        fn.annotationEntries.any { it.shortName?.asString() == "Composable" }

    private fun findEnclosingNavBuilder(element: PsiElement): KtCallExpression? {
        // Walk up lambda arguments; check whose call expression is a NavGraphBuilder fn.
        var cur: PsiElement? = element
        while (cur != null) {
            val lambdaArg = PsiTreeUtil.getParentOfType(cur, KtLambdaArgument::class.java, false)
                ?: PsiTreeUtil.getParentOfType(cur, KtLambdaExpression::class.java, false)
                    ?.let { it.parent as? KtLambdaArgument }
            val call = lambdaArg?.parent as? KtCallExpression ?: run {
                cur = cur.parent
                continue
            }
            val name = call.calleeExpression?.text
            if (name in NAV_BUILDER_FNS) return call
            cur = call.parent
        }
        return null
    }

    private fun findEnclosingSetContent(element: PsiElement): KtCallExpression? {
        var cur: PsiElement? = element
        while (cur != null) {
            val lambdaArg = PsiTreeUtil.getParentOfType(cur, KtLambdaArgument::class.java, false)
                ?: PsiTreeUtil.getParentOfType(cur, KtLambdaExpression::class.java, false)
                    ?.let { it.parent as? KtLambdaArgument }
            val call = lambdaArg?.parent as? KtCallExpression ?: run {
                cur = cur.parent
                continue
            }
            if (call.calleeExpression?.text == "setContent") return call
            cur = call.parent
        }
        return null
    }

    private fun nearestUiClass(anchor: PsiElement): KtClassOrObject? {
        var cls: KtClassOrObject? = PsiTreeUtil.getParentOfType(anchor, KtClassOrObject::class.java, false)
        while (cls != null) {
            val psi = jvmClassFor(cls)
            if (psi != null && uiSupertypeKindOf(psi) != null) return cls
            cls = PsiTreeUtil.getParentOfType(cls, KtClassOrObject::class.java, true)
        }
        return null
    }

    private fun jvmClassFor(ktClass: KtClassOrObject): PsiClass? {
        val fqn = ktClass.fqName?.asString() ?: return null
        return try {
            JavaPsiFacade.getInstance(project).findClass(fqn, GlobalSearchScope.allScope(project))
        } catch (_: IndexNotReadyException) {
            null
        }
    }

    private fun uiSupertypeKindOf(cls: PsiClass): String? {
        for (sup in UI_HOST_SUPERTYPES) {
            if (try {
                    InheritanceUtil.isInheritor(cls, sup)
                } catch (_: IndexNotReadyException) {
                    false
                }
            ) return labelFor(sup)
        }
        return null
    }

    private fun labelFor(sup: String): String = when (sup) {
        "androidx.activity.ComponentActivity" -> "ComponentActivity"
        "android.app.Activity" -> "Activity"
        "androidx.fragment.app.Fragment",
        "android.app.Fragment",
        "androidx.leanback.app.Fragment" -> "Fragment"
        "android.view.View" -> "View"
        else -> sup.substringAfterLast('.')
    }

    private fun ownerToHostCandidate(
        cls: KtClassOrObject,
        viaSetContent: Boolean,
        chain: List<ComposableCallHop>,
    ): HostCandidate.ClassOwner {
        val psi = jvmClassFor(cls)
        val kind = psi?.let { uiSupertypeKindOf(it) } ?: "Class"
        return HostCandidate.ClassOwner(
            classFqn = cls.fqName?.asString() ?: psi?.qualifiedName ?: "?",
            classKind = kind,
            filePath = cls.containingFile?.virtualFile?.path ?: "(unknown)",
            line = lineNumberOf(cls.containingFile, cls) ?: -1,
            viaSetContent = viaSetContent,
            callChain = chain,
        )
    }

    private fun buildNavDestination(
        call: KtCallExpression,
        chain: List<ComposableCallHop>,
    ): HostCandidate.NavDestination {
        val fn = call.calleeExpression?.text ?: "composable"
        val route = extractRoute(call)
        return HostCandidate.NavDestination(
            route = route,
            builderFn = fn,
            filePath = call.containingFile?.virtualFile?.path ?: "(unknown)",
            line = lineNumberOf(call.containingFile, call) ?: -1,
            callChain = chain,
        )
    }

    private fun extractRoute(call: KtCallExpression): String? {
        // First value argument is usually `route` (String) or a typed route (KClass).
        val arg = call.valueArguments.firstOrNull() ?: return null
        val expr = arg.getArgumentExpression() ?: return null
        val str = expr as? KtStringTemplateExpression
        if (str != null) {
            val ent = str.entries
            if (ent.size == 1) return ent[0].text
            return str.text.trim('"')
        }
        val classLit = PsiTreeUtil.findChildOfType(expr, KtClassLiteralExpression::class.java)
        if (classLit != null) {
            return classLit.receiverExpression?.text?.let { simple ->
                resolveSimpleNameInFile(call.containingKtFile, simple)
            } ?: classLit.receiverExpression?.text
        }
        return expr.text.take(MAX_ROUTE_LEN)
    }

    private fun hopFor(fn: KtNamedFunction): ComposableCallHop = ComposableCallHop(
        functionName = fn.name ?: "<anonymous>",
        filePath = fn.containingFile?.virtualFile?.path,
        line = lineNumberOf(fn.containingFile, fn) ?: -1,
    )

    // ---- shared utilities --------------------------------------------------

    private fun lineNumberOf(file: PsiFile?, element: PsiElement): Int? {
        val doc = file?.viewProvider?.document ?: return null
        val offset = element.textOffset
        if (offset < 0 || offset > doc.textLength) return null
        return doc.getLineNumber(offset) + 1
    }

    private fun enclosingExpressionText(anchor: PsiElement, maxLen: Int): String {
        var cur: PsiElement? = anchor
        var best: PsiElement? = anchor
        repeat(4) {
            val parent = cur?.parent ?: return@repeat
            if (parent is KtProperty || parent is KtCallExpression) best = parent
            cur = parent
        }
        val raw = (best ?: anchor).text.replace('\n', ' ').replace(Regex("\\s+"), " ").trim()
        return raw.take(maxLen)
    }

    private fun isImportSite(element: PsiElement): Boolean =
        PsiTreeUtil.getParentOfType(element, org.jetbrains.kotlin.psi.KtImportDirective::class.java, false) != null

    private fun HostCandidate.dedupeKey(): String = when (this) {
        is HostCandidate.ClassOwner -> "class:$classFqn:$viaSetContent"
        is HostCandidate.NavDestination -> "nav:$filePath:$line:$route"
        is HostCandidate.Unresolved -> "unresolved:$filePath:$line"
    }

    private fun ExistingViewModel.signature(): String = fqn ?: "?:$accessor"

    private data class ComposablePathNode(
        val fn: KtNamedFunction,
        val chain: List<ComposableCallHop>,
    )

    companion object {
        /** Bound the upward BFS so a Composable used everywhere doesn't blow up. */
        private const val MAX_NODES = 200
        private const val MAX_ACCESSOR_LEN = 160
        private const val MAX_ROUTE_LEN = 120

        private val UI_HOST_SUPERTYPES = listOf(
            "androidx.activity.ComponentActivity",
            "android.app.Activity",
            "androidx.fragment.app.Fragment",
            "android.app.Fragment",
            "androidx.leanback.app.Fragment",
            "android.view.View",
        )

        private val NAV_BUILDER_FNS = setOf(
            "composable",
            "dialog",
            "bottomSheet",
            "navigation",
        )

        private val VIEWMODEL_DELEGATE_FNS = setOf(
            "viewModels",
            "activityViewModels",
            "navGraphViewModels",
            "hiltViewModel",
            "hiltNavGraphViewModels",
            "viewModel",
            "koinViewModel",
            "getViewModel",
            "sharedViewModel",
        )
    }
}
