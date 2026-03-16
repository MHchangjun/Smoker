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
import ai.koog.prompt.executor.llms.all.simpleOllamaAIExecutor
import ai.koog.prompt.params.LLMParams
import com.song.agent.prompt.SYSTEM_PROMPT
import com.song.agent.subagent.getSubAgentDefinitions
import com.song.agent.tool.*

class TestAgent(
    private val projectRoot: String,
    private val shellTool: ShellTool,
    private val grepTool: GrepTool,
    private val globTool: GlobTool,
    private val readFileTool: ReadFileTool,
    private val writeFileTool: WriteFileTool
) {
    suspend fun start(): String {
        println(projectRoot)

        val userPrompt = """
# Module Discovery

You are an Android project structure analyst. Your sole task is to read the Gradle settings file and produce a complete module list.

## Input

- Project root: `$projectRoot`
- Output path: `$projectRoot/smoker/module_list.json`

## Execution

### 1. Find the settings file
Read whichever exists (prefer .kts):
- `$projectRoot/settings.gradle.kts`
- `$projectRoot/settings.gradle`

### 2. Extract module declarations
Parse all `include(...)` statements. Handle these variants:

```groovy
// Groovy
include ':app'
include ':app', ':core', ':feature:home'
include(":app", ":core")

// KTS
include(":app")
include(":app", ":core", ":feature:home")
```

For each module name, resolve its directory path:
- `:app` → `$projectRoot/app`
- `:feature:home` → `$projectRoot/feature/home`

### 3. Check for projectDir overrides
Some projects remap module directories:

```groovy
project(":old-name").projectDir = file("libs/actual-dir")
```

If found, use the overridden path instead of the default.

### 4. Check for composite builds
If `includeBuild("...")` statements exist, record them separately.
Ignore `includeBuild` inside `pluginManagement` block — that is a build-logic module, not an app module.

### 5. Write output
Write `$projectRoot/smoker/module_list.json`.

## Output Schema

```json
{
  "project_root": "$projectRoot",
  "settings_file": "settings.gradle.kts",
  "modules": [
    {
      "name": ":app",
      "path": "$projectRoot/app"
    },
    {
      "name": ":feature:home",
      "path": "$projectRoot/feature/home"
    }
  ],
  "composite_builds": [
    {
      "name": "malt-android",
      "path": "$projectRoot/malt-android"
    }
  ],
  "total_modules": 12
}
```

## Rules

- Use **absolute paths** in all tool calls and in the output JSON.
- Only read the settings file. Do NOT read build.gradle, AndroidManifest.xml, or any source files.
- Do NOT analyze dependencies, frameworks, or navigation.
- Ignore `pluginManagement` and `dependencyResolutionManagement` blocks entirely.
- If `include` arguments use variables or are dynamically generated, add the entry with `"note": "dynamic include, could not resolve"`.
- Output valid JSON only. No comments, no trailing commas.
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

        val ollama = simpleOllamaAIExecutor()

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
