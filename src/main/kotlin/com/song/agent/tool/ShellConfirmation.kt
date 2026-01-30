package com.song.agent.tool

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Confirmation result for executing a potentially unsafe shell command.
 */
sealed interface ShellCommandConfirmation {
    data object Approved : ShellCommandConfirmation
    data class Denied(val reason: String) : ShellCommandConfirmation
}

/**
 * Hook for "ASK" permission semantics (like the original tool).
 *
 * You can inject your own implementation (GUI dialog, web UI, etc).
 */
fun interface ShellCommandConfirmationHandler {
    suspend fun confirm(command: String, timeoutSeconds: Long): ShellCommandConfirmation
}

/**
 * Simple console-based confirmation handler.
 * Prints the command and waits for user input on stdin.
 */
class PrintShellCommandConfirmationHandler : ShellCommandConfirmationHandler {
    override suspend fun confirm(command: String, timeoutSeconds: Long): ShellCommandConfirmation =
        withContext(Dispatchers.IO) {
            println("The agent requested to run a shell command (timeout=${timeoutSeconds}s):")
            println(command)
            print("Approve? [y/N]: ")
            val resp = readLine()?.trim()?.lowercase()
            return@withContext if (resp == "y" || resp == "yes") {
                ShellCommandConfirmation.Approved
            } else {
                ShellCommandConfirmation.Denied(resp ?: "no input")
            }
        }
}

/**
 * Non-interactive handler: always approves.
 * Useful for CI / fully sandboxed environments.
 */
object AlwaysApproveConfirmationHandler : ShellCommandConfirmationHandler {
    override suspend fun confirm(command: String, timeoutSeconds: Long): ShellCommandConfirmation =
        ShellCommandConfirmation.Approved
}

/**
 * Factory that mimics the JetBrains blog pattern: BRAVE_MODE=true => auto-approve.
 */
internal fun defaultConfirmationHandlerFromEnv(): ShellCommandConfirmationHandler {
    return if (System.getenv("BRAVE_MODE")?.lowercase() == "true") {
        AlwaysApproveConfirmationHandler
    } else {
        PrintShellCommandConfirmationHandler()
    }
}
