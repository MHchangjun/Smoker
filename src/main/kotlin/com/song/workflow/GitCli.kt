package com.song.workflow

import java.io.File
import java.security.MessageDigest

internal data class GitCommandResult(val exitCode: Int, val output: String)

internal class GitCli {
    fun run(projectRoot: File, vararg args: String): GitCommandResult {
        return try {
            val cmd = mutableListOf("git")
            cmd.addAll(args)
            val proc = ProcessBuilder(cmd)
                .directory(projectRoot)
                .redirectErrorStream(true)
                .start()
            val out = proc.inputStream.bufferedReader().use { it.readText() }
            val exit = proc.waitFor()
            GitCommandResult(exit, out)
        } catch (e: Exception) {
            GitCommandResult(-1, "Failed to execute git: ${e.message}")
        }
    }

    fun captureDirtyFingerprints(projectRoot: File): Map<String, String> {
        val paths = linkedSetOf<String>()
        paths += collectPaths(run(projectRoot, "diff", "--name-only").output)
        paths += collectPaths(run(projectRoot, "diff", "--name-only", "--cached").output)
        paths += collectPaths(run(projectRoot, "ls-files", "--others", "--exclude-standard").output)
        return paths.associateWith { fingerprint(projectRoot, it) }
    }

    private fun collectPaths(output: String): Set<String> {
        return output
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
    }

    private fun fingerprint(projectRoot: File, relativePath: String): String {
        val file = File(projectRoot, relativePath)
        if (!file.exists()) return "__DELETED__"
        if (!file.isFile) return "__NON_FILE__"

        val bytes = file.readBytes()
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val hex = StringBuilder(digest.size * 2)
        digest.forEach { byte -> hex.append("%02x".format(byte)) }
        return hex.toString()
    }
}
