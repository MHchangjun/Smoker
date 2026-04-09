package com.song

import com.song.detekt.DetektWorkflowRunner
import com.song.detekt.loadDetektConfig
import com.song.di.startAgentKoin
import java.io.File

fun main() {
    val projectRoot = File(".").absoluteFile
    require(projectRoot.isDirectory) { "Project root is not a directory: ${projectRoot.absolutePath}" }

    val koinApp = startAgentKoin(projectRoot.toPath())

    val detektConfig = loadDetektConfig(projectRoot.toPath())
    val runner = koinApp.koin.get<DetektWorkflowRunner>()
    runner.run(projectRoot, detektConfig)
}
