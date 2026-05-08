package com.song.detekt

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.song.sarif.Finding
import com.song.sarif.SarifReport
import com.song.workflow.runGradleTask
import java.io.File
import java.net.URI

data class DetektScanResult(
    val findings: List<Finding>
)

class DetektScanService {
    private val skipRules = setOf(
        "detekt.exceptions.TooGenericExceptionCaught",
        "detekt.exceptions.TooGenericExceptionThrown",
        "detekt.exceptions.SwallowedException",
    )

    fun scan(context: DetektRunContext): DetektScanResult {
        println("Running detekt: ${context.task} in ${context.projectRoot.absolutePath}")
        val cmdResult = runGradleTask(context.projectRoot, context.task)
        println("detekt exitCode=${cmdResult.exitCode}")

        val sarifFile = findSarif(context.projectRoot, context.module)
        println("Using SARIF: ${sarifFile.absolutePath}")

        val findings = parseSarif(sarifFile).filter {
            it.ruleId !in skipRules
        }
        println("Parsed findings: ${findings.size}")
        return DetektScanResult(findings = findings)
    }

    private fun findSarif(projectRoot: File, module: String): File {
        val direct = File(projectRoot, "$module/build/reports/detekt/detekt.sarif")
        if (direct.exists()) return direct

        val dir = File(projectRoot, "$module/build/reports/detekt")
        if (dir.isDirectory) {
            val latest =
                dir.listFiles()?.filter { it.isFile && it.extension == "sarif" }?.maxByOrNull { it.lastModified() }
            if (latest != null) return latest
        }

        val candidates = projectRoot.walkTopDown()
            .filter { it.isFile && it.extension == "sarif" }
            .filter { it.path.contains("${File.separator}build${File.separator}reports${File.separator}detekt${File.separator}") }
            .toList()

        return candidates.maxByOrNull { it.lastModified() }
            ?: error("No SARIF report found. Ensure detekt sarif report is enabled and detekt was executed.")
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
                        endColumn = region?.endColumn
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
}
