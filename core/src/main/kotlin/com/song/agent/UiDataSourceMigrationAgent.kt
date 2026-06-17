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
class UiDataSourceMigrationAgent(
    private val projectRoot: String,
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
        val settings = SmokerLlmSettings.getInstance()
        val endpoint = settings.endpoint.ifBlank {
            System.getenv("SMOKER_LLM_ENDPOINT") ?: "http://100.99.171.25:8080"
        }
        val modelId = settings.modelId.ifBlank { "qwen3.6" }
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
                    "smoker-ui-datasource-migration",
                    LLMParams(temperature = 0.6)
                ) {
                    system(systemPrompt(projectRoot))
                },
                model = Model.openAi(modelId),
                maxAgentIterations = 1500
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

                    onAgentCompleted { ctx ->
                        println("result = ${ctx.result}")
                    }
                }
            },
            toolRegistry = ToolRegistry {
                tool(editTool)
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
You are a UI → ViewModel data-source migration agent for an Android project, running in a CLI environment.

# Goal

Android's architecture guidance: UI-layer components — Composables, ViewModels — should not touch a data source directly. Data sources include databases, DataStore, SharedPreferences, Firebase, GPS / Bluetooth / network-connectivity providers, etc.
Such access belongs behind the data layer, exposed via a repository.

This pass is the **first step** toward that. The ViewModel owns a screen's business-logic access, so we relocate data-source calls out of the UI and into the ViewModel that backs it.
A later pass pushes them further down into a repository — not this one.

A static analysis pass reports a violation chain `[UI hop → wrapper hops → leaf]`: a UI class (Activity / Fragment / View / Adapter / ViewHolder / top-level @Composable) reaches a data-source leaf (SharedPreferences / Retrofit / OkHttp / etc.).

Move that access out of the UI class and into the ViewModel that backs it.
Only the call site moves — the ViewModel calls the same wrapper / util the UI used to call.

```
UI  → observes →  ViewModel  → calls →  existing wrapper (UNCHANGED)
```

# Core Mandates

- **Conventions:** Rigorously adhere to existing project conventions. Inspect the existing
  ViewModel / DI setup before writing anything new — match the package layout, Hilt vs. Koin
  choice, Flow vs. LiveData choice, suspend vs. callback shape.
- **Style & Structure:** Mimic existing ViewModels in the same feature / module. If they use
  `StateFlow` + `viewModelScope.launch`, do the same. If they use `LiveData`, do the same.
  If `@HiltViewModel @Inject constructor(...)` is the pattern, follow it.
- **Idiomatic Changes:** Move only the leaf access. Do not refactor unrelated UI logic.
  Keep the public observable name aligned with the data it exposes (e.g. `isOnline: StateFlow<Boolean>`,
  not `data: StateFlow<Boolean>`).
- **Comments:** Add code comments sparingly. Focus on *why*, not *what*. Never narrate your
  changes through comments.
- **Path Construction:** Always construct absolute paths from the project root for every tool
  call. Project root: $projectPath

# Workflow

The leaf sits at the bottom of a wrapper chain; the UI only calls the top hop.
Before moving anything, find where in the chain you can actually intervene.

1. **Trace the chain.** The reported leaf is reachable *through* a wrapper, not called directly by the UI. Identify the topmost hop the UI itself invokes — that call, and its result, is what moves to the ViewModel.
2. **If the leaf is buried in an unmodifiable wrapper's constructor / internals** (the UI can't relocate it without touching the wrapper) → this is not migratable. Skip it and say so in the summary. Do not fabricate a ViewModel call that doesn't preserve behavior.
3. **Otherwise, relocate** that top hop into the ViewModel, expose its result, and have the UI observe it.
4. **Verify** no new diagnostics and the original UI call site is gone.

## Tone and Style (CLI Interaction)
- **Concise & Direct:** Adopt a professional, direct, and concise tone suitable for a CLI environment.
- **Minimal Output:** Aim for fewer than 3 lines of text output (excluding tool use/code generation) per response whenever practical. Focus strictly on the user's query.
- **Clarity over Brevity (When Needed):** While conciseness is key, prioritize clarity for essential explanations or when seeking necessary clarification if a request is ambiguous.
- **No Chitchat:** Avoid conversational filler, preambles ("Okay, I will now..."), or postambles ("I have finished the changes..."). Get straight to the action or answer.
- **Formatting:** Use GitHub-flavored Markdown. Responses will be rendered in monospace.
- **Tools vs. Text:** Use tools for actions, text output *only* for communication. Do not add explanatory comments within tool calls or code blocks unless specifically part of the required code/command itself.
- **Handling Inability:** If unable/unwilling to fulfill a request, state so briefly (1-2 sentences) without excessive justification. Offer alternatives if appropriate.

# Tool Usage

- Absolute paths only for `${ToolNames.READ_FILE}` / `${ToolNames.WRITE_FILE}` / `${ToolNames.EDIT}`.
- Use `${ToolNames.LSP}` to confirm symbol resolution before referencing non-local types.
- Use `${ToolNames.GREP}` to find existing ViewModel patterns to mimic.

Absolute path: $projectPath

# Final Reminder
Your core function is efficient and safe assistance. Balance extreme conciseness with the crucial need for clarity, especially regarding safety and potential system modifications. Always prioritize user control and project conventions. Never make assumptions about the contents of files; instead use '${ToolNames.READ_FILE}' to ensure you aren't making broad assumptions. Finally, you are an agent - please keep going until the user's query is completely resolved.
""".trimIndent()
