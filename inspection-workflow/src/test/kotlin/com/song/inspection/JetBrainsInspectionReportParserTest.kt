package com.song.inspection

import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class JetBrainsInspectionReportParserTest {
    private val parser = JetBrainsInspectionReportParser()

    @Test
    fun `parses inspection descriptions and problem files into findings`() {
        val projectRoot = createTempDirectory("inspection-project").toFile()
        val outputDir = createTempDirectory("inspection-output").toFile()
        val sourceFile = projectRoot.resolve("app/src/main/java/com/example/Sample.kt").apply {
            parentFile.mkdirs()
            writeText("class Sample")
        }

        outputDir.toPath().resolve("descriptions.xml").writeText(
            """
            <inspections profile="Project Default">
              <group name="Inheritance issues" path="Java">
                <inspection shortName="RefusedBequest" defaultSeverity="WARNING" displayName="Method does not call super method" enabled="true" />
              </group>
            </inspections>
            """.trimIndent()
        )

        outputDir.toPath().resolve("RefusedBequest.xml").writeText(
            """
            <problems>
              <problem>
                <file>file://${'$'}PROJECT_DIR${'$'}/app/src/main/java/com/example/Sample.kt</file>
                <line>12</line>
                <problem_class severity="WARNING">Method does not call super method</problem_class>
                <description>Method overrides a super method without calling it</description>
              </problem>
            </problems>
            """.trimIndent()
        )

        val findings = parser.parse(projectRoot, outputDir)

        assertEquals(1, findings.size)
        val finding = findings.single()
        assertEquals("RefusedBequest", finding.ruleId)
        assertEquals("WARNING", finding.level)
        assertEquals(12, finding.startLine)
        assertEquals(sourceFile.canonicalPath, finding.absolutePath)
        assertEquals("app/src/main/java/com/example/Sample.kt", finding.uri)
        assertNotNull(finding.message)
    }
}
