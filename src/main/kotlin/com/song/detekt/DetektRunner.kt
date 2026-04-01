package com.song.detekt

import java.io.File
import java.util.concurrent.TimeUnit

// -------------------- detekt runner --------------------
data class CmdResult(val exitCode: Int, val output: String)

fun runGradleTask(projectRoot: File, gradleTask: String, timeoutMinutes: Long = 20): CmdResult {
    val os = System.getProperty("os.name").lowercase()
    val isWindows = os.contains("win")

    val gradlewName = if (isWindows) "gradlew.bat" else "gradlew"
    val gradlew = File(projectRoot, gradlewName)
    require(gradlew.exists()) { "gradlew not found at ${gradlew.absolutePath} (run from the target project root?)" }

    val cmd = when {
        isWindows -> listOf("cmd.exe", "/c", gradlewName, gradleTask, "--no-daemon", "--console=plain")
        gradlew.canExecute() -> listOf("./$gradlewName", gradleTask, "--no-daemon", "--console=plain")
        else -> listOf("bash", "./$gradlewName", gradleTask, "--no-daemon", "--console=plain")
    }

    val pb = ProcessBuilder(cmd).directory(projectRoot).redirectErrorStream(true)
    val p = pb.start()

    val out = StringBuilder()
    val t = Thread {
        p.inputStream.bufferedReader().useLines { lines -> lines.forEach { out.appendLine(it) } }
    }
    t.isDaemon = true
    t.start()

    val finished = p.waitFor(timeoutMinutes, TimeUnit.MINUTES)
    if (!finished) {
        p.destroyForcibly()
        t.join(1000)
        return CmdResult(-1, out.toString() + "\n[TIMEOUT] Gradle task '$gradleTask' did not finish in ${timeoutMinutes}m")
    }

    t.join(5000)
    return CmdResult(p.exitValue(), out.toString())
}
