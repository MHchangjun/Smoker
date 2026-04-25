package com.song.lsp

import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

class JsonRpcTransport(
    input: InputStream,
    private val output: OutputStream
) {
    private val reader = BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8))
    private val requestIdCounter = AtomicInteger(0)
    private val pendingRequests = ConcurrentHashMap<Int, CompletableDeferred<JsonElement?>>()
    private val diagnosticsCache = ConcurrentHashMap<String, List<Diagnostic>>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val json = Json { ignoreUnknownKeys = true }

    fun startReading() {
        scope.launch {
            try {
                while (isActive) {
                    val message = readMessage() ?: break
                    handleMessage(message)
                }
            } catch (_: Exception) {
                // Stream closed or read error — complete all pending requests
                pendingRequests.values.forEach { it.completeExceptionally(LspException("LSP connection closed")) }
                pendingRequests.clear()
            }
        }
    }

    suspend fun request(
        method: String,
        params: JsonElement,
        timeout: kotlin.time.Duration = 60.seconds
    ): JsonElement? {
        val id = requestIdCounter.incrementAndGet()
        val request = JsonRpcRequest(id = id, method = method, params = params)
        val deferred = CompletableDeferred<JsonElement?>()
        pendingRequests[id] = deferred

        sendMessage(json.encodeToString(JsonRpcRequest.serializer(), request))

        return try {
            withTimeout(timeout) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            pendingRequests.remove(id)
            throw LspException("LSP request '$method' timed out after ${timeout.inWholeSeconds}s")
        }
    }

    fun notify(method: String, params: JsonElement) {
        val notification = JsonRpcNotification(method = method, params = params)
        sendMessage(json.encodeToString(JsonRpcNotification.serializer(), notification))
    }

    fun close() {
        scope.cancel()
        pendingRequests.values.forEach { it.completeExceptionally(LspException("Transport closed")) }
        pendingRequests.clear()
        try {
            output.close()
        } catch (_: Exception) {
        }
    }

    private fun readMessage(): String? {
        // Read headers
        var contentLength = -1
        while (true) {
            val line = reader.readLine() ?: return null
            if (line.isBlank()) break
            if (line.startsWith("Content-Length:", ignoreCase = true)) {
                contentLength = line.substringAfter(":").trim().toInt()
            }
        }
        if (contentLength < 0) return null

        // Read body
        val buffer = CharArray(contentLength)
        var read = 0
        while (read < contentLength) {
            val n = reader.read(buffer, read, contentLength - read)
            if (n < 0) return null
            read += n
        }
        return String(buffer)
    }

    fun getCachedDiagnostics(uri: String): List<Diagnostic> =
        diagnosticsCache[uri] ?: emptyList()

    fun clearDiagnosticsCache() {
        diagnosticsCache.clear()
    }

    private fun handleMessage(raw: String) {
        val obj = json.parseToJsonElement(raw).jsonObject

        // Handle server notifications (no id)
        val id = obj["id"]?.jsonPrimitive?.int
        if (id == null) {
            val method = obj["method"]?.jsonPrimitive?.contentOrNull
            if (method == "textDocument/publishDiagnostics") {
                val params = obj["params"]
                if (params != null) {
                    try {
                        val pub = json.decodeFromJsonElement(PublishDiagnosticsParams.serializer(), params)
                        diagnosticsCache[pub.uri] = pub.diagnostics
                    } catch (_: Exception) { }
                }
            }
            return
        }

        val deferred = pendingRequests.remove(id) ?: return

        val error = obj["error"]
        if (error != null) {
            val rpcError = json.decodeFromJsonElement(JsonRpcError.serializer(), error)
            deferred.completeExceptionally(LspException("LSP error ${rpcError.code}: ${rpcError.message}"))
            return
        }

        deferred.complete(obj["result"])
    }

    @Synchronized
    private fun sendMessage(body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        val header = "Content-Length: ${bytes.size}\r\n\r\n"
        output.write(header.toByteArray(StandardCharsets.UTF_8))
        output.write(bytes)
        output.flush()
    }
}

class LspException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
