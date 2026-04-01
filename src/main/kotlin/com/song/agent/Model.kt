package com.song.agent

import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel

object Model {
    val QWEN = LLModel(
        provider = LLMProvider.Ollama,
        id = "qwen3-coder:30b",
        capabilities = listOf(
            LLMCapability.Temperature,
            LLMCapability.Schema.JSON.Basic,
            LLMCapability.Tools
        ),
        contextLength = 262_144,
    )


    val QWEN_LLAMA = LLModel(
        provider = LLMProvider.OpenAI,
        id = "qwen3-next",
        capabilities = listOf(
            LLMCapability.Temperature,
            LLMCapability.Schema.JSON.Basic,
            LLMCapability.Tools,
            LLMCapability.OpenAIEndpoint.Completions,
            LLMCapability.Completion
        ),
        contextLength = 262_144,
    )

    val QWEN_3_5 = LLModel(
        provider = LLMProvider.Ollama,
        id = "qwen3.5:35b",
        capabilities = listOf(
            LLMCapability.Temperature,
            LLMCapability.Schema.JSON.Basic,
            LLMCapability.Tools
        ),
        contextLength = 262_144,
    )

    val QWEN_3_5_LLAMA = LLModel(
        provider = LLMProvider.OpenAI,
        id = "qwen3.5",
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