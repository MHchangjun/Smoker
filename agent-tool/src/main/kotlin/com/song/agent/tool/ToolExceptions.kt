package com.song.agent.tool

/**
 * Thrown when a tool execution fails (invalid arguments, denied operation, process failure, timeout, etc.).
 *
 * Koog will surface this exception as a failed tool call to the LLM.
 */
class ToolExecutionException(
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)
