package com.song.db

import org.jetbrains.exposed.dao.id.IntIdTable

object DetektFindings : IntIdTable("detekt_findings") {
    val runId = reference("run_id", DetektRuns)

    val ruleId = varchar("rule_id", 255)
    val level = varchar("level", 32).nullable()
    val message = text("message").nullable()

    val uriBaseId = varchar("uri_base_id", 64).nullable()
    val uri = text("uri").nullable()
    val absolutePath = text("absolute_path").nullable()

    val startLine = integer("start_line").nullable()
    val startColumn = integer("start_column").nullable()
    val endLine = integer("end_line").nullable()
    val endColumn = integer("end_column").nullable()
}