package com.song.screen.datasource

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

class UiDataSourceScanStore {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun pathFor(projectRoot: File): File = File(projectRoot, ".smoker/ui-datasource-scan.json")

    fun save(projectRoot: File, report: UiDataSourceScanReport): File {
        val target = pathFor(projectRoot)
        target.parentFile?.mkdirs()
        target.writeText(json.encodeToString(report))
        return target
    }

    fun load(projectRoot: File): UiDataSourceScanReport? {
        val target = pathFor(projectRoot)
        if (!target.exists()) return null
        return runCatching { json.decodeFromString<UiDataSourceScanReport>(target.readText()) }
            .getOrNull()
            ?.takeIf { it.schemaVersion == UiDataSourceScanReport.CURRENT_SCHEMA_VERSION }
    }
}
