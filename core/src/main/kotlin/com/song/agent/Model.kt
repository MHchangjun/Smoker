package com.song.agent

import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel

object Model {
    val QWEN_3_6_LLAMA = LLModel(
        provider = LLMProvider.OpenAI,
        id = "qwen3.6",
        capabilities = listOf(
            LLMCapability.Temperature,
            LLMCapability.Schema.JSON.Basic,
            LLMCapability.Tools,
            LLMCapability.OpenAIEndpoint.Completions,
            LLMCapability.Thinking,
            LLMCapability.Completion,
            LLMCapability.PromptCaching
        ),
        contextLength = 262_144,
    )

    val GEMMA4 = LLModel(
        provider = LLMProvider.OpenAI,
        id = "gemma4",
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