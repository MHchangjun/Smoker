package com.song.workflow

import java.io.File

internal data class DetektRunContext(
    val projectRoot: File,
    val module: String,
    val task: String
)

internal class DetektRunContextFactory {
    fun create(projectRoot: File): DetektRunContext {
        val module = "app"
        val task = ":$module:detekt"
        return DetektRunContext(
            projectRoot = projectRoot,
            module = module,
            task = task
        )
    }
}
