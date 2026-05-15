package com.song.agent

import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel

object Model {
    fun openAi(id: String): LLModel = LLModel(
        provider = LLMProvider.OpenAI,
        id = id,
        capabilities = listOf(
            LLMCapability.Temperature,
            LLMCapability.Schema.JSON.Basic,
            LLMCapability.Tools,
            LLMCapability.OpenAIEndpoint.Completions,
            LLMCapability.Thinking,
            LLMCapability.Completion,
            LLMCapability.PromptCaching,
        ),
        contextLength = 262_144,
    )
}
