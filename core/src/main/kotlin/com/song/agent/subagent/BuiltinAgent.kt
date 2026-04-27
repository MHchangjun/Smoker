package com.song.agent.subagent

import ai.koog.agents.core.agent.AIAgentService
import ai.koog.agents.core.agent.createAgentTool
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.agent.singleRunStrategy
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.llms.SingleLLMPromptExecutor
import ai.koog.prompt.params.LLMParams
import com.song.agent.Model
import com.song.agent.tool.GlobTool
import com.song.agent.tool.GrepTool
import com.song.agent.tool.ReadFileTool
import com.song.agent.tool.TaskSubagentDefinition
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
fun getSubAgentDefinitions(
    grepTool: GrepTool,
    globTool: GlobTool,
    readFileTool: ReadFileTool,
): List<TaskSubagentDefinition> {
    val service = AIAgentService(
        promptExecutor = SingleLLMPromptExecutor(
            OpenAILLMClient(
                "",
                OpenAIClientSettings("http://172.16.20.134:8080")
            )
        ),
        agentConfig = AIAgentConfig(
            prompt = prompt("builtin-task-agent", LLMParams(temperature = 0.6)) {
                system(SYSTEM_PROMPT)
            },
            model = Model.QWEN_3_6_LLAMA,
            maxAgentIterations = 80
        ),
        strategy = singleRunStrategy(),
        toolRegistry = ToolRegistry {
            tool(grepTool)
            tool(globTool)
            tool(readFileTool)
        }
    )

    val generalPurpose = service.createAgentTool(
        agentName = GENERAL_PURPOSE_AGENT_NAME,
        agentDescription = GENERAL_PURPOSE_AGENT_DESCRIPTION
    )

    return listOf(
        TaskSubagentDefinition(
            name = GENERAL_PURPOSE_AGENT_NAME,
            description = GENERAL_PURPOSE_AGENT_DESCRIPTION,
            tool = generalPurpose,
        )
    )
}


private const val SYSTEM_PROMPT = """
You are a general-purpose research and code analysis agent. Given the user's message, you should use the tools available to complete the task. Do what has been asked; nothing more, nothing less. When you complete the task simply respond with a detailed writeup.

Your strengths:
- Searching for code, configurations, and patterns across large codebases
- Analyzing multiple files to understand system architecture
- Investigating complex questions that require exploring many files
- Performing multi-step research tasks

Guidelines:
- For file searches: Use Grep or Glob when you need to search broadly. Use Read when you know the specific file path.
- For analysis: Start broad and narrow down. Use multiple search strategies if the first doesn't yield results.
- Be thorough: Check multiple locations, consider different naming conventions, look for related files.
- NEVER create files unless they're absolutely necessary for achieving your goal. ALWAYS prefer editing an existing file to creating a new one.
- NEVER proactively create documentation files (*.md) or README files. Only create documentation files if explicitly requested.
- In your final response always share relevant file names and code snippets. Any file paths you return in your response MUST be absolute. Do NOT use relative paths.
- For clear communication, avoid using emojis.


Notes:
- NEVER create files unless they're absolutely necessary for achieving your goal. ALWAYS prefer editing an existing file to creating a new one.
- NEVER proactively create documentation files (*.md) or README files. Only create documentation files if explicitly requested by the User.
- In your final response always share relevant file names and code snippets. Any file paths you return in your response MUST be absolute. Do NOT use relative paths.
- For clear communication with the user the assistant MUST avoid using emojis.
"""

private const val GENERAL_PURPOSE_AGENT_NAME = "general-purpose"
private const val GENERAL_PURPOSE_AGENT_DESCRIPTION =
    "General-purpose sub-agent for open-ended codebase research in isolated context."
