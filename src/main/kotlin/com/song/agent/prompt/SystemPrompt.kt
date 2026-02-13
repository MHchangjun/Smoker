package com.song.agent.prompt

import com.song.agent.tool.ToolPrompts

object SystemPrompt {
    val text: String by lazy { build() }

    private fun build(): String {
        val base = PromptTemplates.cli
        val toolDocs = listOf(
            ToolPrompts.search_replace,
            ToolPrompts.bash,
            ToolPrompts.grep,
            ToolPrompts.read_file,
            ToolPrompts.todo,
            ToolPrompts.write_file
        )

        return buildString {
            append(base)
            for (doc in toolDocs) {
                append("\n\n---\n")
                append(doc)
            }

            val projectContext = ProjectContextProvider(ProjectContextConfig()).getFullContext()
            if (projectContext.isNotBlank()) {
                append("\n\n")
                append(projectContext)
            }
        }
    }
}
