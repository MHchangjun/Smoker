package com.song.workflow

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

data class WorkflowProgress(
    val ruleId: String? = null,
    val totalFilesInRule: Int = 0,
    val remainingFilesInRule: Int = 0,
    val currentFileIndex: Int = 0,
    val currentFilePath: String? = null,
    val currentFindings: List<Finding> = emptyList(),
    val upcoming: List<UpcomingTask> = emptyList(),
    val lastOutcome: PreviousFileOutcome? = null,
)

fun interface WorkflowProgressListener {
    fun onWorkflowProgress(progress: WorkflowProgress)

    companion object {
        val NONE = WorkflowProgressListener { }
    }
}
