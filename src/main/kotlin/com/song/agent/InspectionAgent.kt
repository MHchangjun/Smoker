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
import com.song.agent.subagent.getSubAgentDefinitions
import com.song.agent.tool.EditTool
import com.song.agent.tool.GlobTool
import com.song.agent.tool.GrepTool
import com.song.agent.tool.ReadFileTool
import com.song.agent.tool.ShellTool
import com.song.agent.tool.TaskTool
import com.song.agent.tool.ToolNames
import com.song.agent.tool.WriteFileTool

class InspectionAgent(
    private val projectRoot: String,
    private val baseTool: ShellTool,
    private val editTool: EditTool,
    private val grepTool: GrepTool,
    private val globTool: GlobTool,
    private val readFileTool: ReadFileTool,
    private val writeFileTool: WriteFileTool,
) {
    suspend fun start(userPrompt: String): String {
        val agentService = buildAgentService()
        return agentService.createAgentAndRun(userPrompt)
    }

    private fun buildAgentService(): AIAgentService<String, String, *> {
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
                    "smoker-inspection",
                    LLMParams(temperature = 0.3)
                ) {
                    system(systemPrompt(projectRoot))
                },
                model = Model.QWEN_3_5_LLAMA,
                maxAgentIterations = 1000
            ),
            strategy = singleRunStrategy(),
            installFeatures = {
                install(EventHandler.Feature) {
                    onAgentStarting { ctx ->
                        log("AgentStart", ctx.context.agentInput)
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
                tool(TaskTool(getSubAgentDefinitions(grepTool, globTool, readFileTool)))
                tool(editTool)
                tool(baseTool)
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

private fun systemPrompt(projectPath: String) = """
You are Qwen Code, an interactive CLI agent developed by Alibaba Group. You fix exactly one JetBrains inspection finding at a time in an Android/Kotlin project.

# Core Mandates

- Follow the existing project conventions and architecture.
- Make the smallest safe change that resolves the single reported inspection finding.
- Preserve behavior. If a real fix is risky, prefer the narrowest suppression that resolves only the reported finding.
- Do not batch unrelated fixes from the same file.
- Do not revert unrelated local changes.
- Before using file tools, always use absolute paths rooted at the project root.

# Workflow

1. Read only the file and nearby context needed to understand the specific finding.
2. Apply the minimal fix for that single finding.
3. Do not touch unrelated findings.
4. Output only a one-line git commit message at the end. Format: `fix(inspect): <what changed>`.

# Tool Usage

- Use ${ToolNames.TASK} for codebase search when useful.
- Use ${ToolNames.SHELL} only for non-interactive commands.

Absolute path: $projectPath
""".trimIndent()
