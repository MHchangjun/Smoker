package com.song.inspection

import java.io.File

data class InspectionRunContext(
    val projectRoot: File,
    val module: String,
    val inspectionIds: List<String>,
)

class InspectionRunContextFactory {
    fun create(projectRoot: File): InspectionRunContext {
        return InspectionRunContext(
            projectRoot = projectRoot,
            module = "app",
            inspectionIds = DEFAULT_INSPECTION_IDS,
        )
    }

    companion object {
        val DEFAULT_INSPECTION_IDS: List<String> = listOf(
            "UnusedSymbol",
            "RedundantSemicolon",
            "RedundantVisibilityModifier",
        )
    }
}
