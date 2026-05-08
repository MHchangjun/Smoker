package com.song.lint

import java.io.File

data class LintRunContext(
    val projectRoot: File,
    val task: String,
)

class LintRunContextFactory {
    fun create(projectRoot: File): LintRunContext {
        // Root-level `lint` task: AGP aggregates per-module variant tasks. Reports land at
        // <module>/build/reports/lint-results.{xml,html,sarif} (variant-less) and/or
        // lint-results-<variant>.{xml,sarif}, depending on AGP version.
        return LintRunContext(
            projectRoot = projectRoot,
            task = "lint",
        )
    }
}
