package com.song.screen.tree

import com.intellij.openapi.application.ReadAction
import com.song.screen.ActivityLayerTree
import com.song.screen.ClassTarget
import com.song.screen.ClassTargetResolver
import com.song.screen.HostEdge
import com.song.screen.activity.ActivityRootResolver
import com.song.screen.host.HostedChildExtractor

/**
 * BFS host-only closure starting from an Activity root.
 *
 * Stop conditions per node:
 *  - Foreign Activity (other root) reached at depth > 0 → include as `OTHER_ACTIVITY`, no expand.
 *  - `depth >= depthLimit` → include, no expand, mark `truncatedReason`.
 *  - `maxNodesPerScreen` reached → stop the whole BFS, set `truncated=true`.
 *  - cycle (FQN already visited) → skip (still emit the edge so DAG parents accumulate).
 *
 * DAG: same FQN is added to `nodes` only once. The earliest-reached depth is recorded.
 * Subsequent reach-edges still produce [HostEdge] entries, so `parents` aggregates.
 */
class LayerTreeBuilder(
    private val resolver: ClassTargetResolver,
    private val extractor: HostedChildExtractor,
    private val classifier: UnitClassifier,
    private val config: TreeConfig,
) {

    suspend fun build(root: ActivityRootResolver.ActivityRoot, log: (String) -> Unit): ActivityLayerTree {
        val visited = LinkedHashMap<String, UnitClassifier.RawNode>()
        val edges = ArrayList<HostEdge>()
        val parentsByChild = LinkedHashMap<String, LinkedHashSet<String>>()
        val queue = ArrayDeque<QueueItem>()
        var truncated = false
        var truncationReason: String? = null

        queue += QueueItem(root.target, depth = 0)
        visited[root.target.fqn] = UnitClassifier.RawNode(
            target = root.target,
            depth = 0,
            isOtherActivity = false,
            truncatedReason = null,
        )

        bfs@ while (queue.isNotEmpty()) {
            val item = queue.removeFirst()
            val parentTarget = item.target

            if (visited.size >= config.maxNodesPerScreen) {
                truncated = true
                truncationReason = "max-nodes-per-screen"
                log("[screen-index] ${root.target.fqn}: truncated at ${visited.size} nodes")
                break@bfs
            }

            // Only expand if this node is allowed to (root, or in-budget, non-foreign).
            val rawParent = visited[parentTarget.fqn] ?: continue
            if (rawParent.isOtherActivity) continue
            if (rawParent.truncatedReason != null) continue

            val children = ReadAction.compute<List<com.song.screen.host.HostedChild>, Throwable> {
                extractor.extract(parentTarget)
            }

            for (child in children) {
                val resolution = ReadAction.compute<ChildResolution?, Throwable> {
                    val target = resolver.resolveClass(child.childFqn) ?: return@compute null
                    val isFragment = resolver.isFragmentLike(target.psiClass)
                    val isActivity = resolver.isActivityClass(target.psiClass)
                    // Hosting algorithm cares only about Fragment / Activity nodes.
                    // Bare View / widget / layout classes (`<CoordinatorLayout>`, `<RecyclerView>`, ...)
                    // are noise — drop them entirely (no node, no edge).
                    if (!isFragment && !isActivity) return@compute null
                    ChildResolution(target, isFragment = isFragment, isActivity = isActivity)
                } ?: continue

                val childTarget = resolution.target
                edges += HostEdge(
                    parentFqn = parentTarget.fqn,
                    childFqn = childTarget.fqn,
                    via = child.via,
                    sourceFile = child.sourceFile,
                )
                parentsByChild.getOrPut(childTarget.fqn) { LinkedHashSet() } += parentTarget.fqn

                if (childTarget.fqn in visited) continue

                val childDepth = item.depth + 1
                val (truncReason, shouldQueue) = when {
                    resolution.isActivity -> "other-activity" to false
                    childDepth >= config.depthLimit -> "depth-limit" to false
                    else -> null to true
                }
                visited[childTarget.fqn] = UnitClassifier.RawNode(
                    target = childTarget,
                    depth = childDepth,
                    isOtherActivity = resolution.isActivity,
                    truncatedReason = truncReason,
                )
                if (shouldQueue) queue += QueueItem(childTarget, depth = childDepth)
            }
        }

        val parentsMap = parentsByChild.mapValues { it.value.toList() }
        val nodes = classifier.classify(visited.values.toList(), edges, parentsMap)

        return ActivityLayerTree(
            rootFqn = root.target.fqn,
            displayName = root.displayLabel ?: root.target.displayName,
            manifestPath = root.manifestPath,
            nodes = nodes,
            edges = edges,
            truncated = truncated,
            truncationReason = truncationReason,
        )
    }

    private data class QueueItem(val target: ClassTarget.ClassRef, val depth: Int)
    private data class ChildResolution(
        val target: ClassTarget.ClassRef,
        val isFragment: Boolean,
        val isActivity: Boolean,
    )
}
