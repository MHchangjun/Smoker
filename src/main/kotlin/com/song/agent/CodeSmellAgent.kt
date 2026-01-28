package com.song.agent

import ai.koog.agents.core.agent.AIAgentService
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.agent.singleRunStrategy
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.features.eventHandler.feature.EventHandler
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.llms.SingleLLMPromptExecutor
import ai.koog.prompt.params.LLMParams
import com.song.agent.prompt.SYSTEM_PROMPT
import com.song.agent.tool.SearchReplaceTool
import com.song.agent.tool.ShellCommandTool
import com.song.agent.tool.TodoTool
import com.song.detekt.DetektConfigContext
import com.song.sarif.Finding
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

class CodeSmellAgent(
    private val shellCommandTool: ShellCommandTool,
    private val searchReplaceTool: SearchReplaceTool,
    private val todoTool: TodoTool
) {
    suspend fun start(finding: Finding, detektConfig: DetektConfigContext): String {
        val userPrompt = buildUserPrompt(finding, detektConfig)
        return start(userPrompt)
    }

    private suspend fun start(userPrompt: String): String {
        val agentService = buildAgentService()
        return agentService.createAgentAndRun(userPrompt)
    }

    private fun buildAgentService(): AIAgentService<String, String, *> {
        val executor = SingleLLMPromptExecutor(
            OpenAILLMClient(
                "",
                OpenAIClientSettings("http://172.16.20.134:8080")
            )
        )
        return AIAgentService(
            promptExecutor = executor,
            agentConfig = AIAgentConfig(
                prompt = prompt(
                    "smoker",
                    LLMParams(temperature = 0.2)
                ) {
                    system(SYSTEM_PROMPT)
                },
                model = Model.DEVSTRAL,
                maxAgentIterations = 1000
            ),
            strategy = singleRunStrategy(),
            installFeatures = {
                install(EventHandler.Feature) {
                    onAgentStarting { ctx ->
                        log("AgentStart", ctx.context.agentInput)
                    }

                    onAgentCompleted { ctx ->
                        log("AgentFinish", "result=${ctx.result}")
                    }

                    onToolCallStarting { ctx ->
                        log("ToolCall", ctx.toolName, "args=${ctx.toolArgs}")
                    }

                    onToolCallCompleted { ctx ->
                        log("ToolCallResult", ctx.toolName)
                    }
                }
            },
            toolRegistry = ToolRegistry {
                tool(searchReplaceTool)
                tool(shellCommandTool)
                tool(todoTool)
            }
        )
    }

    private fun buildUserPrompt(finding: Finding, detektConfig: DetektConfigContext?): String {
        val codeBlock = buildCodeBlock(finding)
        val config = buildDetektConfigBlock(finding, detektConfig)

        val ruleId = finding.ruleId.takeIf { it.isNotBlank() } ?: "unknown rule"
        val location = finding.absolutePath ?: finding.uri ?: "unknown file"
        val message = finding.message ?: "no message"

        return """
One code-quality issue: "$message" in $location (rule: $ruleId). Refactor to remove this issue while keeping the behavior the same. Stay within the policy details below.

Code near the issue:
$codeBlock

$config
""".trimIndent()
    }

    private fun buildCodeBlock(finding: Finding): String {
        val snippet = readSnippet(finding)
        return if (snippet.isNullOrBlank()) {
            "```kotlin\n// snippet unavailable\n```"
        } else {
            "```kotlin\n$snippet\n```"
        }
    }

    private fun buildDetektConfigBlock(
        finding: Finding,
        detektConfig: DetektConfigContext?
    ): String {
        val ruleId = finding.ruleId.takeIf { it.isNotBlank() } ?: "unknown"
        val entries = if (detektConfig != null && ruleId != "unknown") {
            detektConfig.relevantConfigEntries(ruleId)
        } else {
            emptyList()
        }
        val sentences = entries.takeIf { it.isNotEmpty() }
            ?.map { entryToSentence(it) }
            ?: listOf("No specific limits were found; keep nesting shallow and readable.")
        val entriesBlock = sentences.joinToString(" ")

        return """
Policy notes: $entriesBlock
""".trimIndent()
    }

    private fun entryToSentence(entry: String): String {
        val key = entry.substringBefore(":", "").trim()
        val value = entry.substringAfter(":", "").trim()
        if (key.isBlank()) return "Setting $entry."

        return when {
            key.endsWith(".active", ignoreCase = true) -> {
                val base = key.removeSuffix(".active")
                val state = if (value.equals("true", ignoreCase = true)) "enabled" else "disabled"
                "Checks for $base are $state."
            }

            key.endsWith(".threshold", ignoreCase = true) -> {
                val base = key.removeSuffix(".threshold")
                if (value.isNotBlank()) "Keep $base at $value or less." else "Keep $base within a shallow limit."
            }

            value.isNotBlank() -> "Setting $key is $value."
            else -> "Setting $key is present."
        }
    }

    private fun readSnippet(finding: Finding): String? {
        val path = resolvePath(finding) ?: return null
        val startLine = finding.startLine ?: return null
        val endLine = finding.endLine ?: startLine
        if (!Files.exists(path)) return null

        val lines = Files.readAllLines(path)
        val startIndex = (startLine - 1).coerceAtLeast(0)
        val endIndex = (endLine - 1).coerceAtLeast(startIndex)
        if (startIndex >= lines.size) return null

        val safeEnd = endIndex.coerceAtMost(lines.lastIndex)
        return lines.subList(startIndex, safeEnd + 1).joinToString("\n")
    }

    private fun resolvePath(finding: Finding): Path? {
        val raw = finding.absolutePath ?: finding.uri ?: return null
        return if (raw.startsWith("file:")) {
            runCatching { Paths.get(java.net.URI(raw)) }.getOrNull()
        } else {
            Paths.get(raw)
        }
    }

    private fun log(tag: String, vararg parts: Any?) {
        val msg = parts.filterNotNull().joinToString(" | ") { it.toString() }
        println("[$tag] $msg")
    }
}
