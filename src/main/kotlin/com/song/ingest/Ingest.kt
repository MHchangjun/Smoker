package com.song.ingest

import com.song.db.DetektFindings
import com.song.db.DetektRuns
import com.song.sarif.Finding
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File
import java.time.LocalDateTime
import java.util.UUID

// -------------------- DB ingest --------------------
fun ingest(dbFile: File, projectRoot: File, module: String, sarif: File, exitCode: Int, findings: List<Finding>): Int {
    dbFile.parentFile?.mkdirs()
    Database.connect("jdbc:sqlite:${dbFile.absolutePath}", driver = "org.sqlite.JDBC")

    val now = LocalDateTime.now()
    val runUuid = UUID.randomUUID().toString()

    transaction {
        SchemaUtils.create(DetektRuns, DetektFindings)

        val runId = DetektRuns.insertAndGetId {
            it[DetektRuns.runUuid] = runUuid
            it[DetektRuns.projectRoot] = projectRoot.absolutePath
            it[DetektRuns.module] = module
            it[DetektRuns.sarifPath] = sarif.absolutePath
            it[DetektRuns.exitCode] = exitCode
            it[DetektRuns.executedAt] = now
            it[DetektRuns.findingCount] = findings.size
        }

        DetektFindings.batchInsert(findings) { f ->
            this[DetektFindings.runId] = runId
            this[DetektFindings.ruleId] = f.ruleId
            this[DetektFindings.level] = f.level
            this[DetektFindings.message] = f.message
            this[DetektFindings.uriBaseId] = f.uriBaseId
            this[DetektFindings.uri] = f.uri
            this[DetektFindings.absolutePath] = f.absolutePath
            this[DetektFindings.startLine] = f.startLine
            this[DetektFindings.startColumn] = f.startColumn
            this[DetektFindings.endLine] = f.endLine
            this[DetektFindings.endColumn] = f.endColumn
        }
    }
    return findings.size
}
