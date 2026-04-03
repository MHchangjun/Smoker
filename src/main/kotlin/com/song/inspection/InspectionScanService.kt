package com.song.inspection

import java.io.File
import java.nio.file.Files

internal data class InspectionScanResult(
    val findings: List<InspectionFinding>,
    val warnings: List<String>,
    val resultXmlCount: Int,
    val trusted: Boolean
)

internal class InspectionScanService(
    private val runner: JetBrainsInspectRunner,
    private val parser: JetBrainsInspectionReportParser,
    private val filter: InspectionFilter
) {
    fun scan(options: InspectionFixOptions): InspectionScanResult {
        val outputDir = Files.createTempDirectory("smoker-inspect-").toFile()
        return try {
            val inspectOutput = runner.run(
                InspectionRunOptions(
                    projectRoot = options.projectRoot,
                    profilePath = options.profilePath,
                    outputDir = outputDir,
                    inspectBin = options.inspectBin
                )
            )

            val findings = filter.apply(
                parser.parse(options.projectRoot, outputDir)
            )
                .distinct()
            val resultXmlCount = countResultXmlFiles(outputDir)
            val warnings = buildWarnings(inspectOutput, outputDir, resultXmlCount, findings.size)

            InspectionScanResult(
                findings = findings,
                warnings = warnings,
                resultXmlCount = resultXmlCount,
                trusted = isTrusted(findings.size, resultXmlCount, warnings)
            )
        } finally {
            outputDir.deleteRecursively()
        }
    }

    private fun countResultXmlFiles(outputDir: File): Int {
        if (!outputDir.isDirectory) return 0
        return outputDir.walkTopDown()
            .onEnter { dir -> dir.name != ".smoker-inspect-runtime" }
            .count { file ->
                file.isFile &&
                    file.extension == "xml" &&
                    file.name != "descriptions.xml" &&
                    file.name != ".descriptions.xml"
            }
    }

    private fun buildWarnings(
        inspectOutput: String,
        outputDir: File,
        resultXmlCount: Int,
        findingCount: Int
    ): List<String> {
        val warnings = mutableListOf<String>()

        if (resultXmlCount == 0) {
            warnings += "No per-inspection result XML files were generated in ${outputDir.absolutePath}."
        }

        if (inspectOutput.contains("Descriptions are missed for tools", ignoreCase = true)) {
            warnings += "JetBrains inspect reported missing tool descriptions."
        }

        if (findingCount == 0 && inspectOutput.contains("SEVERE - #c.i.c.InspectionsResultUtil", ignoreCase = true)) {
            warnings += "SEVERE inspection-result errors were logged during the run."
        }

        return warnings
    }

    private fun isTrusted(
        findingCount: Int,
        resultXmlCount: Int,
        warnings: List<String>
    ): Boolean {
        if (findingCount > 0) return true
        if (resultXmlCount == 0) return false
        return warnings.none { warning ->
            warning.contains("missing tool descriptions", ignoreCase = true) ||
                warning.contains("SEVERE inspection-result errors", ignoreCase = true)
        }
    }
}
