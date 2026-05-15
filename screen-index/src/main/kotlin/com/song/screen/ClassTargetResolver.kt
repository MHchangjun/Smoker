package com.song.screen

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
        val psiClass = JavaPsiFacade.getInstance(project).findClass(fqn, scope) ?: return null
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

    fun isActivityClass(psi: PsiClass): Boolean {
        for (base in ACTIVITY_BASES) {
            if (InheritanceUtil.isInheritor(psi, base)) return true
        }
        return false
    }

    fun isDialogClass(psi: PsiClass): Boolean {
        for (base in DIALOG_BASES) {
            if (InheritanceUtil.isInheritor(psi, base)) return true
        }
        return false
    }

    fun isFragmentLike(psi: PsiClass): Boolean {
        for (base in FRAGMENT_BASES) {
            if (InheritanceUtil.isInheritor(psi, base)) return true
        }
        return false
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
    }
}
