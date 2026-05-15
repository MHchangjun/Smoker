package com.song.screen

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.song.screen.activity.ActivityRootResolver
import com.song.screen.tree.LayerTreeBuilder
import java.io.File

class ScreenIndexService(
    private val project: Project,
    private val activityRootResolver: ActivityRootResolver,
    private val treeBuilder: LayerTreeBuilder,
    private val store: ScreenIndexStore,
) {

    suspend fun build(projectRoot: File, log: (String) -> Unit = {}): ScreenIndex {
        log("[screen-index] phase begin root=${projectRoot.absolutePath}")
        refreshProjectRootInVfs(projectRoot)

        DumbService.getInstance(project).waitForSmartMode()
        log("[screen-index] smart mode reached")

        val roots = ReadAction.compute<List<ActivityRootResolver.ActivityRoot>, Throwable> {
            activityRootResolver.findActivityRoots(log)
        }
        log("[screen-index] activity roots=${roots.size}")

        val trees = roots.map { root ->
            log("[screen-index] build tree ${root.target.fqn}")
            val tree = treeBuilder.build(root, log)
            log("[screen-index] done ${root.target.fqn} nodes=${tree.nodes.size} edges=${tree.edges.size}${if (tree.truncated) " [TRUNCATED:${tree.truncationReason}]" else ""}")
            tree
        }

        val index = ScreenIndex(
            generatedAtEpochMs = System.currentTimeMillis(),
            projectRoot = projectRoot.absolutePath,
            screens = trees.sortedBy { it.rootFqn },
        )
        val written = store.save(projectRoot, index)
        log("[screen-index] wrote ${index.screens.size} screens to ${written.absolutePath}")
        return index
    }
}
