package com.song.agent.tool

import java.io.File

internal object ExecutableFinder {
    /**
     * Returns the absolute path of [exe] if found on PATH and executable, else null.
     */
    fun which(exe: String): String? {
        val pathEnv = System.getenv("PATH") ?: return null
        val pathSeparator = File.pathSeparatorChar
        val candidates = mutableListOf<String>()

        val isWin = System.getProperty("os.name").lowercase().contains("win")
        if (isWin) {
            // Windows can resolve via PATHEXT
            val pathext = (System.getenv("PATHEXT") ?: ".EXE;.BAT;.CMD").split(";")
            for (ext in pathext) {
                candidates += if (exe.lowercase().endsWith(ext.lowercase())) exe else exe + ext
            }
        } else {
            candidates += exe
        }

        for (dir in pathEnv.split(pathSeparator)) {
            if (dir.isBlank()) continue
            val base = File(dir)
            for (candidate in candidates) {
                val f = File(base, candidate)
                if (f.isFile && f.canExecute()) return f.absolutePath
            }
        }
        return null
    }

    fun exists(exe: String): Boolean = which(exe) != null
}
