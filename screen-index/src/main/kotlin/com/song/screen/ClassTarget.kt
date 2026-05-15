package com.song.screen

import com.intellij.psi.PsiClass
import org.jetbrains.kotlin.psi.KtClassOrObject

/**
 * Resolved class reference used as a BFS queue item in the layer-tree builder.
 *
 * `fqn` is the canonical key for the visited set — the same FQN never enters
 * the queue twice, even across DAG paths.
 */
sealed class ClassTarget {
    abstract val fqn: String
    abstract val displayName: String
    abstract val filePath: String?

    data class ClassRef(
        override val fqn: String,
        override val displayName: String,
        override val filePath: String?,
        val psiClass: PsiClass,
        val ktClass: KtClassOrObject?,
        val packageName: String,
    ) : ClassTarget()
}
