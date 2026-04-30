package com.song.detekt

import com.song.sarif.Finding

data class UpcomingTask(
    val ruleId: String,
    val filePath: String,
    val findingCount: Int,
)

data class PreviousFileOutcome(
    val ruleId: String,
    val filePath: String,
    val committed: Boolean,
    val commitSha: String?,
    val rawMessage: String,
    val durationMs: Long,
)

data class DetektRuleProgress(
    val ruleId: String? = null,
    val totalFilesInRule: Int = 0,
    val remainingFilesInRule: Int = 0,
    val currentFileIndex: Int = 0,
    val currentFilePath: String? = null,
    val currentFindings: List<Finding> = emptyList(),
    val upcoming: List<UpcomingTask> = emptyList(),
    val lastOutcome: PreviousFileOutcome? = null,
)

fun interface DetektProgressListener {
    fun onRuleProgress(progress: DetektRuleProgress)

    companion object {
        val NONE = DetektProgressListener { }
    }
}
