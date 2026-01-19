package com.song.db

import com.song.sarif.Finding
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File
import java.time.LocalDateTime

data class RunSummary(
    val id: Int,
    val projectRoot: String,
    val module: String,
    val sarifPath: String,
    val exitCode: Int,
    val executedAt: LocalDateTime,
    val findingCount: Int
)

fun loadAllFindings(dbFile: File): List<Finding> {
    Database.connect("jdbc:sqlite:${dbFile.absolutePath}", driver = "org.sqlite.JDBC")

    return transaction {
        SchemaUtils.create(DetektRuns, DetektFindings)
        DetektFindings
            .selectAll()
            .orderBy(DetektFindings.id to SortOrder.ASC)
            .map { row ->
                Finding(
                    ruleId = row[DetektFindings.ruleId],
                    level = row[DetektFindings.level],
                    message = row[DetektFindings.message],
                    uriBaseId = row[DetektFindings.uriBaseId],
                    uri = row[DetektFindings.uri],
                    absolutePath = row[DetektFindings.absolutePath],
                    startLine = row[DetektFindings.startLine],
                    startColumn = row[DetektFindings.startColumn],
                    endLine = row[DetektFindings.endLine],
                    endColumn = row[DetektFindings.endColumn]
                )
            }
    }
}

fun loadLatestRun(dbFile: File): RunSummary? {
    Database.connect("jdbc:sqlite:${dbFile.absolutePath}", driver = "org.sqlite.JDBC")

    return transaction {
        SchemaUtils.create(DetektRuns, DetektFindings)
        DetektRuns
            .selectAll()
            .orderBy(DetektRuns.executedAt to SortOrder.DESC)
            .limit(1)
            .map { row ->
                RunSummary(
                    id = row[DetektRuns.id].value,
                    projectRoot = row[DetektRuns.projectRoot],
                    module = row[DetektRuns.module],
                    sarifPath = row[DetektRuns.sarifPath],
                    exitCode = row[DetektRuns.exitCode],
                    executedAt = row[DetektRuns.executedAt],
                    findingCount = row[DetektRuns.findingCount]
                )
            }
            .singleOrNull()
    }
}

fun loadFindingsForRun(dbFile: File, runId: Int): List<Finding> {
    Database.connect("jdbc:sqlite:${dbFile.absolutePath}", driver = "org.sqlite.JDBC")

    return transaction {
        SchemaUtils.create(DetektRuns, DetektFindings)
        DetektFindings
            .selectAll()
            .where { DetektFindings.runId eq runId }
            .orderBy(DetektFindings.id to SortOrder.ASC)
            .map { row ->
                Finding(
                    ruleId = row[DetektFindings.ruleId],
                    level = row[DetektFindings.level],
                    message = row[DetektFindings.message],
                    uriBaseId = row[DetektFindings.uriBaseId],
                    uri = row[DetektFindings.uri],
                    absolutePath = row[DetektFindings.absolutePath],
                    startLine = row[DetektFindings.startLine],
                    startColumn = row[DetektFindings.startColumn],
                    endLine = row[DetektFindings.endLine],
                    endColumn = row[DetektFindings.endColumn]
                )
            }
    }
}
