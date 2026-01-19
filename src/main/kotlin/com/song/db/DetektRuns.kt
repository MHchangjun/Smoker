package com.song.db

import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.javatime.datetime

object DetektRuns : IntIdTable("detekt_runs") {
    val runUuid = varchar("run_uuid", 36).uniqueIndex()
    val projectRoot = text("project_root")
    val module = varchar("module", 64)
    val sarifPath = text("sarif_path")
    val exitCode = integer("exit_code")
    val executedAt = datetime("executed_at")
    val findingCount = integer("finding_count")
}