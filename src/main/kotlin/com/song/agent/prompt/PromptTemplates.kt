package com.song.agent.prompt

object PromptTemplates {
    val cli: String by lazy { readResource("/prompts/cli.md") }

    private fun readResource(path: String): String {
        val stream = PromptTemplates::class.java.getResourceAsStream(path)
            ?: error("Missing prompt resource: $path")
        return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }
}
