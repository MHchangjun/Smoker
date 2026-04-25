package com.song.lsp

import java.util.concurrent.ConcurrentHashMap

class LspFileTracker {
    private val openFiles = ConcurrentHashMap<String, Int>() // uri -> version

    fun isOpen(uri: String): Boolean = openFiles.containsKey(uri)

    fun markOpened(uri: String) {
        openFiles[uri] = 1
    }

    fun incrementVersion(uri: String): Int {
        return openFiles.compute(uri) { _, v -> (v ?: 0) + 1 }!!
    }

    fun markClosed(uri: String) {
        openFiles.remove(uri)
    }

    fun clear() {
        openFiles.clear()
    }
}
