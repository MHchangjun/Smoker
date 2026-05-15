package com.song.screen.tree

import com.intellij.openapi.application.ReadAction
import com.song.screen.ClassTarget
import com.song.screen.ClassTargetResolver
import com.song.screen.HostEdge
import com.song.screen.LayerNode
import com.song.screen.UnitType

/**
 * Convert raw BFS-collected node data into typed [LayerNode]s.
 *
 * Type derivation order (first match wins):
 *  1. Already marked OTHER_ACTIVITY by the builder (depth>0 reached a foreign Activity) → keep as-is.
 *  2. PSI subclass of `DialogFragment` / `BottomSheetDialogFragment` / `android.app.Dialog` → DIALOG.
 *  3. Class is Activity → HOST_ACTIVITY if any hosts edge originates from it, else LEAF_ACTIVITY.
 *  4. Otherwise (Fragment-ish) → HOST_FRAGMENT if hosts non-empty, else LEAF_FRAGMENT.
 */
class UnitClassifier(private val resolver: ClassTargetResolver) {

    data class RawNode(
        val target: ClassTarget.ClassRef,
        val depth: Int,
        val isOtherActivity: Boolean,
        val truncatedReason: String?,
    )

    fun classify(
        rawNodes: List<RawNode>,
        edges: List<HostEdge>,
        parentsByChild: Map<String, List<String>>,
    ): List<LayerNode> {
        val hostsByParent: Map<String, List<String>> = edges
            .groupBy { it.parentFqn }
            .mapValues { entry -> entry.value.map { it.childFqn }.distinct() }

        return rawNodes.map { raw ->
            val fqn = raw.target.fqn
            val hosts = hostsByParent[fqn].orEmpty()
            val parents = parentsByChild[fqn].orEmpty()
            val type = ReadAction.compute<UnitType, Throwable> {
                deriveType(raw, hosts.isNotEmpty())
            }
            LayerNode(
                fqn = fqn,
                displayName = raw.target.displayName,
                type = type,
                depth = raw.depth,
                filePath = raw.target.filePath,
                parents = parents,
                hosts = hosts,
                truncatedReason = raw.truncatedReason,
            )
        }
    }

    private fun deriveType(raw: RawNode, hasHosts: Boolean): UnitType {
        if (raw.isOtherActivity) return UnitType.OTHER_ACTIVITY
        val psi = raw.target.psiClass
        if (resolver.isDialogClass(psi)) return UnitType.DIALOG
        if (resolver.isActivityClass(psi)) {
            return if (hasHosts) UnitType.HOST_ACTIVITY else UnitType.LEAF_ACTIVITY
        }
        return if (hasHosts) UnitType.HOST_FRAGMENT else UnitType.LEAF_FRAGMENT
    }
}
