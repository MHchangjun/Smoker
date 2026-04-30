package com.song.smoker.detektagent

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.song.agent.AgentActivityListener
import com.song.detekt.DetektRuleProgress
import com.song.detekt.PreviousFileOutcome
import com.song.smoker.detektagent.model.AgentIdentity
import com.song.smoker.detektagent.model.AgentSnapshot
import com.song.smoker.detektagent.model.AgentStatus
import com.song.smoker.detektagent.model.DetektAgentTopic
import com.song.smoker.detektagent.model.FeedEvent
import com.song.smoker.detektagent.model.NowTask
import com.song.smoker.detektagent.model.QueuedTask
import com.song.smoker.detektagent.model.RuleProgress
import com.song.smoker.detektagent.model.ThinkingStep
import com.song.smoker.detektagent.model.TodayStats
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

@Service(Service.Level.PROJECT)
class DetektAgentBridge(private val project: Project) : AgentActivityListener {

    private val lock = Any()
    private val seq = AtomicLong(0)

    private var status: AgentStatus = AgentStatus.SLEEPING
    private var identity: AgentIdentity =
        AgentIdentity(name = "detekt-fixer", version = "v0.1.0", uptime = "uptime 0s", cycleNumber = 0)
    private var runStartedAt: Instant? = null
    private var fileStartedAt: Instant = Instant.now()

    private var currentRuleId: String? = null
    private var currentRuleGroup: String = ""
    private var currentFilePath: String? = null
    private var currentLine: Int = 0
    private var currentStepTag: String = "plan"
    private var currentStepText: String = ""
    private var currentReadingNow: String? = null
    private var currentStepIndex: Int = 1

    private val rules: LinkedHashMap<String, RuleProgress> = LinkedHashMap()
    private val feed: ArrayDeque<FeedEvent> = ArrayDeque()
    private var fixedToday: Int = 0
    private var lastEventAt: Instant = Instant.now()
    private var queue: List<QueuedTask> = emptyList()
    private val recentFileDurationsMs: ArrayDeque<Long> = ArrayDeque()
    private val durationWindow = 20
    private val pendingDiffs: HashMap<String, Pair<List<String>, List<String>>> = HashMap()
    private var runFixedCount: Int = 0
    private val seenOutcomeKeys: HashSet<String> = HashSet()

    fun snapshot(): AgentSnapshot = synchronized(lock) { buildSnapshot() }

    fun onRunStart() {
        val now = Instant.now()
        synchronized(lock) {
            runStartedAt = now
            fileStartedAt = now
            lastEventAt = now
            identity = identity.copy(cycleNumber = identity.cycleNumber + 1)
            status = AgentStatus.SCANNING
            currentStepTag = "plan"
            currentStepText = "starting agent"
            currentStepIndex = 1
            runFixedCount = 0
            seenOutcomeKeys.clear()
            addFeed(
                FeedEvent.RunStarted(
                    id = nextId("run"),
                    timestamp = now,
                    projectName = project.name,
                ),
            )
        }
        publish()
    }

    fun onRunFinished(failure: Throwable? = null) {
        val now = Instant.now()
        synchronized(lock) {
            status = AgentStatus.SLEEPING
            currentRuleId = null
            currentFilePath = null
            currentReadingNow = null
            queue = emptyList()
            rules.replaceAll { _, v -> v.copy(running = false) }
            val durationMs = runStartedAt
                ?.let { Duration.between(it, now).toMillis() }
                ?: 0L
            addFeed(
                FeedEvent.RunFinished(
                    id = nextId("done"),
                    timestamp = now,
                    fixedCount = runFixedCount,
                    durationMs = durationMs,
                    failure = failure?.let { "${it.javaClass.simpleName}: ${it.message ?: "(no message)"}" },
                ),
            )
        }
        publish()
    }

    fun recordDiff(filePath: String, removed: List<String>, added: List<String>) {
        synchronized(lock) {
            pendingDiffs[filePath] = removed to added
        }
    }

    fun onRuleProgress(progress: DetektRuleProgress) {
        synchronized(lock) {
            val now = Instant.now()
            lastEventAt = now

            // 1) Surface the previous file's outcome (came from DetektFixService)
            progress.lastOutcome?.let { absorbOutcome(it, now) }

            val ruleId = progress.ruleId
            if (ruleId == null) {
                rules.replaceAll { _, v -> v.copy(running = false) }
                currentRuleId = null
                currentFilePath = null
                return@synchronized
            }
            val total = progress.totalFilesInRule
            val done = progress.currentFileIndex.coerceAtLeast(0)
            val existingGroup = rules[ruleId]?.ruleGroup ?: ""
            rules[ruleId] = RuleProgress(ruleId, existingGroup, done, total, true)
            rules.entries
                .filter { it.key != ruleId }
                .forEach { rules[it.key] = it.value.copy(running = false) }

            if (ruleId != currentRuleId) {
                currentRuleId = ruleId
                currentRuleGroup = existingGroup
                fileStartedAt = now
                addFeed(
                    FeedEvent.RuleBatchStarted(
                        id = nextId("rb"),
                        timestamp = now,
                        ruleId = ruleId,
                        fileCount = total,
                    ),
                )
            }

            val filePath = progress.currentFilePath
            if (filePath != null && filePath != currentFilePath) {
                currentFilePath = filePath
                fileStartedAt = now
                currentLine = progress.currentFindings
                    .minByOrNull { it.startLine ?: Int.MAX_VALUE }?.startLine ?: 0
            } else if (filePath == null) {
                // End-of-batch progress emission carries lastOutcome only.
                currentFilePath = null
            }
            queue = progress.upcoming.mapIndexed { idx, up ->
                QueuedTask(
                    position = idx + 1,
                    ruleId = up.ruleId,
                    filePath = up.filePath,
                    etaSeconds = ((idx + 1) * estimatedSecondsPerFile()).toInt(),
                )
            }
            status = AgentStatus.FIXING
        }
        publish()
    }

    private fun absorbOutcome(outcome: PreviousFileOutcome, now: Instant) {
        val key = outcome.commitSha ?: "${outcome.ruleId}|${outcome.filePath}|${outcome.durationMs}"
        if (!seenOutcomeKeys.add(key)) return
        recordFileDuration(outcome.durationMs)
        if (outcome.committed) {
            val (removed, added) = consumeCapturedDiff(outcome.filePath)
            val summary = firstLine(outcome.rawMessage).take(120)
            addFeed(
                FeedEvent.FileFixed(
                    id = nextId("fix"),
                    timestamp = now,
                    ruleId = outcome.ruleId,
                    filePath = outcome.filePath,
                    line = currentLine,
                    summary = summary.ifBlank { "fix applied" },
                    diffRemoved = removed,
                    diffAdded = added,
                    durationMs = outcome.durationMs,
                    commitSha = outcome.commitSha,
                ),
            )
            fixedToday += 1
            runFixedCount += 1
        } else {
            pendingDiffs.remove(outcome.filePath)
            val reason = parseSkipReason(outcome.rawMessage)
            addFeed(
                FeedEvent.FileSkipped(
                    id = nextId("skip"),
                    timestamp = now,
                    ruleId = outcome.ruleId,
                    filePath = outcome.filePath,
                    reason = reason,
                    durationMs = outcome.durationMs,
                ),
            )
        }
    }

    private fun parseSkipReason(rawMessage: String): String {
        val first = firstLine(rawMessage).take(160)
        return when {
            first.isBlank() -> "no commit"
            first.startsWith("suppress:", ignoreCase = true) -> first
            first.startsWith("refactor:", ignoreCase = true) -> "no diff produced"
            else -> first
        }
    }

    override fun onAgentStart(input: String) {
        synchronized(lock) {
            currentStepTag = "plan"
            currentStepText = firstLine(input).take(80)
            currentStepIndex = 1
            lastEventAt = Instant.now()
        }
        publish()
    }

    override fun onToolCallStart(name: String, args: String) {
        synchronized(lock) {
            val n = name.lowercase()
            currentStepTag = when {
                "edit" in n || "write" in n -> "edit"
                "read" in n -> "read"
                "grep" in n || "glob" in n -> "search"
                "shell" in n -> if ("detekt" in args.lowercase()) "scan" else "shell"
                "lsp" in n -> "verify"
                else -> n.take(8)
            }
            currentStepText = "$name ${truncate(firstLine(args), 80)}"
            currentStepIndex = (currentStepIndex % 4) + 1

            if ("read" in n || "grep" in n || "glob" in n) {
                extractPath(args)?.let { currentReadingNow = it }
            }
            if ("shell" in n && "detekt" in args.lowercase()) {
                status = AgentStatus.SCANNING
            }
            lastEventAt = Instant.now()
        }
        publish()
    }

    override fun onToolCallCompleted(name: String, summary: String) {
        synchronized(lock) {
            lastEventAt = Instant.now()
        }
        publish()
    }

    override fun onToolCallFailed(name: String, message: String) {
        synchronized(lock) {
            val now = Instant.now()
            addFeed(
                FeedEvent.ToolError(
                    id = nextId("err"),
                    timestamp = now,
                    toolName = name,
                    message = truncate(firstLine(message), 160),
                ),
            )
            lastEventAt = now
        }
        publish()
    }

    override fun onReasoning(text: String) {
        synchronized(lock) {
            currentStepTag = "think"
            currentStepText = truncate(firstLine(text), 80)
            lastEventAt = Instant.now()
        }
        publish()
    }

    override fun onAssistant(text: String) {
        synchronized(lock) {
            lastEventAt = Instant.now()
        }
        publish()
    }

    private fun addFeed(event: FeedEvent) {
        feed.addFirst(event)
        while (feed.size > 60) feed.removeLast()
    }

    private fun recordFileDuration(ms: Long) {
        if (ms <= 0) return
        recentFileDurationsMs.addLast(ms)
        while (recentFileDurationsMs.size > durationWindow) recentFileDurationsMs.removeFirst()
    }

    private fun estimatedSecondsPerFile(): Double {
        if (recentFileDurationsMs.isEmpty()) return 30.0
        return recentFileDurationsMs.average() / 1000.0
    }

    private fun consumeCapturedDiff(path: String): Pair<List<String>, List<String>> {
        val captured = pendingDiffs.remove(path) ?: return emptyList<String>() to emptyList()
        val removed = captured.first.take(3)
        val added = captured.second.take(3)
        return removed to added
    }

    private fun publish() {
        val snap = synchronized(lock) { buildSnapshot() }
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            project.messageBus.syncPublisher(DetektAgentTopic.TOPIC).onSnapshot(snap)
        }
    }

    private fun buildSnapshot(): AgentSnapshot {
        val now = Instant.now()
        val totalRemaining = rules.values.sumOf { it.remaining }
        val nowTask = if (status == AgentStatus.SLEEPING || currentRuleId == null) {
            null
        } else {
            val ruleProg = rules[currentRuleId]
            NowTask(
                ruleId = currentRuleId.orEmpty(),
                ruleGroup = currentRuleGroup,
                filePath = currentFilePath.orEmpty(),
                lineNumber = currentLine,
                elapsedMs = Duration.between(fileStartedAt, now).toMillis().coerceAtLeast(0),
                step = ThinkingStep(currentStepTag, currentStepText.ifBlank { "..." }),
                stepIndex = currentStepIndex,
                stepTotal = 4,
                progressPercent = ruleProg
                    ?.takeIf { it.total > 0 }
                    ?.let { (it.done * 100 / it.total).coerceIn(0, 100) }
                    ?: 0,
                readingNow = currentReadingNow,
            )
        }
        val uptime = runStartedAt?.let { "uptime ${formatUptime(Duration.between(it, now))}" }
            ?: "uptime 0s"
        val heartbeat = Duration.between(lastEventAt, now).seconds.toInt().coerceAtLeast(0)
        val etaClear = if (totalRemaining > 0 && recentFileDurationsMs.isNotEmpty()) {
            formatEta((estimatedSecondsPerFile() * totalRemaining).toLong())
        } else if (totalRemaining > 0) {
            "—"
        } else {
            "0"
        }
        return AgentSnapshot(
            status = status,
            identity = identity.copy(uptime = uptime),
            stats = TodayStats(fixedToday, totalRemaining, etaClear),
            nowWorking = nowTask,
            feed = feed.toList(),
            queue = queue,
            rules = rules.values.toList(),
            heartbeatSecondsAgo = heartbeat,
        )
    }

    private fun formatEta(seconds: Long): String {
        return when {
            seconds < 60 -> "~${seconds}s"
            seconds < 3600 -> "~${seconds / 60}m"
            else -> "~${seconds / 3600}h ${(seconds % 3600) / 60}m"
        }
    }

    private fun nextId(prefix: String): String = "$prefix-${seq.incrementAndGet()}"

    private fun firstLine(text: String): String =
        text.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()

    private fun truncate(text: String, max: Int): String =
        if (text.length <= max) text else text.take(max - 1) + "…"

    private fun formatUptime(d: Duration): String {
        val days = d.toDays()
        val hours = d.minusDays(days).toHours()
        val minutes = d.minusDays(days).minusHours(hours).toMinutes()
        return when {
            days > 0 -> "${days}d ${hours}h"
            hours > 0 -> "${hours}h ${minutes}m"
            minutes > 0 -> "${minutes}m"
            else -> "${d.seconds}s"
        }
    }

    private fun extractPath(args: String): String? {
        val match = Regex("([\\w./-]+\\.(?:kt|kts|java|xml|gradle))").find(args)
        return match?.value
    }
}

fun composeActivityListeners(vararg listeners: AgentActivityListener): AgentActivityListener =
    object : AgentActivityListener {
        override fun onAgentStart(input: String) = listeners.forEach { it.onAgentStart(input) }
        override fun onToolCallStart(name: String, args: String) =
            listeners.forEach { it.onToolCallStart(name, args) }
        override fun onToolCallCompleted(name: String, summary: String) =
            listeners.forEach { it.onToolCallCompleted(name, summary) }
        override fun onToolCallFailed(name: String, message: String) =
            listeners.forEach { it.onToolCallFailed(name, message) }
        override fun onReasoning(text: String) = listeners.forEach { it.onReasoning(text) }
        override fun onAssistant(text: String) = listeners.forEach { it.onAssistant(text) }
    }
