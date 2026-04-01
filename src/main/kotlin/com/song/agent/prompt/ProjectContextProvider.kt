package com.song.agent.prompt

import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.name

class ProjectContextProvider(
    private val config: ProjectContextConfig,
    rootPath: Path = Paths.get(".")
) {
    private val rootPath: Path = rootPath.toAbsolutePath().normalize()

    private var fileCount = 0
    private var startTimeNs = 0L
    private var truncatedBySize = false
    private var truncatedByTimeout = false
    private var truncatedByFiles = false
    private val moduleTypeCache = mutableMapOf<String, String?>()

    fun getFullContext(): String {
//        val structure = getDirectoryStructure()
        return UtilityPrompt.PROJECT_CONTEXT.read().formatTemplate(
//            "structure" to structure,
//            "abs_path" to rootPath.toString(),
        )
    }

    private fun getDirectoryStructure(): String {
        val settingsFile = findSettingsFile()
        val modules = settingsFile?.let { parseIncludedModules(it) }.orEmpty()
        val settingsName = settingsFile?.fileName?.toString() ?: "settings.gradle(.kts)"

        if (modules.isEmpty()) {
            return "Android module structure of ${rootPath.name}: no modules found in $settingsName."
        }

        val header = "Android module structure of ${rootPath.name} (from $settingsName, depth≤${config.maxDepth}):\n"
        val lines = mutableListOf<String>()
        var currentLength = header.length

        startTimeNs = System.nanoTime()
        fileCount = 0
        truncatedBySize = false
        truncatedByTimeout = false
        truncatedByFiles = false

        fun appendLine(line: String) {
            lines.add(line)
            currentLength += line.length + 1
            if (currentLength > config.maxChars - config.truncationBuffer) {
                truncatedBySize = true
            }
        }

        fun shouldStop(): Boolean {
            if (fileCount >= config.maxFiles) {
                truncatedByFiles = true
                return true
            }
            val elapsedSeconds = (System.nanoTime() - startTimeNs) / 1_000_000_000.0
            if (elapsedSeconds > config.timeoutSeconds) {
                truncatedByTimeout = true
                return true
            }
            if (truncatedBySize) return true
            return false
        }

        val root = buildModuleTree(modules)

        fun render(node: ModuleNode, prefix: String, depth: Int, segments: List<String>) {
            if (depth > config.maxDepth || shouldStop()) return

            val keys = node.children.keys.sorted()
            val showTruncation = keys.size > config.maxDirsPerLevel
            val visible = if (showTruncation) keys.take(config.maxDirsPerLevel) else keys

            for ((index, key) in visible.withIndex()) {
                if (shouldStop()) break

                val child = node.children.getValue(key)
                val isLast = index == visible.lastIndex && !showTruncation
                val connector = if (isLast) "└── " else "├── "
                val childSegments = segments + key
                val label = moduleLabel(child, childSegments)

                appendLine(prefix + connector + key + "/" + label)
                fileCount++

                if (child.children.isNotEmpty() && depth < config.maxDepth) {
                    val childPrefix = prefix + if (isLast) "    " else "│   "
                    render(child, childPrefix, depth + 1, childSegments)
                }
            }

            if (showTruncation && !shouldStop()) {
                val remaining = keys.size - visible.size
                appendLine(prefix + "└── ... (${remaining} more items)")
            }
        }

        render(root, "", 0, emptyList())

        var structure = header + lines.joinToString("\n")

        if (truncatedByFiles) {
            structure += "\n... (truncated at ${config.maxFiles} modules limit)"
        } else if (truncatedByTimeout) {
            structure += "\n... (truncated due to ${config.timeoutSeconds}s timeout)"
        } else if (truncatedBySize) {
            structure += "\n... (truncated at ${config.maxChars} characters)"
        }

        return structure
    }

    private data class ModuleNode(
        val name: String,
        val children: MutableMap<String, ModuleNode> = linkedMapOf(),
        var isModule: Boolean = false,
    )

    private fun findSettingsFile(): Path? {
        val kts = rootPath.resolve("settings.gradle.kts")
        if (kts.toFile().isFile) return kts
        val groovy = rootPath.resolve("settings.gradle")
        if (groovy.toFile().isFile) return groovy
        return null
    }

    private fun parseIncludedModules(settingsFile: Path): List<String> {
        val rawText = readTextSafe(settingsFile) ?: return emptyList()
        val text = stripComments(rawText)
        val modules = mutableListOf<String>()

        val includeCallRegex = Regex("""include\s*\(([^)]*)\)""", RegexOption.DOT_MATCHES_ALL)
        for (match in includeCallRegex.findAll(text)) {
            modules.addAll(extractModuleStrings(match.groupValues[1]))
        }

        val includeLineRegex = Regex("""(?m)^\s*include\s+([^\n]+)""")
        for (match in includeLineRegex.findAll(text)) {
            modules.addAll(extractModuleStrings(match.groupValues[1]))
        }

        return modules.map { it.trim() }
            .filter { it.startsWith(":") }
            .distinct()
    }

    private fun extractModuleStrings(text: String): List<String> {
        val regex = Regex("""['"](:[^'"]+)['"]""")
        return regex.findAll(text).map { it.groupValues[1] }.toList()
    }

    private fun stripComments(text: String): String {
        val noBlockComments = Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL).replace(text, "")
        return Regex("(?m)//.*$").replace(noBlockComments, "")
    }

    private fun buildModuleTree(modules: List<String>): ModuleNode {
        val root = ModuleNode(rootPath.name)
        for (module in modules) {
            val segments = module.trim().trim(':').split(':').filter { it.isNotBlank() }
            var node = root
            for (segment in segments) {
                node = node.children.getOrPut(segment) { ModuleNode(segment) }
            }
            node.isModule = true
        }
        return root
    }

    private fun moduleLabel(node: ModuleNode, segments: List<String>): String {
        if (!node.isModule) return ""
        val key = segments.joinToString(":")
        val type = if (moduleTypeCache.containsKey(key)) {
            moduleTypeCache[key]
        } else {
            detectModuleType(segments).also { moduleTypeCache[key] = it }
        }
        return if (type.isNullOrBlank()) "" else " ($type)"
    }

    private fun detectModuleType(segments: List<String>): String? {
        val moduleDir = segments.fold(rootPath) { acc, seg -> acc.resolve(seg) }
        if (!moduleDir.toFile().isDirectory) return "missing"

        val buildFile = listOf("build.gradle.kts", "build.gradle")
            .map { moduleDir.resolve(it) }
            .firstOrNull { it.toFile().isFile }
            ?: return "no-build"

        val text = readTextSafe(buildFile) ?: return "no-build"

        val type = when {
            containsPlugin(text, "com.android.application") -> "application"
            containsPlugin(text, "com.android.library") -> "library"
            containsPlugin(text, "com.android.dynamic-feature") -> "dynamic-feature"
            containsPlugin(text, "com.android.test") -> "test"
            containsPlugin(text, "com.android.instantapp") -> "instant-app"
            else -> null
        }

        if (type != null) return type
        if (Regex("""\bandroid\s*\{""").containsMatchIn(text)) return "android"
        return ""
    }

    private fun containsPlugin(text: String, id: String): Boolean {
        val escaped = Regex.escape(id)
        val kotlinDsl = Regex("""id\(\s*["']$escaped["']\s*\)""")
        val groovyDsl = Regex("""id\s+["']$escaped["']""")
        val applyPlugin = Regex("""apply\s+plugin:\s*["']$escaped["']""")
        return kotlinDsl.containsMatchIn(text) ||
            groovyDsl.containsMatchIn(text) ||
            applyPlugin.containsMatchIn(text)
    }

    private fun readTextSafe(path: Path): String? {
        return try {
            path.toFile().readText(Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

}

data class ProjectContextConfig(
    val maxChars: Int = 40_000,
    val maxDepth: Int = 3,
    val maxFiles: Int = 1_000,
    val maxDirsPerLevel: Int = 20,
    val truncationBuffer: Int = 1_000,
    val timeoutSeconds: Double = 2.0,
)

private fun String.formatTemplate(vararg pairs: Pair<String, String>): String {
    var out = this
    for ((key, value) in pairs) {
        out = out.replace("{$key}", value)
    }
    return out
}
