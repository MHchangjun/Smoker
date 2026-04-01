package com.song.workflow

import java.io.File

internal data class LintRunContext(
    val projectRoot: File,
    val module: String,
    val task: String
)

internal class LintRunContextFactory {
    fun create(projectRoot: File): LintRunContext {
        val module = "app"
        val task = ":$module:detekt"
        return LintRunContext(
            projectRoot = projectRoot,
            module = module,
            task = task
        )
    }
}
