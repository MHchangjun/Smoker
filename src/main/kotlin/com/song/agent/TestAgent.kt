package com.song.agent

import ai.koog.agents.core.agent.AIAgentService
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.agent.singleRunStrategy
import ai.koog.agents.core.dsl.builder.forwardTo
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.dsl.extension.nodeExecuteMultipleTools
import ai.koog.agents.core.dsl.extension.nodeLLMRequestMultiple
import ai.koog.agents.core.dsl.extension.nodeLLMSendMultipleToolResults
import ai.koog.agents.core.dsl.extension.onMultipleAssistantMessages
import ai.koog.agents.core.dsl.extension.onMultipleToolCalls
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.features.eventHandler.feature.EventHandler
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.llms.SingleLLMPromptExecutor
import ai.koog.prompt.executor.llms.all.simpleOllamaAIExecutor
import ai.koog.prompt.executor.llms.all.simpleOpenAIExecutor
import ai.koog.prompt.params.LLMParams
import com.song.agent.prompt.SYSTEM_PROMPT
import com.song.agent.subagent.getSubAgentDefinitions
import com.song.agent.tool.ShellTool
import com.song.agent.tool.EditTool
import com.song.agent.tool.GlobTool
import com.song.agent.tool.GrepTool
import com.song.agent.tool.ReadFileTool
import com.song.agent.tool.TaskTool
import com.song.agent.tool.WriteFileTool

class TestAgent(
    private val shellTool: ShellTool,
    private val grepTool: GrepTool,
    private val globTool: GlobTool,
    private val editTool: EditTool,
    private val readFileTool: ReadFileTool,
    private val writeFileTool: WriteFileTool
) {
    suspend fun start(): String {
        val userPrompt = """
Start Phase 0 gates for this project and keep a short running note (Findings / Hypothesis / Next).
After Phase 0, pick one hotspot from the startup path and do one meaningful refactor aligned to your hypothesis.
Constraints: keep the diff small, don’t change product behavior, and run the most relevant verification command if feasible.
        """.trimIndent()
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

//        val ollama = simpleOllamaAIExecutor()

        return AIAgentService(
            promptExecutor = executor,
            agentConfig = AIAgentConfig(
                prompt = prompt(
                    "smoker",
                    LLMParams(temperature = 0.7)
                ) {
                    system(SYSTEM_PROMPT)
                },
                model = Model.QWEN_3_5_LLAMA,
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
                tool(editTool)
                tool(TaskTool(getSubAgentDefinitions(grepTool, globTool, readFileTool)))
                tool(shellTool)
                tool(grepTool)
                tool(globTool)
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
