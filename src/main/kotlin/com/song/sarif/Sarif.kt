package com.song.sarif

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.io.File
import java.net.URI

// -------------------- SARIF minimal model --------------------
@JsonIgnoreProperties(ignoreUnknown = true)
data class SarifReport(val runs: List<SarifRun> = emptyList())

@JsonIgnoreProperties(ignoreUnknown = true)
data class SarifRun(
    val originalUriBaseIds: Map<String, SarifBase>? = null,
    val results: List<SarifResult>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SarifBase(val uri: String? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SarifResult(
    val level: String? = null,
    val ruleId: String? = null,
    val message: SarifMessage? = null,
    val locations: List<SarifLocation>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SarifMessage(val text: String? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SarifLocation(val physicalLocation: SarifPhysicalLocation? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SarifPhysicalLocation(
    val artifactLocation: SarifArtifactLocation? = null,
    val region: SarifRegion? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SarifArtifactLocation(
    val uri: String? = null,
    val uriBaseId: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SarifRegion(
    val startLine: Int? = null,
    val startColumn: Int? = null,
    val endLine: Int? = null,
    val endColumn: Int? = null
)

data class Finding(
    val ruleId: String,
    val level: String?,
    val message: String?,
    val uriBaseId: String?,
    val uri: String?,
    val absolutePath: String?,
    val startLine: Int?,
    val startColumn: Int?,
    val endLine: Int?,
    val endColumn: Int?
)

// -------------------- SARIF locate + parse --------------------
fun findSarif(projectRoot: File, module: String): File {
    val direct = File(projectRoot, "$module/build/reports/detekt/detekt.sarif")
    if (direct.exists()) return direct

    val dir = File(projectRoot, "$module/build/reports/detekt")
    if (dir.isDirectory) {
        val latest = dir.listFiles()?.filter { it.isFile && it.extension == "sarif" }?.maxByOrNull { it.lastModified() }
        if (latest != null) return latest
    }

    val candidates = projectRoot.walkTopDown()
        .filter { it.isFile && it.extension == "sarif" }
        .filter { it.path.contains("${File.separator}build${File.separator}reports${File.separator}detekt${File.separator}") }
        .toList()

    return candidates.maxByOrNull { it.lastModified() }
        ?: error("No SARIF report found. Ensure detekt sarif report is enabled and detekt was executed.")
}

fun parseSarif(sarifFile: File): List<Finding> {
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

fun resolveAbs(baseUri: String?, relativeUri: String?): String? {
    if (relativeUri.isNullOrBlank()) return null
    if (relativeUri.startsWith("file:")) return runCatching { File(URI(relativeUri)).canonicalPath }.getOrNull()
    if (baseUri.isNullOrBlank()) return relativeUri

    val baseFile = if (baseUri.startsWith("file:")) runCatching { File(URI(baseUri)) }.getOrNull() else File(baseUri)
    baseFile ?: return null

    return runCatching { File(baseFile, relativeUri).canonicalPath }.getOrNull()
}
