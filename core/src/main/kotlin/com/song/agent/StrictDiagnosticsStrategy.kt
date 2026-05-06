package com.song.agent

import ai.koog.agents.core.agent.context.AIAgentGraphContextBase
import ai.koog.agents.core.agent.entity.AIAgentGraphStrategy
import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.dsl.extension.*
import ai.koog.prompt.message.Message
import com.song.agent.tool.ToolNames

internal fun strictDiagnosticsStrategy(): AIAgentGraphStrategy<String, String> =
    strategy("strict_diagnostics") {
        val nodeCallLLM by nodeLLMRequestMultiple()
        val nodeExecuteTool by nodeExecuteMultipleTools(parallelTools = false)
        val nodeSendToolResult by nodeLLMSendMultipleToolResults()
        val nodeNagPendingErrors by node<List<Message.Assistant>, List<Message.Response>> {
            val pending = llm.readSession { pendingDiagnosticErrors(prompt.messages) }
            llm.writeSession {
                appendPrompt {
                    user(buildPendingErrorsNag(pending))
                }
                requestLLMMultiple()
            }
        }

        edge(nodeStart forwardTo nodeCallLLM)

        edge(nodeCallLLM forwardTo nodeExecuteTool onMultipleToolCalls { true })
        edge(
            nodeCallLLM forwardTo nodeNagPendingErrors
                onMultipleAssistantMessages { true }
                onCondition { hasUnresolvedDiagnostics() }
        )
        edge(
            nodeCallLLM forwardTo nodeFinish
                onMultipleAssistantMessages { true }
                onCondition { !hasUnresolvedDiagnostics() }
                transformed { it.joinToString("\n") { message -> message.content } }
        )

        edge(nodeExecuteTool forwardTo nodeSendToolResult)

        edge(nodeSendToolResult forwardTo nodeExecuteTool onMultipleToolCalls { true })
        edge(
            nodeSendToolResult forwardTo nodeNagPendingErrors
                onMultipleAssistantMessages { true }
                onCondition { hasUnresolvedDiagnostics() }
        )
        edge(
            nodeSendToolResult forwardTo nodeFinish
                onMultipleAssistantMessages { true }
                onCondition { !hasUnresolvedDiagnostics() }
                transformed { it.joinToString("\n") { message -> message.content } }
        )

        edge(nodeNagPendingErrors forwardTo nodeExecuteTool onMultipleToolCalls { true })
        edge(
            nodeNagPendingErrors forwardTo nodeNagPendingErrors
                onMultipleAssistantMessages { true }
                onCondition { hasUnresolvedDiagnostics() }
        )
        edge(
            nodeNagPendingErrors forwardTo nodeFinish
                onMultipleAssistantMessages { true }
                onCondition { !hasUnresolvedDiagnostics() }
                transformed { it.joinToString("\n") { message -> message.content } }
        )
    }

private suspend fun AIAgentGraphContextBase.hasUnresolvedDiagnostics(): Boolean =
    llm.readSession { pendingDiagnosticErrors(prompt.messages).isNotEmpty() }

private fun pendingDiagnosticErrors(messages: List<Message>): Map<String, Int> {
    val errorsByFile = linkedMapOf<String, Int>()
    for (msg in messages) {
        if (msg !is Message.Tool.Result) continue
        if (msg.tool != ToolNames.EDIT && msg.tool != ToolNames.WRITE_FILE) continue

        val path = extractEditedPath(msg.content) ?: continue
        when (val state = extractDiagnosticsState(msg.content)) {
            null, DiagnosticsState.Unavailable -> {} // unknown — leave prior state untouched
            DiagnosticsState.Clean -> errorsByFile.remove(path)
            is DiagnosticsState.Errors -> errorsByFile[path] = state.count
        }
    }
    return errorsByFile
}

private val EDITED_PATH_REGEX = Regex(
    """(?:The file: |Created new file: )(\S+)|"path"\s*:\s*"([^"]+)""""
)
private val DIAGNOSTICS_REGEX = Regex(
    """\[diagnostics] (?:(\d+) issue\(s\)|(no issues)|(unavailable))"""
)

private fun extractEditedPath(content: String): String? {
    val match = EDITED_PATH_REGEX.find(content) ?: return null
    return match.groupValues[1].ifEmpty { match.groupValues[2] }.ifEmpty { null }
}

private sealed interface DiagnosticsState {
    data object Clean : DiagnosticsState
    data object Unavailable : DiagnosticsState
    data class Errors(val count: Int) : DiagnosticsState
}

private fun extractDiagnosticsState(content: String): DiagnosticsState? {
    val match = DIAGNOSTICS_REGEX.find(content) ?: return null
    val (countStr, noIssues, unavailable) = match.destructured
    return when {
        countStr.isNotEmpty() -> DiagnosticsState.Errors(countStr.toInt())
        noIssues.isNotEmpty() -> DiagnosticsState.Clean
        unavailable.isNotEmpty() -> DiagnosticsState.Unavailable
        else -> null
    }
}

private fun buildPendingErrorsNag(pending: Map<String, Int>): String = buildString {
    appendLine("STOP. You declared completion, but the latest tool results show diagnostic errors still pending in:")
    appendLine()
    pending.entries.sortedBy { it.key }.forEach { (path, count) ->
        val plural = if (count > 1) "s" else ""
        appendLine("- $path ($count error$plural)")
    }
    appendLine()
    append(
        "Do NOT terminate. Use ${ToolNames.READ_FILE} / ${ToolNames.EDIT} / ${ToolNames.WRITE_FILE} " +
            "to inspect and fix every remaining error. If a fix would alter behavior, fall back to @Suppress per Rule 2. " +
            "Continue until the next edit's [diagnostics] section reports `no issues` for every file above."
    )
}
