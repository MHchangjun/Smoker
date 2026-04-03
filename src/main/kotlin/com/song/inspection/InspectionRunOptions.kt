package com.song.inspection

import java.io.File

internal data class InspectionRunOptions(
    val projectRoot: File,
    val profilePath: File,
    val outputDir: File,
    val inspectBin: String
)
