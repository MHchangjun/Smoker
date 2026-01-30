package com.song.agent.tool

/**
 * Loads the original tool "description prompts" from resources without modifying them.
 *
 * Put the markdown files under:
 *   src/main/resources/
 *     - bash.md
 *     - grep.md
 *     - read_file.md
 */
object ToolPrompts {
    val bash: String by lazy { readResource("/bash.md") }
    val grep: String by lazy { readResource("/grep.md") }
    val read_file: String by lazy { readResource("/read_file.md") }

    private fun readResource(path: String): String {
        val stream = ToolPrompts::class.java.getResourceAsStream(path)
            ?: error("Missing tool prompt resource: $path")
        return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }
}
