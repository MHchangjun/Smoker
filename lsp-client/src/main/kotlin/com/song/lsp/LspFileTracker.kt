package com.song.lsp

import java.util.concurrent.ConcurrentHashMap

class LspFileTracker {
    private data class State(val version: Int, val contentHash: Int)

    private val openFiles = ConcurrentHashMap<String, State>()

    fun isOpen(uri: String): Boolean = openFiles.containsKey(uri)

    fun markOpened(uri: String, content: String) {
        openFiles[uri] = State(1, content.hashCode())
    }

    fun hasChanged(uri: String, content: String): Boolean {
        val state = openFiles[uri] ?: return true
        return state.contentHash != content.hashCode()
    }

    fun incrementVersion(uri: String, content: String): Int {
        val newState = openFiles.compute(uri) { _, v ->
            State((v?.version ?: 0) + 1, content.hashCode())
        }!!
        return newState.version
    }

    fun markClosed(uri: String) {
        openFiles.remove(uri)
    }

    fun clear() {
        openFiles.clear()
    }
}
