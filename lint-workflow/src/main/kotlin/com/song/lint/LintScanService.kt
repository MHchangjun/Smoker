package com.song.lint

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.song.sarif.Finding
import com.song.sarif.SarifReport
import com.song.workflow.runGradleTask
import java.io.File
import java.net.URI

data class LintScanResult(
    val findings: List<Finding>,
)

class LintScanService(
    private val xmlParser: LintXmlParser = LintXmlParser(),
) {
    private val skipRules = setOf(
        "MissingTranslation",
        "IconMissingDensityFolder",
        "GoogleAppIndexingWarning",
    )

    fun scan(context: LintRunContext): LintScanResult {
        println("Running lint: ${context.task} in ${context.projectRoot.absolutePath}")
        val cmdResult = runGradleTask(context.projectRoot, context.task)
        println("lint exitCode=${cmdResult.exitCode}")

        val reports = findReportFiles(context.projectRoot)
        if (reports.isEmpty()) {
            error(
                "No lint report found under */build/reports/. " +
                        "Expected lint-results*.{sarif,xml}. " +
                        "Confirm the lint task generated XML or SARIF output."
            )
        }

        val findings = reports.flatMap { report ->
            when (report.format) {
                ReportFormat.SARIF -> {
                    println("Using SARIF: ${report.file.absolutePath}")
                    parseSarif(report.file)
                }

                ReportFormat.XML -> {
                    println("Using XML: ${report.file.absolutePath}")
                    xmlParser.parse(report.file)
                }
            }
        }

        val filtered = findings.filter { it.ruleId !in skipRules }
        println("Parsed findings: ${filtered.size}")
        return LintScanResult(findings = filtered)
    }

    /**
     * Walks every module's `build/reports/` looking for lint output. Prefers SARIF over XML
     * when both exist for the same variant (same prefix before the extension).
     */
    private fun findReportFiles(projectRoot: File): List<ReportLocation> {
        val candidates = projectRoot.walkTopDown()
            .onEnter { dir -> dir.name != "src" && !dir.path.contains("/.gradle/") }
            .filter { it.isFile }
            .filter {
                val parent = it.parentFile?.path?.replace('\\', '/').orEmpty()
                parent.endsWith("/build/reports") &&
                    it.name.startsWith("lint-results") &&
                    (it.extension == "sarif" || it.extension == "xml")
            }
            .toList()

        // Group by (parent dir + base name without extension) so SARIF wins over XML for the same report.
        val grouped = candidates.groupBy { "${it.parentFile.path}|${it.nameWithoutExtension}" }
        return grouped.values.mapNotNull { group ->
            val sarif = group.firstOrNull { it.extension == "sarif" }
            val xml = group.firstOrNull { it.extension == "xml" }
            when {
                sarif != null -> ReportLocation(sarif, ReportFormat.SARIF)
                xml != null -> ReportLocation(xml, ReportFormat.XML)
                else -> null
            }
        }.sortedByDescending { it.file.lastModified() }
    }

    private fun parseSarif(sarifFile: File): List<Finding> {
        val mapper = jacksonObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        val report: SarifReport = mapper.readValue(sarifFile)

        val out = ArrayList<Finding>(256)
        for (run in report.runs) {
            val baseMap = run.originalUriBaseIds.orEmpty().mapValues { it.value.uri.orEmpty() }

            for (result in run.results.orEmpty()) {
                val ruleId = result.ruleId ?: "UNKNOWN_RULE"
                val level = result.level
                val msg = result.message?.text

                for (loc in result.locations.orEmpty()) {
                    val phys = loc.physicalLocation
                    val art = phys?.artifactLocation
                    val region = phys?.region

                    val uriBaseId = art?.uriBaseId
                    val uri = art?.uri
                    val baseUri = uriBaseId?.let { baseMap[it] }
                    val abs = resolveAbs(baseUri, uri)

                    out += Finding(
                        ruleId = ruleId,
                        level = level,
                        message = msg,
                        uriBaseId = uriBaseId,
                        uri = uri,
                        absolutePath = abs,
                        startLine = region?.startLine,
                        startColumn = region?.startColumn,
                        endLine = region?.endLine,
                        endColumn = region?.endColumn,
                    )
                }
            }
        }
        return out
    }

    private fun resolveAbs(baseUri: String?, relativeUri: String?): String? {
        if (relativeUri.isNullOrBlank()) return null
        if (relativeUri.startsWith("file:")) return runCatching { File(URI(relativeUri)).canonicalPath }.getOrNull()
        if (baseUri.isNullOrBlank()) return relativeUri

        val baseFile =
            if (baseUri.startsWith("file:")) runCatching { File(URI(baseUri)) }.getOrNull() else File(baseUri)
        baseFile ?: return null

        return runCatching { File(baseFile, relativeUri).canonicalPath }.getOrNull()
    }

    private enum class ReportFormat { SARIF, XML }
    private data class ReportLocation(val file: File, val format: ReportFormat)
}
