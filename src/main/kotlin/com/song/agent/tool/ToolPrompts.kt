package com.song.agent.tool

/**
 * Loads the original tool "description prompts" from resources without modifying them.
 *
 * Put the markdown files under:
 *   src/main/resources/
 *     prompts/cli.md
 *     tools/builtins/prompts/
 *       - bash.md
 *       - grep.md
 *       - read_file.md
 *       - search_replace.md
 *       - todo.md
 *       - write_file.md
 */
object ToolPrompts {
    val bash: String by lazy { readResource("/tools/builtins/prompts/bash.md") }
    val grep: String by lazy { readResource("/tools/builtins/prompts/grep.md") }
    val read_file: String by lazy { readResource("/tools/builtins/prompts/read_file.md") }
    val search_replace: String by lazy { readResource("/tools/builtins/prompts/search_replace.md") }
    val todo: String by lazy { readResource("/tools/builtins/prompts/todo.md") }
    val write_file: String by lazy { readResource("/tools/builtins/prompts/write_file.md") }

    private fun readResource(path: String): String {
        val stream = ToolPrompts::class.java.getResourceAsStream(path)
            ?: error("Missing tool prompt resource: $path")
        return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }
}
