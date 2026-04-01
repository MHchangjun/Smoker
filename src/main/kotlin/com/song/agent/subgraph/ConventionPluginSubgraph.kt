package com.song.agent.subgraph

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@SerialName("ConventionPluginsResult")
@LLMDescription("Resolved convention plugin map from build-logic. Each entry describes what frameworks and dependencies a custom plugin provides when applied to a module.")
data class ConventionPluginsResult(
    @property:LLMDescription("Map of plugin ID to its resolved contents. Key is the plugin ID string (e.g. watcha.android.feature)")
    val plugins: Map<String, ResolvedPlugin> = emptyMap()
)

@Serializable
@SerialName("ResolvedPlugin")
@LLMDescription("A single convention plugin resolved from its source implementation")
data class ResolvedPlugin(
    @SerialName("source_file")
    @property:LLMDescription("Relative path from project root to the plugin implementation class file (e.g. build-logic/convention/src/main/kotlin/AndroidFeatureConventionPlugin.kt). Null if source could not be located.")
    val sourceFile: String? = null,
    @property:LLMDescription("List of other plugin IDs this plugin applies via pluginManager.apply(). Includes both custom and third-party plugin IDs (e.g. [watcha.android.library, watcha.android.hilt, org.jetbrains.kotlin.plugin.serialization])")
    val applies: List<String> = emptyList(),
    @property:LLMDescription("Dependencies this plugin adds to modules. Use the resolved library coordinate when possible (e.g. androidx.navigation:navigation-fragment-ktx), or project path for project dependencies (e.g. project(:core:core))")
    val dependencies: List<String> = emptyList(),
    @SerialName("inferred_frameworks")
    @property:LLMDescription("Frameworks this plugin brings in, inferred from its dependencies and applied plugins")
    val inferredFrameworks: InferredFrameworks = InferredFrameworks()
)

@Serializable
@SerialName("InferredFrameworks")
@LLMDescription("Framework usage inferred from a convention plugin's dependencies and applied plugins")
data class InferredFrameworks(
    @property:LLMDescription("UI frameworks: compose, view_xml, or both. Empty list if this plugin does not configure UI.")
    val ui: List<String> = emptyList(),
    @property:LLMDescription("Navigation frameworks: navigation-xml, navigation-compose, or both. Empty list if this plugin does not configure navigation.")
    val navigation: List<String> = emptyList(),
    @property:LLMDescription("Dependency injection framework: hilt, dagger, koin, or null if not configured by this plugin")
    val di: String? = null
)