package com.song.git

data class CommitOutcome(
    val committed: Boolean,
    val commitSha: String?,
    val changedPaths: List<String>
) {
    companion object {
        fun none(): CommitOutcome = CommitOutcome(
            committed = false,
            commitSha = null,
            changedPaths = emptyList()
        )
    }
}
