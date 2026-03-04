package com.song.agent.tool

import ai.koog.agents.core.agent.AIAgentTool
import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

data class TaskSubagentDefinition(
    val name: String,
    val description: String,
    val tool: Tool<String, AIAgentTool.AgentToolResult<String>>
)

class TaskTool(
    private val subagents: List<TaskSubagentDefinition>
) : Tool<TaskTool.Args, TaskTool.Result>(
    argsSerializer = Args.serializer(),
    resultSerializer = Result.serializer(),
    name = "task",
    description = buildDescription(subagents).also { println(it) }
) {
    private val subagentByName: Map<String, TaskSubagentDefinition> = subagents.associateBy { it.name }

    init {
        val invalidName = subagents.firstOrNull { it.name.isBlank() }
        if (invalidName != null) {
            throw IllegalArgumentException("Task subagent name must not be blank")
        }
        val invalidDescription = subagents.firstOrNull { it.description.isBlank() }
        if (invalidDescription != null) {
            throw IllegalArgumentException("Task subagent description must not be blank: ${invalidDescription.name}")
        }

        val duplicateNames = subagents.groupBy { it.name }.filterValues { it.size > 1 }.keys
        if (duplicateNames.isNotEmpty()) {
            throw IllegalArgumentException(
                "Duplicate task subagent names: ${duplicateNames.sorted().joinToString(", ")}"
            )
        }
    }

    @Serializable
    data class Args(
        @property:LLMDescription("A short (3-5 word) description of the task")
        val description: String,
        @property:LLMDescription("The task for the agent to perform")
        val prompt: String,
        @property:LLMDescription("The type of specialized agent to use for this task")
        val subagent_type: String
    )

    @Serializable
    enum class Status {
        @SerialName("running")
        RUNNING,

        @SerialName("completed")
        COMPLETED,

        @SerialName("failed")
        FAILED,

        @SerialName("cancelled")
        CANCELLED
    }

    @Serializable
    data class Result(
        val type: String = "task_execution",
        val subagent_name: String,
        val task_description: String,
        val task_prompt: String,
        val status: Status,
        val output: String = "",
        val terminate_reason: String? = null
    )

    override suspend fun execute(args: Args): Result {
        val description = args.description.trim()
        val prompt = args.prompt.trim()
        val subagentType = args.subagent_type.trim()
        val validationError = validateParams(description, prompt, subagentType)
        if (validationError != null) {
            throw ToolExecutionException(validationError)
        }

        val subagent = subagentByName[subagentType]
            ?: return failedResult(
                subagentType = subagentType,
                description = description,
                prompt = prompt,
                terminateReason = "Subagent \"$subagentType\" not found"
            )

        return runCatching {
            // Match qwen task.ts semantics:
            // - description: visibility/tracking metadata
            // - prompt: the only content sent to sub-agent execution context
            val delegatedResult = subagent.tool.execute(prompt)
            if (!delegatedResult.successful) {
                failedResult(
                    subagentType = subagentType,
                    description = description,
                    prompt = prompt,
                    terminateReason = delegatedResult.errorMessage.orEmpty().ifBlank { "Delegated sub-agent failed" },
                )
            } else {
                Result(
                    subagent_name = subagentType,
                    task_description = description,
                    task_prompt = prompt,
                    status = Status.COMPLETED,
                    output = delegatedResult.result.orEmpty(),
                )
            }
        }.getOrElse { error ->
            failedResult(
                subagentType = subagentType,
                description = description,
                prompt = prompt,
                terminateReason = "Failed to run subagent: ${error.message ?: error::class.simpleName.orEmpty()}",
            )
        }
    }

    fun getAvailableSubagentNames(): List<String> = subagents.map { it.name }

    private fun validateParams(
        description: String,
        prompt: String,
        subagentType: String
    ): String? {
        if (description.isBlank()) {
            return "Parameter \"description\" must be a non-empty string."
        }

        if (prompt.isBlank()) {
            return "Parameter \"prompt\" must be a non-empty string."
        }

        if (subagentType.isBlank()) {
            return "Parameter \"subagent_type\" must be a non-empty string."
        }

        if (subagentType !in subagentByName.keys) {
            return "Subagent \"$subagentType\" not found. Available subagents: ${
                getAvailableSubagentNames().joinToString(
                    ", "
                )
            }"
        }

        return null
    }

    private fun failedResult(
        subagentType: String,
        description: String,
        prompt: String,
        terminateReason: String,
    ): Result {
        return Result(
            subagent_name = subagentType,
            task_description = description,
            task_prompt = prompt,
            status = Status.FAILED,
            output = "",
            terminate_reason = terminateReason
        )
    }

    companion object {
        private fun buildDescription(subagents: List<TaskSubagentDefinition>): String {
            val subagentDescriptions = if (subagents.isEmpty()) {
                "No subagents are currently configured. You can create subagents using the /agents command."
            } else {
                subagents.joinToString("\n") { "- **${it.name}**: ${it.description}" }
            }

            return """
Launch a new agent to handle complex, multi-step tasks autonomously. 

Available agent types and the tools they have access to:
$subagentDescriptions

When using the Task tool, you must specify a subagent_type parameter to select which agent type to use.

When NOT to use the Agent tool:
- If you want to read a specific file path, use the Read or Glob tool instead of the Agent tool, to find the match more quickly
- If you are searching for a specific class definition like "class Foo", use the Glob tool instead, to find the match more quickly
- If you are searching for code within a specific file or set of 2-3 files, use the Read tool instead of the Agent tool, to find the match more quickly
- Other tasks that are not related to the agent descriptions above

Usage notes:
1. Launch multiple agents concurrently whenever possible, to maximize performance; to do that, use a single message with multiple tool uses
2. When the agent is done, it will return a single message back to you. The result returned by the agent is not visible to the user. To show the user the result, you should send a text message back to the user with a concise summary of the result.
3. Each agent invocation is stateless. You will not be able to send additional messages to the agent, nor will the agent be able to communicate with you outside of its final report. Therefore, your prompt should contain a highly detailed task description for the agent to perform autonomously and you should specify exactly what information the agent should return back to you in its final and only message to you.
4. The agent's outputs should generally be trusted
5. Clearly tell the agent whether you expect it to write code or just to do research (search, file reads, web fetches, etc.), since it is not aware of the user's intent
6. If the agent description mentions that it should be used proactively, then you should try your best to use it without the user having to ask for it first. Use your judgement.

Example usage:
<example_agent_descriptions>
"code-reviewer": use this agent after you are done writing a signficant piece of code
"greeting-responder": use this agent when to respond to user greetings with a friendly joke
</example_agent_description>

<example>
user: "Please write a function that checks if a number is prime"
assistant: Sure let me write a function that checks if a number is prime
assistant: First let me use the Write tool to write a function that checks if a number is prime
assistant: I'm going to use the Write tool to write the following code:
<code>
function isPrime(n) {
  if (n <= 1) return false
  for (let i = 2; i * i <= n; i++) {
    if (n % i === 0) return false
  }
  return true
}
</code>
<commentary>
Since a signficant piece of code was written and the task was completed, now use the code-reviewer agent to review the code
</commentary>
assistant: Now let me use the code-reviewer agent to review the code
assistant: Uses the Task tool to launch the with the code-reviewer agent 
</example>

<example>
user: "Hello"
<commentary>
Since the user is greeting, use the greeting-responder agent to respond with a friendly joke
</commentary>
assistant: "I'm going to use the Task tool to launch the with the greeting-responder agent"
</example>
""".trimIndent()
        }
    }
}
