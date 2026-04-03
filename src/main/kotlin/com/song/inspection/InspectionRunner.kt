package com.song.inspection

import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

internal class JetBrainsInspectRunner {
    fun run(options: InspectionRunOptions): String {
        options.outputDir.mkdirs()
        val inspectBin = resolveInspectBin(options.inspectBin)
        val runtime = prepareRuntime(options, inspectBin)

        val command = listOf(
            inspectBin.absolutePath,
            options.projectRoot.absolutePath,
            options.profilePath.absolutePath,
            options.outputDir.absolutePath,
            "-v2"
        )

        println("Running JetBrains inspections...")

        val process = ProcessBuilder(command)
            .directory(options.projectRoot)
            .redirectErrorStream(true)
            .apply {
                environment()[runtime.propertiesEnvName] = runtime.propertiesFile.absolutePath
            }
            .start()

        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()
        require(exitCode == 0) {
            buildString {
                append("inspect.sh failed with exit code $exitCode")
                if (output.contains("한 번에 하나의 IDEA 인스턴스만 실행할 수 있습니다.") ||
                    output.contains("Only one instance of IDEA can be run at a time", ignoreCase = true)
                ) {
                    append(". The isolated runtime did not bypass the single-instance lock; close the running IDE and retry.")
                }
                summarizeFailureOutput(output)?.let { summary ->
                    append(" ")
                    append(summary)
                }
            }
        }
        println("Inspection scan completed.")
        return output
    }

    private fun prepareRuntime(options: InspectionRunOptions, inspectBin: File): InspectRuntime {
        val timestamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now())
        val root = options.outputDir.resolve(".smoker-inspect-runtime/$timestamp").absoluteFile
        val configDir = root.resolve("config")
        val systemDir = root.resolve("system")
        val logDir = root.resolve("log")
        configDir.mkdirs()
        systemDir.mkdirs()
        logDir.mkdirs()

        val propertiesFile = root.resolve("idea.properties").apply {
            writeText(
                buildString {
                    appendLine("idea.config.path=${configDir.toJetBrainsPath()}")
                    appendLine("idea.system.path=${systemDir.toJetBrainsPath()}")
                    appendLine("idea.log.path=${logDir.toJetBrainsPath()}")
                }
            )
        }

        return InspectRuntime(
            root = root,
            propertiesFile = propertiesFile,
            propertiesEnvName = resolvePropertiesEnvName(inspectBin)
        )
    }

    private fun resolveInspectBin(configured: String): File {
        val direct = File(configured)
        if (direct.isAbsolute || configured.contains(File.separator)) {
            require(direct.isFile) { "inspect.sh not found: ${direct.absolutePath}" }
            return direct
        }

        val envCandidate = listOf(
            System.getenv("JETBRAINS_INSPECT_BIN"),
            System.getenv("ANDROID_STUDIO_HOME")?.let { "$it/bin/inspect.sh" },
            System.getenv("IDEA_HOME")?.let { "$it/bin/inspect.sh" }
        )
            .filterNotNull()
            .map { File(it) }
            .firstOrNull { it.isFile }
        if (envCandidate != null) return envCandidate

        val searchRoots = buildList {
            add(File("/Applications"))
            add(File(System.getProperty("user.home"), "Applications"))
        }

        val appNames = listOf(
            "Android Studio.app",
            "IntelliJ IDEA.app",
            "IntelliJ IDEA CE.app",
            "PyCharm.app"
        )

        searchRoots.forEach { root ->
            appNames.forEach { appName ->
                val candidate = root.resolve("$appName/Contents/bin/inspect.sh")
                if (candidate.isFile) {
                    return candidate
                }
            }
        }

        error(
            buildString {
                appendLine("Unable to locate inspect.sh.")
                appendLine("Set it explicitly with --inspect-bin=/path/to/inspect.sh")
                appendLine("or export JETBRAINS_INSPECT_BIN=/path/to/inspect.sh")
                append("Checked PATH name '$configured', JETBRAINS_INSPECT_BIN, ANDROID_STUDIO_HOME, IDEA_HOME, /Applications, and ~/Applications.")
            }
        )
    }

    private fun resolvePropertiesEnvName(inspectBin: File): String {
        val path = inspectBin.absolutePath
        return when {
            path.contains("Android Studio.app") -> "STUDIO_PROPERTIES"
            path.contains("PyCharm.app") -> "PYCHARM_PROPERTIES"
            else -> "IDEA_PROPERTIES"
        }
    }

    private fun summarizeFailureOutput(output: String): String? {
        val interesting = output.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filter { line ->
                line.contains("SEVERE", ignoreCase = true) ||
                    line.contains("ERROR", ignoreCase = true) ||
                    line.contains("failed", ignoreCase = true) ||
                    line.contains("Only one instance of IDEA can be run at a time", ignoreCase = true) ||
                    line.contains("한 번에 하나의 IDEA 인스턴스만 실행할 수 있습니다.")
            }
            .take(3)
            .toList()

        if (interesting.isEmpty()) return null
        return "Summary: ${interesting.joinToString(" | ")}"
    }

    private fun File.toJetBrainsPath(): String = absolutePath.replace(File.separatorChar, '/')
}

internal data class InspectRuntime(
    val root: File,
    val propertiesFile: File,
    val propertiesEnvName: String
)
