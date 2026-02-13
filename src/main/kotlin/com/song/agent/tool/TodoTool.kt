package com.song.agent.tool

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

class TodoTool(
    private val maxTodos: Int = DEFAULT_MAX_TODOS
) : Tool<TodoTool.Args, TodoTool.Result>(
    argsSerializer = Args.serializer(),
    resultSerializer = Result.serializer(),
    name = "todo",
    description = "Manage todos. Use action='read' to view, action='write' with complete list to update."
) {

    @Serializable
    enum class TodoStatus {
        @SerialName("pending")
        PENDING,
        @SerialName("in_progress")
        IN_PROGRESS,
        @SerialName("completed")
        COMPLETED,
        @SerialName("cancelled")
        CANCELLED
    }

    @Serializable
    enum class TodoPriority {
        @SerialName("low")
        LOW,
        @SerialName("medium")
        MEDIUM,
        @SerialName("high")
        HIGH
    }

    @Serializable
    data class TodoItem(
        val id: String,
        val content: String,
        val status: TodoStatus = TodoStatus.PENDING,
        val priority: TodoPriority = TodoPriority.MEDIUM
    )

    @Serializable
    data class Args(
        @property:LLMDescription("Either 'read' or 'write'.")
        val action: String,
        @property:LLMDescription("Complete list of todos when writing.")
        val todos: List<TodoItem>? = null
    )

    @Serializable
    data class Result(
        val message: String,
        val todos: List<TodoItem>,
        @SerialName("total_count")
        val totalCount: Int
    )

    private val storedTodos = mutableListOf<TodoItem>()

    override suspend fun execute(args: Args): Result {
        return when (args.action) {
            "read" -> readTodos()
            "write" -> writeTodos(args.todos.orEmpty())
            else -> throw IllegalArgumentException("Invalid action '${args.action}'. Use 'read' or 'write'.")
        }
    }

    private fun readTodos(): Result {
        return Result(
            message = "Retrieved ${storedTodos.size} todos",
            todos = storedTodos.toList(),
            totalCount = storedTodos.size
        )
    }

    private fun writeTodos(todos: List<TodoItem>): Result {
        if (todos.size > maxTodos) {
            throw IllegalArgumentException("Cannot store more than $maxTodos todos")
        }

        val ids = todos.map { it.id }
        if (ids.size != ids.toSet().size) {
            throw IllegalArgumentException("Todo IDs must be unique")
        }

        storedTodos.clear()
        storedTodos.addAll(todos)

        return Result(
            message = "Updated ${storedTodos.size} todos",
            todos = storedTodos.toList(),
            totalCount = storedTodos.size
        )
    }

    companion object {
        private const val DEFAULT_MAX_TODOS = 100
    }
}
