package com.song.lint

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.dataformat.xml.XmlMapper
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty
import com.fasterxml.jackson.module.kotlin.kotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import com.song.sarif.Finding
import java.io.File

@JsonIgnoreProperties(ignoreUnknown = true)
private data class LintIssues(
    @JacksonXmlProperty(localName = "issue")
    @JacksonXmlElementWrapper(useWrapping = false)
    val issues: List<LintIssue> = emptyList(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class LintIssue(
    @JacksonXmlProperty(localName = "id", isAttribute = true)
    val id: String? = null,
    @JacksonXmlProperty(localName = "severity", isAttribute = true)
    val severity: String? = null,
    @JacksonXmlProperty(localName = "message", isAttribute = true)
    val message: String? = null,
    @JacksonXmlProperty(localName = "location")
    @JacksonXmlElementWrapper(useWrapping = false)
    @JsonProperty("location")
    val locations: List<LintLocation> = emptyList(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class LintLocation(
    @JacksonXmlProperty(localName = "file", isAttribute = true)
    val file: String? = null,
    @JacksonXmlProperty(localName = "line", isAttribute = true)
    val line: Int? = null,
    @JacksonXmlProperty(localName = "column", isAttribute = true)
    val column: Int? = null,
)

class LintXmlParser {
    fun parse(xmlFile: File): List<Finding> {
        val mapper = XmlMapper().apply {
            registerModule(kotlinModule())
            configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        }
        val report: LintIssues = mapper.readValue(xmlFile)

        val out = ArrayList<Finding>(report.issues.size)
        for (issue in report.issues) {
            val ruleId = issue.id ?: "UNKNOWN_RULE"
            val level = mapSeverity(issue.severity)
            val msg = issue.message
            for (loc in issue.locations) {
                val absolute = loc.file?.let { resolveAbs(it) }
                out += Finding(
                    ruleId = ruleId,
                    level = level,
                    message = msg,
                    uriBaseId = null,
                    uri = loc.file,
                    absolutePath = absolute,
                    startLine = loc.line,
                    startColumn = loc.column,
                    endLine = null,
                    endColumn = null,
                )
            }
        }
        return out
    }

    private fun mapSeverity(severity: String?): String? = when (severity?.lowercase()) {
        "error", "fatal" -> "error"
        "warning" -> "warning"
        "informational", "info" -> "note"
        null -> null
        else -> severity.lowercase()
    }

    private fun resolveAbs(rawPath: String): String? = runCatching {
        val f = File(rawPath)
        if (f.isAbsolute) f.canonicalPath else null
    }.getOrNull()
}
