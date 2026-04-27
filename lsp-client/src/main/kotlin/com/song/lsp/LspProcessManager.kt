package com.song.lsp

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.nio.file.Path
import java.util.logging.Logger

class LspProcessManager(
    private val serverCommand: String = "kotlin-lsp",
    private val projectRoot: Path,
    private val port: Int = 0
) {
    private val log = Logger.getLogger(LspProcessManager::class.java.simpleName)
    private var process: Process? = null
    private var socket: Socket? = null

    val stderrLogFile: File = File(projectRoot.toFile(), ".gradle/lsp-stderr.log")
    // kotlin-lsp's IntelliJ-Platform logger writes idea.log under <system-path>/log/.
    // Pinning system-path lets us tail that log to see workspace import attempts.
    val systemPath: File = File(projectRoot.toFile(), ".gradle/kotlin-lsp")

    fun start(): Pair<InputStream, OutputStream> {
        val actualPort = if (port == 0) findAvailablePort() else port
        stderrLogFile.parentFile?.mkdirs()
        systemPath.mkdirs()

        val builder = ProcessBuilder(
            serverCommand,
            "--socket", "127.0.0.1:$actualPort",
            "--log-level", "DEBUG",
            "--system-path", systemPath.absolutePath
        )
            .directory(projectRoot.toFile())
            .redirectErrorStream(false)
            .redirectError(ProcessBuilder.Redirect.appendTo(stderrLogFile))

        val env = builder.environment()
        inheritEnvIfPresent(env, "JAVA_HOME")
        inheritEnvIfPresent(env, "ANDROID_HOME")
        inheritEnvIfPresent(env, "ANDROID_SDK_ROOT")
        inheritEnvIfPresent(env, "GRADLE_USER_HOME")
        inheritEnvIfPresent(env, "PATH")

        log.info("Starting LSP server (socket mode, port=$actualPort) in $projectRoot")

        val proc = builder.start()
        process = proc

        val sock = waitForSocket(actualPort, proc)
        socket = sock

        log.info("Connected to LSP server on port $actualPort")
        return sock.getInputStream() to sock.getOutputStream()
    }

    fun stop() {
        try {
            socket?.close()
        } catch (_: Exception) {
        } finally {
            socket = null
        }
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

    private fun waitForSocket(port: Int, proc: Process, maxWaitMs: Long = 30_000): Socket {
        val start = System.currentTimeMillis()
        val retryIntervalMs = 200L

        while (System.currentTimeMillis() - start < maxWaitMs) {
            if (!proc.isAlive) {
                throw LspException("LSP server process exited before accepting connections (exit=${proc.exitValue()}). Check ${stderrLogFile.absolutePath}")
            }
            try {
                return Socket("127.0.0.1", port)
            } catch (_: java.net.ConnectException) {
                Thread.sleep(retryIntervalMs)
            }
        }
        proc.destroyForcibly()
        throw LspException("LSP server did not start listening on port $port within ${maxWaitMs}ms. Check ${stderrLogFile.absolutePath}")
    }

    private fun findAvailablePort(): Int {
        java.net.ServerSocket(0).use { return it.localPort }
    }

    private fun inheritEnvIfPresent(env: MutableMap<String, String>, key: String) {
        val value = System.getenv(key)
        if (value != null) {
            env[key] = value
        }
    }
}
