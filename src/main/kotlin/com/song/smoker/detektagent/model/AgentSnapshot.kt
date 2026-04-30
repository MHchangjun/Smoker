package com.song.smoker.detektagent.model

import java.time.Instant

enum class AgentStatus { FIXING, SCANNING, AWAITING_VERIFY, SLEEPING }

data class AgentIdentity(
    val name: String,
    val version: String,
    val uptime: String,
    val cycleNumber: Int,
)

data class TodayStats(
    val fixedToday: Int,
    val remaining: Int,
    val etaClear: String,
)

data class ThinkingStep(val tag: String, val text: String)

data class NowTask(
    val ruleId: String,
    val ruleGroup: String,
    val filePath: String,
    val lineNumber: Int,
    val elapsedMs: Long,
    val step: ThinkingStep,
    val stepIndex: Int,
    val stepTotal: Int,
    val progressPercent: Int,
    val readingNow: String?,
)

sealed class FeedEvent {
    abstract val id: String
    abstract val timestamp: Instant

    /** A new agent run was triggered. */
    data class RunStarted(
        override val id: String,
        override val timestamp: Instant,
        val projectName: String,
    ) : FeedEvent()

    /** DetektFixService picked the next rule batch. */
    data class RuleBatchStarted(
        override val id: String,
        override val timestamp: Instant,
        val ruleId: String,
        val fileCount: Int,
    ) : FeedEvent()

    /** Agent fixed a file and a commit was created. */
    data class FileFixed(
        override val id: String,
        override val timestamp: Instant,
        val ruleId: String,
        val filePath: String,
        val line: Int,
        val summary: String,
        val diffRemoved: List<String>,
        val diffAdded: List<String>,
        val durationMs: Long,
        val commitSha: String?,
    ) : FeedEvent()

    /** Agent finished a file but no commit was produced (no-op, suppress, or failed edit). */
    data class FileSkipped(
        override val id: String,
        override val timestamp: Instant,
        val ruleId: String,
        val filePath: String,
        val reason: String,
        val durationMs: Long,
    ) : FeedEvent()

    /** A tool call threw during agent execution. */
    data class ToolError(
        override val id: String,
        override val timestamp: Instant,
        val toolName: String,
        val message: String,
    ) : FeedEvent()

    /** The whole run completed (or failed). */
    data class RunFinished(
        override val id: String,
        override val timestamp: Instant,
        val fixedCount: Int,
        val durationMs: Long,
        val failure: String?,
    ) : FeedEvent()
}

data class QueuedTask(
    val position: Int,
    val ruleId: String,
    val filePath: String,
    val etaSeconds: Int,
)

data class RuleProgress(
    val ruleId: String,
    val ruleGroup: String,
    val done: Int,
    val total: Int,
    val running: Boolean,
) {
    val remaining: Int get() = (total - done).coerceAtLeast(0)
}

data class AgentSnapshot(
    val status: AgentStatus,
    val identity: AgentIdentity,
    val stats: TodayStats,
    val nowWorking: NowTask?,
    val feed: List<FeedEvent>,
    val queue: List<QueuedTask>,
    val rules: List<RuleProgress>,
    val heartbeatSecondsAgo: Int,
)
