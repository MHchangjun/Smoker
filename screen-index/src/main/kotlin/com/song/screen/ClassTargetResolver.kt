package com.song.screen

import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.InheritanceUtil
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtFile

/**
 * Resolve a fully-qualified class name to a [ClassTarget.ClassRef].
 *
 * Callers must execute inside a `ReadAction`.
 */
class ClassTargetResolver(private val project: Project) {

    fun resolveClass(fqn: String): ClassTarget.ClassRef? {
        val scope = GlobalSearchScope.allScope(project)
        val psiClass = try {
            JavaPsiFacade.getInstance(project).findClass(fqn, scope)
        } catch (_: IndexNotReadyException) {
            return null
        } ?: return null
        // K2 wraps Kotlin classes in a light class whose containingFile may not be
        // a KtFile directly. Re-derive via the VFS path so visitors always see source-level PSI.
        val vf = psiClass.containingFile?.virtualFile
        val ktFile: KtFile? = when {
            vf == null -> null
            vf.extension == "kt" -> PsiManager.getInstance(project).findFile(vf) as? KtFile
            else -> psiClass.containingFile as? KtFile
        }
        val ktClass = findKtClassOrObject(ktFile, fqn)
        val packageName = psiClass.qualifiedName?.substringBeforeLast('.', "") ?: ""
        return ClassTarget.ClassRef(
            fqn = fqn,
            displayName = psiClass.name ?: fqn.substringAfterLast('.'),
            filePath = vf?.path,
            psiClass = psiClass,
            ktClass = ktClass,
            packageName = packageName,
        )
    }

    fun isActivityClass(psi: PsiClass): Boolean = inheritsAny(psi, ACTIVITY_BASES)
    fun isDialogClass(psi: PsiClass): Boolean = inheritsAny(psi, DIALOG_BASES)
    fun isFragmentLike(psi: PsiClass): Boolean = inheritsAny(psi, FRAGMENT_BASES)
    fun isApplicationClass(psi: PsiClass): Boolean = inheritsAny(psi, APPLICATION_BASES)

    /**
     * Generated boilerplate that shouldn't be treated as a real screen:
     *  - Hilt @AndroidEntryPoint wrapper classes (`Hilt_FooFragment`, `Hilt_FooActivity`)
     *  - anything sitting under a `build/generated/` directory (kapt / ksp / dagger / hilt output)
     */
    fun isGeneratedClass(psi: PsiClass): Boolean {
        val name = psi.name.orEmpty()
        if (name.startsWith("Hilt_")) return true
        val path = psi.containingFile?.virtualFile?.path.orEmpty()
        if (path.contains("/build/generated/")) return true
        return false
    }

    private fun inheritsAny(psi: PsiClass, bases: List<String>): Boolean = try {
        bases.any { InheritanceUtil.isInheritor(psi, it) }
    } catch (_: IndexNotReadyException) {
        false
    }

    private fun findKtClassOrObject(ktFile: KtFile?, fqn: String): KtClassOrObject? {
        if (ktFile == null) return null
        val short = fqn.substringAfterLast('.')
        return ktFile.declarations.filterIsInstance<KtClassOrObject>().firstOrNull { it.name == short }
            ?: ktFile.declarations.filterIsInstance<KtClassOrObject>().firstOrNull { it.fqName?.asString() == fqn }
    }

    companion object {
        val ACTIVITY_BASES = listOf(
            "android.app.Activity",
            "androidx.activity.ComponentActivity",
            "androidx.appcompat.app.AppCompatActivity",
            "androidx.fragment.app.FragmentActivity",
        )
        val DIALOG_BASES = listOf(
            "androidx.fragment.app.DialogFragment",
            "com.google.android.material.bottomsheet.BottomSheetDialogFragment",
            "android.app.Dialog",
        )
        val FRAGMENT_BASES = listOf(
            "androidx.fragment.app.Fragment",
            "android.app.Fragment",
            "androidx.leanback.app.Fragment",
        )
        val APPLICATION_BASES = listOf(
            "android.app.Application",
        )
    }
}
