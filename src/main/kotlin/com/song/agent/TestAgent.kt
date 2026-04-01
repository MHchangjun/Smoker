package com.song.agent

import ai.koog.agents.core.agent.AIAgentService
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.features.eventHandler.feature.EventHandler
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.llms.SingleLLMPromptExecutor
import ai.koog.prompt.params.LLMParams
import com.song.agent.prompt.SYSTEM_PROMPT
import com.song.agent.subagent.getSubAgentDefinitions
import com.song.agent.subgraph.ActivityInput
import com.song.agent.subgraph.ActivityScreenResult
import com.song.agent.subgraph.activitySubgraph
import com.song.agent.tool.*

class TestAgent(
    private val projectRoot: String,
    private val shellTool: ShellTool,
    private val grepTool: GrepTool,
    private val globTool: GlobTool,
    private val readFileTool: ReadFileTool,
    private val writeFileTool: WriteFileTool
) {
    suspend fun start(): ActivityScreenResult {
        val input = ActivityInput(
            projectRoot = projectRoot,
            activity = " /Users/nate/StudioProjects/kloud/app/src/playStore/java/com/frograms/wplay/MainNavActivity.kt"
        )

        return start(input)
    }

    private suspend fun start(input: ActivityInput): ActivityScreenResult {
        val agentService = buildAgentService()
        return agentService.createAgentAndRun(input)
    }

    private fun buildAgentService(): AIAgentService<ActivityInput, ActivityScreenResult, *> {
        val executor = SingleLLMPromptExecutor(
            OpenAILLMClient(
                "",
                OpenAIClientSettings("http://100.99.171.25:8080")
            )
        )

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
            strategy = buildStrategy(),
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
                    }

                    onNodeExecutionStarting {
                        println("use token : ${it.context.llm.prompt.latestTokenUsage}")
                    }

                    onToolCallFailed { ctx ->
                        log("onToolCallFailed", ctx.toolName)
                        println(ctx.error?.message.toString())
                    }
                }
            },
            toolRegistry = ToolRegistry {
                tool(TaskTool(getSubAgentDefinitions(grepTool, globTool, readFileTool)))
                tool(shellTool)
                tool(grepTool)
                tool(globTool)
                tool(readFileTool)
                tool(writeFileTool)
            }
        )
    }

    private fun buildStrategy() = strategy<ActivityInput, ActivityScreenResult>("test-agent") {
        val activitySubgraph by activitySubgraph()

        nodeStart then activitySubgraph then nodeFinish
    }

    private fun log(tag: String, vararg parts: Any?) {
        val msg = parts.filterNotNull().joinToString(" | ") { it.toString() }
        println("[$tag] $msg")
    }
}
