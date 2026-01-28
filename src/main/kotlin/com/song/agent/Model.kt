package com.song.agent

import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel

object Model {
    val DEVSTRAL = LLModel(
        provider = LLMProvider.OpenAI,
        id = "unsloth/Devstral-Small-2-24B-Instruct-2512-GGUF:Q8_0",
        capabilities = listOf(
            LLMCapability.Temperature,
            LLMCapability.Schema.JSON.Basic,
            LLMCapability.Tools,
            LLMCapability.OpenAIEndpoint.Completions,
            LLMCapability.Completion
        ),
        contextLength = 262_144,
    )
}