package com.song.detekt

import java.io.File

data class DetektRunContext(
    val projectRoot: File,
    val module: String,
    val task: String
)

class DetektRunContextFactory {
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
