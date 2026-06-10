@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.song.agent

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.params.LLMParams
import kotlin.time.TimeSource

internal sealed interface NextSpeakerOutcome {
    data class Finish(val content: String) : NextSpeakerOutcome
    data object Continue : NextSpeakerOutcome
}

internal const val PLEASE_CONTINUE_MESSAGE = "Please continue."

private const val TAG = "NextSpeakerCheck"

private const val SIDE_SYSTEM =
    "You are an arbiter that decides whether a coding agent should continue working or stop and wait for the user."

private const val SIDE_CHECK_PROMPT =
    """The text above is the message a coding agent just produced at the end of its turn.
Based *strictly* on that message, decide who should logically speak next: the 'user' or the 'model' (the agent).

Decision Rules (apply in order):
1. MODEL CONTINUES: If the message states an immediate next action the agent intends to take ("Next, I will...", "Now I'll process...", "Moving on to...", "Let me also..."), OR if the message seems clearly incomplete (cut off mid-thought without a natural conclusion), then the **model** should speak next.
2. QUESTION TO USER: If the message ends with a direct question specifically addressed to the user, then the **user** should speak next.
3. WAITING FOR USER: If the message completed a thought or task without indicating further work, it implies a pause expecting user input. The **user** should speak next.

Reply with exactly one lowercase word: either `model` or `user`. No punctuation, no explanation. /no_think"""

private const val LAST_MESSAGE_MAX_CHARS = 8000

internal suspend fun decideNextSpeaker(
    assistantMessages: List<Message.Assistant>,
): NextSpeakerOutcome {
    val joinedContent = assistantMessages.joinToString("\n") { it.content }
    log("enter | assistantMessages=${assistantMessages.size} | last=\"${previewOf(joinedContent)}\"")

    if (assistantMessages.all { it.content.isBlank() }) {
        log("shortcut=empty-assistant → Continue")
        return NextSpeakerOutcome.Continue
    }

    val judge = SideJudge.getOrNull()
    if (judge == null) {
        log("shortcut=no-side-llm-configured → Finish (skip check)")
        return NextSpeakerOutcome.Finish(joinedContent)
    }

    val mark = TimeSource.Monotonic.markNow()
    val verdict = judge.judge(joinedContent)
    val elapsedMs = mark.elapsedNow().inWholeMilliseconds

    return if (verdict == Speaker.MODEL) {
        log("verdict=model | elapsed=${elapsedMs}ms → Continue (\"$PLEASE_CONTINUE_MESSAGE\")")
        NextSpeakerOutcome.Continue
    } else {
        log("verdict=user | elapsed=${elapsedMs}ms → Finish")
        NextSpeakerOutcome.Finish(joinedContent)
    }
}

private enum class Speaker { USER, MODEL }

private class SideJudge(
    val endpoint: String,
    val modelId: String,
) {
    private val executor = MultiLLMPromptExecutor(
        OpenAILLMClient("", OpenAIClientSettings(endpoint))
    )
    private val model: LLModel = Model.openAi(modelId)

    suspend fun judge(lastAssistantText: String): Speaker {
        val truncated = lastAssistantText.take(LAST_MESSAGE_MAX_CHARS)
        val sidePrompt = prompt("next-speaker", LLMParams(temperature = 0.0)) {
            system(SIDE_SYSTEM)
            user("---\n$truncated\n---\n\n$SIDE_CHECK_PROMPT")
        }
        return try {
            log("side query → $endpoint ($modelId)")
            val responses = executor.execute(sidePrompt, model)
            val raw = (responses.firstOrNull { it is Message.Assistant } as? Message.Assistant)?.content
            log("side query ← raw=\"${previewOf(raw.orEmpty())}\"")
            val speaker = parseSpeaker(raw)
            log("parsed → $speaker")
            speaker
        } catch (e: Exception) {
            log("side query FAILED (${e::class.simpleName}: ${e.message}) → defaulting to USER (stop)")
            Speaker.USER
        }
    }

    companion object {
        @Volatile
        private var cached: SideJudge? = null

        @Synchronized
        fun getOrNull(): SideJudge? {
            val settings = SmokerLlmSettings.getInstance()
            val endpoint = settings.nextSpeakerEndpoint
            val modelId = settings.nextSpeakerModelId
            if (endpoint.isBlank() || modelId.isBlank()) return null

            val current = cached
            if (current != null && current.endpoint == endpoint && current.modelId == modelId) {
                return current
            }
            val fresh = SideJudge(endpoint, modelId)
            cached = fresh
            return fresh
        }
    }
}

private val MODEL_TOKEN = Regex("(?<![A-Za-z])model(?![A-Za-z])", RegexOption.IGNORE_CASE)
private val USER_TOKEN = Regex("(?<![A-Za-z])user(?![A-Za-z])", RegexOption.IGNORE_CASE)

private fun parseSpeaker(text: String?): Speaker {
    val body = text.orEmpty()
    val hasModel = MODEL_TOKEN.containsMatchIn(body)
    val hasUser = USER_TOKEN.containsMatchIn(body)
    return when {
        hasModel && !hasUser -> Speaker.MODEL
        hasUser && !hasModel -> Speaker.USER
        else -> {
            val lastModel = MODEL_TOKEN.findAll(body).lastOrNull()?.range?.first ?: -1
            val lastUser = USER_TOKEN.findAll(body).lastOrNull()?.range?.first ?: -1
            if (lastModel > lastUser) Speaker.MODEL else Speaker.USER
        }
    }
}

private fun previewOf(text: String, max: Int = 160): String {
    val collapsed = text.replace(Regex("\\s+"), " ").trim()
    return if (collapsed.length <= max) collapsed else collapsed.take(max) + "…"
}

private fun log(message: String) {
    println("[$TAG] $message")
}
