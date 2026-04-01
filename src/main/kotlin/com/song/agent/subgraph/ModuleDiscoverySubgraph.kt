package com.song.agent.subgraph

import ai.koog.agents.core.dsl.builder.AIAgentSubgraphBuilderBase
import ai.koog.agents.core.dsl.builder.AIAgentSubgraphDelegate
import ai.koog.agents.core.dsl.builder.forwardTo
import ai.koog.agents.core.dsl.extension.*
import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ModuleDiscoveryInput(
    val projectRoot: String,
)

@Serializable
@SerialName("ModuleDiscoveryResult")
@LLMDescription("Complete module structure analysis of a Gradle project, parsed from settings.gradle(.kts). Includes all declared modules, build-logic sources, and composite builds.")
data class ModuleDiscoveryResult(
    @SerialName("project_root")
    @property:LLMDescription("Absolute path of the analyzed project root (e.g. /home/user/MyApp)")
    val projectRoot: String = "",
    @SerialName("settings_file")
    @property:LLMDescription("Name of the settings file used (settings.gradle.kts or settings.gradle)")
    val settingsFile: String = "",
    @property:LLMDescription("All modules declared via include(). Does not contain buildSrc or includeBuild entries.")
    val modules: List<DiscoveredModule> = emptyList(),
    @SerialName("build_logic")
    @property:LLMDescription("Build-logic sources found via includeBuild inside pluginManagement block")
    val buildLogic: List<DiscoveredBuildLogic> = emptyList(),
    @SerialName("composite_builds")
    @property:LLMDescription("Composite builds declared via includeBuild outside the pluginManagement block, typically used for dependency substitution")
    val compositeBuilds: List<DiscoveredCompositeBuild> = emptyList(),
)

@Serializable
@SerialName("DiscoveredModule")
@LLMDescription("A single Gradle module declared via include()")
data class DiscoveredModule(
    @property:LLMDescription("Gradle path of the module (e.g. :feature:home)")
    val name: String = "",
    @property:LLMDescription("Absolute filesystem path of the module directory. Uses the overridden path if projectDir is remapped.")
    val path: String = ""
)

@Serializable
@SerialName("DiscoveredBuildLogic")
@LLMDescription("A build-logic source such as a convention plugin directory")
data class DiscoveredBuildLogic(
    @property:LLMDescription("Name of the build-logic directory (e.g. build-logic)")
    val name: String = "",
    @property:LLMDescription("Absolute filesystem path of the build-logic directory")
    val path: String = "",
    @property:LLMDescription("How this entry was discovered: always pluginManagement")
    val source: String = ""
)

@Serializable
@SerialName("DiscoveredCompositeBuild")
@LLMDescription("A composite build declared via includeBuild outside the pluginManagement block")
data class DiscoveredCompositeBuild(
    @property:LLMDescription("Name of the composite build directory")
    val name: String = "",
    @property:LLMDescription("Absolute filesystem path of the composite build directory")
    val path: String = ""
)

internal fun AIAgentSubgraphBuilderBase<ModuleDiscoveryInput, ModuleDiscoveryResult>.moduleDiscoverySubgraph(): AIAgentSubgraphDelegate<ModuleDiscoveryInput, ModuleDiscoveryResult> {
    return subgraph<ModuleDiscoveryInput, ModuleDiscoveryResult>(
        name = "module_discovery_subgraph"
    ) {
        val buildUserPrompt by node<ModuleDiscoveryInput, String>("build_user_prompt") { input ->
            buildModuleDiscoveryPrompt(input)
        }

        val nodeCallLLM by nodeLLMRequest("call_llm")
        val nodeExecuteTool by nodeExecuteTool("execute_tool")
        val nodeSendToolResult by nodeLLMSendToolResult("send_tool_result")
        val nodeRequestStructured by nodeLLMRequestStructured<ModuleDiscoveryResult>(
            name = "request_structured_output"
        )

        edge(nodeStart forwardTo buildUserPrompt)
        edge(buildUserPrompt forwardTo nodeCallLLM)

        edge(nodeCallLLM forwardTo nodeExecuteTool onToolCall { true })
        edge(
            nodeCallLLM forwardTo nodeRequestStructured
                onAssistantMessage { true }
                transformed {
                    "Based on all previous tool results, return the final module discovery in the required schema."
                }
        )

        edge(nodeExecuteTool forwardTo nodeSendToolResult)
        edge(nodeSendToolResult forwardTo nodeExecuteTool onToolCall { true })
        edge(
            nodeSendToolResult forwardTo nodeRequestStructured
                onAssistantMessage { true }
                transformed {
                    "Based on all previous tool results, return the final module discovery in the required schema."
                }
        )

        edge(
            nodeRequestStructured forwardTo nodeFinish
                transformed { result ->
                    result.getOrElse { throwable ->
                        throw IllegalStateException("Failed to create structured module discovery output", throwable)
                    }.data
                }
        )
    }
}

private fun buildModuleDiscoveryPrompt(input: ModuleDiscoveryInput): String = """
# Module Discovery

You are an Android project structure analyst. Your sole task is to read the Gradle settings file and produce a complete module list for serialization.

## Input

- Project root: `${input.projectRoot}`

## Execution

### 1. Find the settings file
Read whichever exists (prefer .kts):
- `${input.projectRoot}/settings.gradle.kts`
- `${input.projectRoot}/settings.gradle`

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
- `:app` -> `${input.projectRoot}/app`
- `:feature:home` -> `${input.projectRoot}/feature/home`

### 3. Check for projectDir overrides
Some projects remap module directories:

```groovy
project(":old-name").projectDir = file("libs/actual-dir")
```

If found, use the overridden path instead of the default.

### 4. Separate build-logic from composite builds
`includeBuild(...)` can appear in two different contexts:

**Inside `pluginManagement` block → `build_logic`:**
```kotlin
pluginManagement {
    includeBuild("build-logic")  // ← convention plugin source
}
```

**Outside `pluginManagement` block → `composite_builds`:**
```kotlin
includeBuild("malt-android") {   // ← app dependency
    dependencySubstitution { ... }
}
```

Record them in separate fields.

## Rules

- Use **absolute paths** in all tool calls and in the output.
- Only read the settings file. Do NOT read build.gradle, AndroidManifest.xml, or any source files.
- Do NOT analyze dependencies, frameworks, or navigation.
- Ignore `dependencyResolutionManagement` block entirely.
- If `include` arguments use variables or are dynamically generated, add the entry with `"note": "dynamic include, could not resolve"`.
    """.trimIndent()
