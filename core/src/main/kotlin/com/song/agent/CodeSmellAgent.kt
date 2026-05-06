package com.song.agent

import ai.koog.agents.core.agent.AIAgentService
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.features.eventHandler.feature.EventHandler
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.params.LLMParams
import com.song.agent.tool.*
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
class CodeSmellAgent(
    private val projectRoot: String,
//    private val shellTool: ShellTool,
    private val editTool: EditTool,
    private val readFileTool: ReadFileTool,
    private val writeFileTool: WriteFileTool,
    private val grepTool: GrepTool,
    private val globTool: GlobTool,
    private val lspTool: LspTool,
    private val activityListener: AgentActivityListener = AgentActivityListener.NONE,
) {
    suspend fun start(userPrompt: String): String {
        val agentService = buildAgentService()
        return agentService.createAgentAndRun(userPrompt)
    }

    private fun buildAgentService(): AIAgentService<String, String, *> {
        val endpoint = System.getenv("SMOKER_LLM_ENDPOINT") ?: "http://100.99.171.25:8080"
        val executor = MultiLLMPromptExecutor(
            OpenAILLMClient(
                "",
                OpenAIClientSettings(endpoint)
            )
        )
        return AIAgentService(
            promptExecutor = executor,
            agentConfig = AIAgentConfig(
                prompt = prompt(
                    "smoker",
                    LLMParams(temperature = 0.6)
                ) {
                    system(systemPrompt(projectRoot))
                },
                model = Model.QWEN_3_6_LLAMA,
                maxAgentIterations = 1000
            ),
            strategy = strictDiagnosticsStrategy(),
            installFeatures = {
                install(EventHandler.Feature) {
                    onAgentStarting { ctx ->
                        val input = ctx.context.agentInput?.toString().orEmpty()
                        log("AgentStart", input)
                        activityListener.onAgentStart(input)
                    }

                    onToolCallStarting { ctx ->
                        val args = ctx.toolArgs.toString()
                        log("ToolCall", ctx.toolName, "args=$args")
                        activityListener.onToolCallStart(ctx.toolName, args)
                    }

                    onToolCallCompleted { ctx ->
                        val summary = ctx.toolResult.toString()
                        log("ToolCallResult", ctx.toolName, "args=$summary")
                        activityListener.onToolCallCompleted(ctx.toolName, summary)
                    }

                    onLLMCallCompleted { ctx ->
                        ctx.responses.forEach { response ->
                            when (response) {
                                is ai.koog.prompt.message.Message.Reasoning -> {
                                    log("Reasoning", response.content)
                                    activityListener.onReasoning(response.content)
                                }
                                is ai.koog.prompt.message.Message.Assistant -> {
                                    log("Assistant", response.content)
                                    activityListener.onAssistant(response.content)
                                }
                                else -> {}
                            }
                        }
                    }

                    onToolCallFailed { ctx ->
                        val msg = ctx.error?.message ?: "unknown error"
                        log("onToolCallFailed", ctx.toolName, "result = $msg")
                        activityListener.onToolCallFailed(ctx.toolName, msg)
                    }
                }
            },
            toolRegistry = ToolRegistry {
                tool(editTool)
//                tool(shellTool)
                tool(readFileTool)
                tool(writeFileTool)
                tool(grepTool)
                tool(globTool)
                tool(lspTool)
            }
        )
    }

    private fun log(tag: String, vararg parts: Any?) {
        val msg = parts.filterNotNull().joinToString(" | ") { it.toString() }
        println("[$tag] $msg")
    }
}

private fun systemPrompt(projectPath: String) = """
You are a refactoring agent running in a CLI environment.

# Core Mandates

- **Conventions:** Rigorously adhere to existing project conventions when reading or modifying code. Analyze surrounding code and configuration first.
- **Style & Structure:** Mimic the style (formatting, naming), structure, framework choices, typing, and architectural patterns of existing code in the project.
- **Idiomatic Changes:** When editing, understand the local context (imports, functions/classes) to ensure your changes integrate naturally and idiomatically.
- **Comments:** Add code comments sparingly. Focus on *why* something is done, especially for complex logic, rather than *what* is done. Only add high-value comments if necessary for clarity or if requested by the user. Do not edit comments that are separate from the code you are changing. *NEVER* talk to the user or describe your changes through comments.
- **Proactiveness:** Fulfill the user's request thoroughly. When adding features or fixing bugs, this includes adding tests to ensure quality. Consider all created files, especially tests, to be permanent artifacts unless the user says otherwise.
- **Path Construction:** Before using any file system tool (e.g., ${ToolNames.READ_FILE}' or '${ToolNames.WRITE_FILE}'), you must construct the full absolute path for the file_path argument. Always combine the absolute path of the project's root directory with the file's path relative to the root. For example, if the project root is /path/to/project/ and the file is foo/bar/baz.txt, the final path you must use is /path/to/project/foo/bar/baz.txt. If the user provides a relative path, you must resolve it against the root directory to create an absolute path.

# Primary Workflows

## Code Smell Fix Tasks
When requested to fix code smells or lint findings, follow this approach:
- **Resolve:** Use LSP to confirm types, functions, and import paths for any non-local symbol you will reference.
- **Implement:** Apply the minimal fix using the available tools (e.g., '${ToolNames.EDIT}', '${ToolNames.WRITE_FILE}'), strictly adhering to the Rules. Do NOT expand scope beyond the reported issues. Process issues one at a time; do not batch unrelated edits into a single tool call. Edit results include auto-injected `[diagnostics]`, ensure no new errors before moving on.
- **Adapt:** If any fix turns out to risk altering behavior, fall back to `@Suppress` per Rule 2 (can be applied per-issue).
- **Summarize:** Output a single-line summary in one of these formats:
  - When fix applied: `refactor: <what changed>`
  - When @Suppress used per Rule 2: `suppress: <ruleId> due to <reason>`

**Key Principle:** Minimal, isolated fix per issue. No new errors.

### Rules
1. **Minimal change only** : Fix the reported issue and nothing else. Do NOT refactor surrounding code, even if it looks improvable.
2. **Behavior preservation is non-negotiable** : If uncertain whether a change alters behavior, keep the original code and add `@Suppress`.
3. **Write idiomatic Kotlin** : Prefer stdlib functions over manual loops, modern Kotlin APIs over legacy Java utilities.
4. **Stepdown Rule** : When extracting a private function, place it immediately below the calling function.
5. **No fully-qualified names in code** : Never inline FQNs (e.g. `com.foo.bar.Baz`) in signatures, parameter types, or function bodies. Add an `import` and use the simple name.

# Operational Guidelines

## Tone and Style (CLI Interaction)
- **Concise & Direct:** Adopt a professional, direct, and concise tone suitable for a CLI environment.
- **Minimal Output:** Aim for fewer than 3 lines of text output (excluding tool use/code generation) per response whenever practical. Focus strictly on the user's query.
- **Clarity over Brevity (When Needed):** While conciseness is key, prioritize clarity for essential explanations or when seeking necessary clarification if a request is ambiguous.
- **No Chitchat:** Avoid conversational filler, preambles ("Okay, I will now..."), or postambles ("I have finished the changes..."). Get straight to the action or answer.
- **Formatting:** Use GitHub-flavored Markdown. Responses will be rendered in monospace.
- **Tools vs. Text:** Use tools for actions, text output *only* for communication. Do not add explanatory comments within tool calls or code blocks unless specifically part of the required code/command itself.
- **Handling Inability:** If unable/unwilling to fulfill a request, state so briefly (1-2 sentences) without excessive justification. Offer alternatives if appropriate.

## Tool Usage
- **File Paths:** Always use absolute paths when referring to files with tools like '${ToolNames.READ_FILE}' or '${ToolNames.WRITE_FILE}'. Relative paths are not supported. You must provide an absolute path.

Absolute path: $projectPath
""".trimIndent()
