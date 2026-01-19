package com.song.agent

import ai.koog.agents.core.agent.AIAgentService
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.agent.singleRunStrategy
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.features.eventHandler.feature.EventHandler
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.llms.all.simpleOllamaAIExecutor
import ai.koog.prompt.params.LLMParams
import com.song.agent.prompt.SYSTEM_PROMPT
import com.song.agent.tool.ApplyPatchTool
import com.song.agent.tool.ShellCommandTool
import com.song.sarif.Finding

class CodeSmellAgent(
    private val shellCommandTool: ShellCommandTool,
    private val applyPatchTool: ApplyPatchTool
) {
    suspend fun start(finding: Finding): String {
        val userPrompt = buildUserPrompt(finding)
        return start(userPrompt)
    }

    private suspend fun start(userPrompt: String): String {
        val agentService = buildAgentService()
        return agentService.createAgentAndRun(userPrompt)
    }

    private fun buildAgentService(): AIAgentService<String, String, *> {
        return AIAgentService(
            promptExecutor = simpleOllamaAIExecutor(),
            agentConfig = AIAgentConfig(
                prompt = prompt(
                    "smoker",
                    LLMParams(temperature = 0.6)
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
                tool(applyPatchTool)
                tool(shellCommandTool)
            }
        )
    }

    private fun buildUserPrompt(finding: Finding): String {
        val location = buildString {
            append("start=")
            append(formatPosition(finding.startLine, finding.startColumn))
            append(", end=")
            append(formatPosition(finding.endLine, finding.endColumn))
        }

        return """
You are fixing exactly ONE static-analysis finding.

Issue
- message: ${finding.message ?: "no message"}
- file: ${finding.absolutePath ?: finding.uri ?: "unknown"}
- location: $location
""".trimIndent()
    }

    private fun formatPosition(line: Int?, column: Int?): String {
        val lineText = line?.toString() ?: "?"
        val columnText = column?.toString() ?: "?"
        return "$lineText:$columnText"
    }

    private fun log(tag: String, vararg parts: Any?) {
        val msg = parts.filterNotNull().joinToString(" | ") { it.toString() }
        println("[$tag] $msg")
    }
}
