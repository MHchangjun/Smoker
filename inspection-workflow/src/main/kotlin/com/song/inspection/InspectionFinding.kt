package com.song.inspection

data class InspectionFinding(
    val ruleId: String,
    val level: String?,
    val message: String?,
    val uriBaseId: String?,
    val uri: String?,
    val absolutePath: String?,
    val startLine: Int?,
    val startColumn: Int?,
    val endLine: Int?,
    val endColumn: Int?
)
