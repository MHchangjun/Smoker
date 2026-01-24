package com.song

import com.song.agent.CodeSmellAgent
import com.song.cli.Args
import com.song.cli.parseArgs
import com.song.db.RunSummary
import com.song.db.loadFindingsForRun
import com.song.db.loadLatestRun
import com.song.detekt.DetektConfigContext
import com.song.detekt.loadDetektConfig
import com.song.detekt.runGradleDetekt
import com.song.di.startAgentKoin
import com.song.ingest.ingest
import com.song.sarif.Finding
import com.song.sarif.findSarif
import com.song.sarif.parseSarif
import kotlinx.coroutines.runBlocking

fun main(raw: Array<String>) {
    val args = parseArgs(raw)

    val projectRoot = args.project
    require(projectRoot.isDirectory) { "Project root is not a directory: ${projectRoot.absolutePath}" }
    val koinApp = startAgentKoin(projectRoot.toPath())
    val detektConfig = loadDetektConfig(projectRoot.toPath())

    val task = args.task ?: ":${args.module}:detekt"

    println("Smoker ready. Use the menu to scan or act on existing findings.")
    var latestRun = loadLatestRun(args.db)
    var latestFindings = latestRun?.let { loadFindingsForRun(args.db, it.id) } ?: emptyList()
    printSummary(args, latestRun, latestFindings)

    val agent = koinApp.koin.get<CodeSmellAgent>()

    while (true) {
        println()
        println("Select action: [r]escan, [l]ist, [s]how, [f]ix, [q]uit")
        when (readChoice()) {
            "r" -> {
                val (run, findings) = rescan(projectRoot, args, task)
                latestRun = run
                latestFindings = findings
                printSummary(args, latestRun, latestFindings)
            }
            "l" -> listFindings(latestFindings)
            "s" -> showFinding(latestFindings)
            "f" -> fixAllFindings(agent, detektConfig, latestFindings)
            "q" -> return
            else -> println("Unknown choice. Use r/l/s/f/q.")
        }
    }
}

private fun readChoice(): String {
    return readLine()?.trim()?.lowercase().orEmpty()
}

private fun printSummary(args: Args, run: RunSummary?, findings: List<Finding>) {
    println("DB: ${args.db.absolutePath}")
    if (run == null) {
        println("No previous detekt runs found. Choose 'rescan' to create one.")
        return
    }
    println("Last run: ${run.executedAt} module=${run.module} exit=${run.exitCode} findings=${run.findingCount}")
    if (findings.isEmpty()) {
        println("No findings stored for the last run.")
        return
    }
    val byLevel = findings.groupingBy { it.level ?: "unspecified" }.eachCount()
    val byRule = findings.groupingBy { it.ruleId }.eachCount()
        .entries.sortedByDescending { it.value }
        .take(5)
        .joinToString(", ") { "${it.key}=${it.value}" }
    println("By level: ${byLevel.entries.sortedBy { it.key }.joinToString(", ") { "${it.key}=${it.value}" }}")
    println("Top rules: $byRule")
}

private fun listFindings(findings: List<Finding>) {
    if (findings.isEmpty()) {
        println("No findings to list.")
        return
    }
    println("Findings:")
    findings.forEachIndexed { index, finding ->
        val fileLabel = finding.absolutePath ?: finding.uri ?: "unknown"
        val line = finding.startLine?.let { ":$it" } ?: ""
        val level = finding.level ?: "unspecified"
        println("${index + 1}. ${finding.ruleId} [$level] $fileLabel$line")
    }
}

private fun showFinding(findings: List<Finding>) {
    if (findings.isEmpty()) {
        println("No findings to show.")
        return
    }
    val index = promptIndex(findings.size, "show") ?: return
    val finding = findings[index]
    val fileLabel = finding.absolutePath ?: finding.uri ?: "unknown"
    println("Rule: ${finding.ruleId}")
    println("Level: ${finding.level ?: "unspecified"}")
    println("Message: ${finding.message ?: "no message"}")
    println("File: $fileLabel")
    println("Range: ${finding.startLine ?: "?"}:${finding.startColumn ?: "?"} - ${finding.endLine ?: "?"}:${finding.endColumn ?: "?"}")
}

private fun fixAllFindings(
    agent: CodeSmellAgent,
    detektConfig: DetektConfigContext?,
    findings: List<Finding>
) {
    if (detektConfig == null) {
        println("Detekt config not found. Skipping agent execution.")
        return
    }
    if (findings.isEmpty()) {
        println("No findings to fix.")
        return
    }
    runBlocking {
        for ((index, finding) in findings.withIndex()) {
            val fileLabel = finding.absolutePath ?: finding.uri ?: "unknown"
            println("Fixing finding ${index + 1}/${findings.size}: ${finding.ruleId} @ $fileLabel")
            val result = agent.start(finding, detektConfig)
            println("Agent result: $result")
        }
    }
}

private fun promptIndex(max: Int, verb: String): Int? {
    print("Enter finding number to $verb (1-$max): ")
    val raw = readLine()?.trim().orEmpty()
    val idx = raw.toIntOrNull()
    if (idx == null || idx !in 1..max) {
        println("Invalid number.")
        return null
    }
    return idx - 1
}

private fun rescan(
    projectRoot: java.io.File,
    args: Args,
    task: String
): Pair<RunSummary?, List<Finding>> {
    println("Running detekt: $task in ${projectRoot.absolutePath}")
    val cmdResult = runGradleDetekt(projectRoot, task)
    println("detekt exitCode=${cmdResult.exitCode}")

    val sarifFile = args.sarifPath ?: findSarif(projectRoot, args.module)
    println("Using SARIF: ${sarifFile.absolutePath}")

    val findings = parseSarif(sarifFile)
    println("Parsed findings: ${findings.size}")

    val inserted = ingest(
        dbFile = args.db,
        projectRoot = projectRoot,
        module = args.module,
        sarif = sarifFile,
        exitCode = cmdResult.exitCode,
        findings = findings
    )

    println("Inserted rows: $inserted")
    val latestRun = loadLatestRun(args.db)
    val latestFindings = latestRun?.let { loadFindingsForRun(args.db, it.id) } ?: emptyList()
    return latestRun to latestFindings
}
