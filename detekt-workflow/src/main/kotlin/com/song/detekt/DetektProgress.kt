package com.song.detekt

import com.song.sarif.Finding

data class DetektRuleProgress(
    val ruleId: String? = null,
    val totalFilesInRule: Int = 0,
    val remainingFilesInRule: Int = 0,
    val currentFileIndex: Int = 0,
    val currentFilePath: String? = null,
    val currentFindings: List<Finding> = emptyList(),
)

fun interface DetektProgressListener {
    fun onRuleProgress(progress: DetektRuleProgress)

    companion object {
        val NONE = DetektProgressListener { }
    }
}
