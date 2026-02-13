package com.song.agent.prompt

enum class UtilityPrompt(private val resourcePath: String) {
    PROJECT_CONTEXT("/prompts/project_context.md");

    fun read(): String {
        val stream = UtilityPrompt::class.java.getResourceAsStream(resourcePath)
            ?: error("Missing prompt resource: $resourcePath")
        return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }
}
