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
    description = TOOL_DESCRIPTION
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

        private val TOOL_DESCRIPTION = """
Use the `todo` tool to manage a simple task list. This tool helps you track tasks and their progress.

## How it works

- **Reading:** Use `action: "read"` to view the current todo list
- **Writing:** Use `action: "write"` with the complete `todos` list to update. You must provide the ENTIRE list - this replaces everything.

## Todo Structure
Each todo item has:
- `id`: A unique string identifier (e.g., "1", "2", "task-a")
- `content`: The task description
- `status`: One of: "pending", "in_progress", "completed", "cancelled"
- `priority`: One of: "high", "medium", "low"

## When to Use This Tool

**Use proactively for:**
- Complex multi-step tasks (3+ distinct steps)
- Non-trivial tasks requiring careful planning
- Multiple tasks provided by the user (numbered or comma-separated)
- Tracking progress on ongoing work
- After receiving new instructions - immediately capture requirements
- When starting work - mark task as in_progress BEFORE beginning
- After completing work - mark as completed and add any follow-up tasks discovered

**Skip this tool for:**
- Single, straightforward tasks
- Trivial operations (< 3 simple steps)
- Purely conversational or informational requests
- Tasks that provide no organizational benefit

## Task Management Best Practices

1. **Status Management:**
   - Only ONE task should be `in_progress` at a time
   - Mark tasks `in_progress` BEFORE starting work on them
   - Mark tasks `completed` IMMEDIATELY after finishing
   - Keep tasks `in_progress` if blocked or encountering errors

2. **Task Completion Rules:**
   - ONLY mark as `completed` when FULLY accomplished
   - Never mark complete if tests are failing, implementation is partial, or errors are unresolved
   - When blocked, create a new task describing what needs resolution

3. **Task Organization:**
   - Create specific, actionable items
   - Break complex tasks into manageable steps
   - Use clear, descriptive task names
   - Remove irrelevant tasks entirely (don't just mark cancelled)

## Examples

**Example 1: Reading todos**
```json
{
  "action": "read"
}
```

**Example 2: Initial task creation (user requests multiple features)**
```json
{
  "action": "write",
  "todos": [
    {
      "id": "1",
      "content": "Add dark mode toggle to settings",
      "status": "pending",
      "priority": "high"
    },
    {
      "id": "2",
      "content": "Implement theme context/state management",
      "status": "pending",
      "priority": "high"
    },
    {
      "id": "3",
      "content": "Update components for theme switching",
      "status": "pending",
      "priority": "medium"
    },
    {
      "id": "4",
      "content": "Run tests and verify build",
      "status": "pending",
      "priority": "medium"
    }
  ]
}
```

**Example 3: Starting work (marking one task in_progress)**
```json
{
  "action": "write",
  "todos": [
    {
      "id": "1",
      "content": "Add dark mode toggle to settings",
      "status": "in_progress",
      "priority": "high"
    },
    {
      "id": "2",
      "content": "Implement theme context/state management",
      "status": "pending",
      "priority": "high"
    },
    {
      "id": "3",
      "content": "Update components for theme switching",
      "status": "pending",
      "priority": "medium"
    },
    {
      "id": "4",
      "content": "Run tests and verify build",
      "status": "pending",
      "priority": "medium"
    }
  ]
}
```

**Example 4: Completing task and adding discovered subtask**
```json
{
  "action": "write",
  "todos": [
    {
      "id": "1",
      "content": "Add dark mode toggle to settings",
      "status": "completed",
      "priority": "high"
    },
    {
      "id": "2",
      "content": "Implement theme context/state management",
      "status": "in_progress",
      "priority": "high"
    },
    {
      "id": "3",
      "content": "Update components for theme switching",
      "status": "pending",
      "priority": "medium"
    },
    {
      "id": "4",
      "content": "Fix TypeScript errors in theme types",
      "status": "pending",
      "priority": "high"
    },
    {
      "id": "5",
      "content": "Run tests and verify build",
      "status": "pending",
      "priority": "medium"
    }
  ]
}
```

**Example 5: Handling blockers (keeping task in_progress)**
```json
{
  "action": "write",
  "todos": [
    {
      "id": "1",
      "content": "Deploy to production",
      "status": "in_progress",
      "priority": "high"
    },
    {
      "id": "2",
      "content": "BLOCKER: Fix failing deployment pipeline",
      "status": "pending",
      "priority": "high"
    },
    {
      "id": "3",
      "content": "Update documentation",
      "status": "pending",
      "priority": "low"
    }
  ]
}
```

## Common Scenarios

**Multi-file refactoring:** Create todos for each file that needs updating
**Performance optimization:** List specific bottlenecks as individual tasks
**Bug fixing:** Track reproduction, diagnosis, fix, and verification as separate tasks
**Feature implementation:** Break down into UI, logic, tests, and documentation tasks

Remember: When writing, you must include ALL todos you want to keep. Any todo not in the list will be removed. Be proactive with task management to demonstrate thoroughness and ensure all requirements are completed successfully.
""".trimIndent()
    }
}
