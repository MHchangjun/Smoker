package com.song.screen.datasource

import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.song.screen.refreshProjectRootInVfs
import java.io.File

/**
 * Drive a single scan pass: VFS refresh → wait for smart mode → ReadAction
 * scan via [UiDataSourceScanner] → persist [UiDataSourceScanReport] under
 * `.smoker/ui-datasource-scan.json` → echo a summary through the [log]
 * callback so the tool-window indicator stays useful.
 *
 * Read-only. The downstream refactoring agent consumes the JSON file.
 */
class UiDataSourceScanService(
    private val project: Project,
    private val scanner: UiDataSourceScanner,
    private val store: UiDataSourceScanStore,
) {

    suspend fun run(projectRoot: File, log: (String) -> Unit = {}): UiDataSourceScanReport {
        log("[ui-datasource] phase begin root=${projectRoot.absolutePath}")
        refreshProjectRootInVfs(projectRoot)

        val dumb = DumbService.getInstance(project)
        dumb.waitForSmartMode()
        log("[ui-datasource] smart mode reached")

        val violations = dumb.runReadActionInSmartMode<List<UiDataSourceViolation>> {
            scanner.scan(log)
        }

        printSummary(violations, log)

        val report = UiDataSourceScanReport(
            generatedAtEpochMs = System.currentTimeMillis(),
            projectRoot = projectRoot.absolutePath,
            violations = violations,
        )
        val written = store.save(projectRoot, report)
        log("[ui-datasource] wrote ${violations.size} violations to ${written.absolutePath}")
        return report
    }

    private fun printSummary(violations: List<UiDataSourceViolation>, log: (String) -> Unit) {
        val sep = "=".repeat(72)
        log(sep)
        log("UI → DATA-SOURCE VIOLATIONS (${violations.size})")
        log(sep)

        if (violations.isEmpty()) {
            log("  (none — UI layer is clean against the current leaf catalog)")
            log(sep)
            return
        }

        val byLeaf = violations.groupBy { it.leaf }.toList().sortedByDescending { it.second.size }
        log("-- by leaf (${byLeaf.size}) --")
        for ((leaf, list) in byLeaf) log("  ${list.size.toString().padStart(4)}  $leaf")
        log("")

        val byKind = violations.groupBy { it.callerKind }.toList().sortedByDescending { it.second.size }
        log("-- by caller kind (${byKind.size}) --")
        for ((kind, list) in byKind) log("  ${list.size.toString().padStart(4)}  $kind")
        log("")

        val byHop = violations.groupBy { it.hopDistance }.toList().sortedBy { it.first }
        log("-- by hop distance (1 = direct, 2+ = via wrapper) --")
        for ((hops, list) in byHop) log("  ${list.size.toString().padStart(4)}  ${hops}-hop")
        log("")

        val byCaller = violations.groupBy { it.callerClassFqn ?: it.callerFile }
            .toList().sortedByDescending { it.second.size }
        log("-- detail (${byCaller.size} callers) --")
        for ((caller, list) in byCaller) {
            log("  $caller  (${list.size})")
            for (v in list) {
                log("    [${v.matchKind}] ${v.leaf}  (${v.hopDistance}-hop)")
                log("      ${shortPath(v.callerFile)}:${v.callerLine}  ${v.callerExcerpt}")
                log("      chain: ${renderChain(v)}")
            }
        }
        log(sep)
    }

    private fun renderChain(v: UiDataSourceViolation): String =
        v.chain.joinToString(separator = "  →  ") { it.displayName }

    private fun shortPath(absPath: String): String {
        val root = project.basePath ?: return absPath
        return if (absPath.startsWith(root)) absPath.removePrefix(root).removePrefix("/") else absPath
    }
}
