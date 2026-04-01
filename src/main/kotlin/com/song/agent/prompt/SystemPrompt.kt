package com.song.agent.prompt

object SystemPrompt {
    val text: String by lazy { build() }

    private fun build(): String {
        val base = PromptTemplates.cli

        return buildString {
            append(base)

//            val projectContext = ProjectContextProvider(ProjectContextConfig()).getFullContext()
//            if (projectContext.isNotBlank()) {
//                append("\n\n")
//                append(projectContext)
//            }
        }
    }
}
