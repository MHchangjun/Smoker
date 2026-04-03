package com.song

import com.song.detekt.loadDetektConfig
import com.song.di.startAgentKoin
import com.song.inspection.InspectionFixCommand
import com.song.inspection.InspectionWorkflowRunner
import com.song.workflow.LintWorkflowRunner
import java.io.File

fun main(args: Array<String>) {
    val projectRoot = File(".").absoluteFile
    require(projectRoot.isDirectory) { "Project root is not a directory: ${projectRoot.absolutePath}" }

    val koinApp = startAgentKoin(projectRoot.toPath())

    when (args.firstOrNull() ?: "lint") {
        "lint" -> {
            val detektConfig = loadDetektConfig(projectRoot.toPath())
            val runner = koinApp.koin.get<LintWorkflowRunner>()
            runner.run(projectRoot, detektConfig)
        }

        "inspect-fix" -> {
            val command = InspectionFixCommand(projectRoot)
            val options = command.parse(args.drop(1))
            val runner = koinApp.koin.get<InspectionWorkflowRunner>()
            runner.run(options)
        }

        else -> error("Unknown command '${args.first()}'. Supported commands: lint, inspect-fix")
    }
}
