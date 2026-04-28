package com.song.poc

import ai.koog.agents.core.agent.AIAgentService
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ApplicationStarter
import kotlinx.coroutines.runBlocking

class SmokerPocStarter : ApplicationStarter {

    override fun main(args: List<String>) {
        println("=== Smoker PoC start === args=$args")

        runCatching { AIAgentService::class.qualifiedName }
            .onSuccess { println("koog AIAgentService class reachable: $it") }
            .onFailure { println("koog AIAgentService class load FAILED: ${it.message}") }

        runCatching { runBlocking { "coroutines-ok" } }
            .onSuccess { println("kotlinx-coroutines runBlocking OK: $it") }
            .onFailure { println("kotlinx-coroutines runBlocking FAILED: ${it.message}") }

        println("=== Smoker PoC done ===")
        ApplicationManager.getApplication().exit(true, true, false)
    }
}
