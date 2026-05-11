package com.song.smoker.daemon

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.song.smoker.UnifiedLauncher
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

@Service(Service.Level.PROJECT)
class SmokerHttpListener(private val project: Project) : Disposable {
    private val log = thisLogger()
    private var server: HttpServer? = null

    fun start() {
        if (server != null) return
        val host = System.getenv("SMOKER_HTTP_HOST")?.takeIf { it.isNotBlank() } ?: DEFAULT_HOST
        val port = System.getenv("SMOKER_HTTP_PORT")?.toIntOrNull() ?: DEFAULT_PORT
        val srv = try {
            HttpServer.create(InetSocketAddress(host, port), 0)
        } catch (e: Exception) {
            log.warn("[smoker-http] failed to bind $host:$port — listener disabled", e)
            return
        }
        srv.createContext("/run") { runHandler(it) }
        srv.createContext("/status") { statusHandler(it) }
        srv.executor = null
        srv.start()
        server = srv
        log.info("[smoker-http] listening on $host:$port (project=${project.name})")
    }

    override fun dispose() {
        server?.stop(2)
        server = null
    }

    private fun statusHandler(exchange: HttpExchange) {
        val running = project.service<UnifiedLauncher>().isRunning()
        respond(exchange, 200, """{"running":$running}""", contentType = "application/json")
    }

    private fun runHandler(exchange: HttpExchange) {
        if (exchange.requestMethod != "POST") {
            respond(exchange, 405, "method not allowed: ${exchange.requestMethod}")
            return
        }
        val launcher = project.service<UnifiedLauncher>()
        if (launcher.isRunning()) {
            respond(exchange, 409, "already running")
            return
        }
        val logBuf = StringBuilder()
        val latch = CountDownLatch(1)
        val failureRef = AtomicReference<Throwable?>(null)
        val hooks = UnifiedLauncher.Hooks(
            log = { msg -> synchronized(logBuf) { logBuf.appendLine(msg) } },
            onFinish = { err -> failureRef.set(err); latch.countDown() },
        )
        if (!launcher.start(hooks)) {
            respond(exchange, 409, "already running")
            return
        }
        latch.await()
        val err = failureRef.get()
        val body = buildString {
            appendLine("status: ${if (err == null) "success" else "failure"}")
            err?.let { appendLine("error: ${it.message ?: it.javaClass.simpleName}") }
            appendLine("---")
            synchronized(logBuf) { append(logBuf) }
        }
        respond(exchange, if (err == null) 200 else 500, body)
    }

    private fun respond(
        exchange: HttpExchange,
        code: Int,
        body: String,
        contentType: String = "text/plain; charset=utf-8",
    ) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", contentType)
        exchange.sendResponseHeaders(code, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    companion object {
        private const val DEFAULT_HOST = "127.0.0.1"
        private const val DEFAULT_PORT = 8765
    }
}
