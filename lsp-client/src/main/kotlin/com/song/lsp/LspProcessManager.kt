package com.song.lsp

import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Path

class LspProcessManager(
    private val serverCommand: String = "kotlin-language-server",
    private val projectRoot: Path
) {
    private var process: Process? = null

    fun start(): Pair<InputStream, OutputStream> {
        val builder = ProcessBuilder(serverCommand)
            .directory(projectRoot.toFile())
            .redirectError(ProcessBuilder.Redirect.DISCARD)

        val proc = builder.start()
        process = proc

        // Returns (server stdout for reading, server stdin for writing)
        return proc.inputStream to proc.outputStream
    }

    fun stop() {
        val proc = process ?: return
        try {
            proc.destroyForcibly()
            proc.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
        } catch (_: Exception) {
        } finally {
            process = null
        }
    }

    fun isAlive(): Boolean = process?.isAlive == true
}
