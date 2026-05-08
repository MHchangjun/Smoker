package com.song.sarif

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

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
