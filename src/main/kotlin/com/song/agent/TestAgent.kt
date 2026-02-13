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
import com.song.agent.tool.BashTool
import com.song.agent.tool.GrepTool
import com.song.agent.tool.ReadFileTool
import com.song.agent.tool.SearchReplaceTool
import com.song.agent.tool.TodoTool
import com.song.agent.tool.WriteFileTool

class TestAgent(
    private val bashTool: BashTool,
    private val grepTool: GrepTool,
    private val searchReplaceTool: SearchReplaceTool,
    private val todoTool: TodoTool,
    private val readFileTool: ReadFileTool,
    private val writeFileTool: WriteFileTool,
) {
    suspend fun start(): String {
        val userPrompt = """
Find all ViewModels that reference any *Repository* directly. Exclude tests. Output file + line numbers as evidence.
        """.trimIndent()
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
                    LLMParams(temperature = 0.2)
                ) {
                    system(SYSTEM_PROMPT)
                },
                model = Model.DEVSTRAL_OLLAMA,
                maxAgentIterations = 1000
            ),
            strategy = singleRunStrategy(),
            installFeatures = {
                install(EventHandler.Feature) {
                    onAgentStarting { ctx ->
                        log("AgentStart", ctx.context.config.prompt.messages.joinToString("\n"))
                    }

                    onAgentCompleted { ctx ->
                        log("AgentFinish", "result=${ctx.result}")
                    }

                    onToolCallStarting { ctx ->
                        log("ToolCall", ctx.toolName, "args=${ctx.toolArgs}")
                    }

                    onToolCallCompleted { ctx ->
                        log("ToolCallResult", ctx.toolName)
                        println(ctx.toolResult.toString())
                    }

                    onToolCallFailed { ctx ->
                        log("onToolCallFailed", ctx.toolName)
                        println(ctx.error?.message.toString())
                    }
                }
            },
            toolRegistry = ToolRegistry {
                tool(searchReplaceTool)
                tool(bashTool)
                tool(todoTool)
                tool(grepTool)
                tool(readFileTool)
                tool(writeFileTool)
            }
        )
    }

    private fun log(tag: String, vararg parts: Any?) {
        val msg = parts.filterNotNull().joinToString(" | ") { it.toString() }
        println("[$tag] $msg")
    }
}
