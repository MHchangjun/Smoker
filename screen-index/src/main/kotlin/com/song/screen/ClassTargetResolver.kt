package com.song.screen

import com.intellij.psi.PsiClass

/**
 *
 * Callers must execute inside a `ReadAction`.
 */
class ClassTargetResolver {
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
}
