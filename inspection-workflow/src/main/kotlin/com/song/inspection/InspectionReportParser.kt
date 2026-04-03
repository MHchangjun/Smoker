package com.song.inspection

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

class JetBrainsInspectionReportParser {
    fun parse(projectRoot: File, outputDir: File): List<InspectionFinding> {
        require(outputDir.isDirectory) { "Inspection output directory not found: ${outputDir.absolutePath}" }

        val descriptions = parseDescriptions(findDescriptionsFile(outputDir))
        val findings = outputDir.walkTopDown()
            .filter { it.isFile && it.extension == "xml" }
            .filter { it.name != "descriptions.xml" && it.name != ".descriptions.xml" }
            .flatMap { file -> parseInspectionFile(projectRoot, file, descriptions).asSequence() }
            .sortedWith(compareBy<InspectionFinding>({ it.absolutePath ?: "" }, { it.startLine ?: Int.MAX_VALUE }, { it.startColumn ?: Int.MAX_VALUE }, { it.ruleId }))
            .toList()

        return findings
    }

    private fun findDescriptionsFile(outputDir: File): File? {
        val visible = outputDir.resolve("descriptions.xml")
        if (visible.isFile) return visible

        val hidden = outputDir.resolve(".descriptions.xml")
        if (hidden.isFile) return hidden

        return null
    }

    private fun parseDescriptions(file: File?): Map<String, InspectionDescriptor> {
        if (file == null || !file.isFile) return emptyMap()
        val document = parseXml(file) ?: return emptyMap()

        return document.getElementsByTagName("inspection")
            .asElementSequence()
            .map { inspection ->
                val group = inspection.parentNode as? Element
                val shortName = inspection.getAttribute("shortName").trim()
                shortName to InspectionDescriptor(
                    shortName = shortName,
                    displayName = inspection.getAttribute("displayName").trim().ifBlank { null },
                    groupName = group?.getAttribute("name")?.trim()?.ifBlank { null },
                    defaultSeverity = inspection.getAttribute("defaultSeverity").trim().ifBlank { null }
                )
            }
            .filter { (shortName, _) -> shortName.isNotBlank() }
            .toMap()
    }

    private fun parseInspectionFile(
        projectRoot: File,
        file: File,
        descriptions: Map<String, InspectionDescriptor>
    ): List<InspectionFinding> {
        val document = parseXml(file) ?: return emptyList()
        val ruleId = file.nameWithoutExtension
        val descriptor = descriptions[ruleId]

        return document.getElementsByTagName("problem")
            .asElementSequence()
            .mapNotNull { problem ->
                val rawPath = problem.directChildText("file")
                val absolutePath = rawPath?.let { resolveInspectionPath(it, projectRoot) }
                val displayName = descriptor?.displayName ?: problem.directChildText("problem_class")?.normalizeWhitespace()
                val description = problem.directChildText("description")?.normalizeWhitespace()
                val groupName = descriptor?.groupName
                val message = listOfNotNull(displayName, description, groupName?.let { "(group: $it)" })
                    .joinToString(": ")
                    .ifBlank { null }

                InspectionFinding(
                    ruleId = ruleId,
                    level = problem.problemSeverity() ?: descriptor?.defaultSeverity,
                    message = message,
                    uriBaseId = null,
                    uri = absolutePath?.let { relativize(projectRoot, it) } ?: rawPath,
                    absolutePath = absolutePath,
                    startLine = problem.directChildText("line")?.toIntOrNull(),
                    startColumn = null,
                    endLine = problem.directChildText("line")?.toIntOrNull(),
                    endColumn = null
                )
            }
            .toList()
    }

    private fun parseXml(file: File) = runCatching {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        }
        factory.newDocumentBuilder().parse(file)
    }.getOrElse { error ->
        println("Skipping unreadable inspection report ${file.absolutePath}: ${error.message}")
        null
    }

    private fun resolveInspectionPath(rawPath: String, projectRoot: File): String? {
        val decoded = URLDecoder.decode(rawPath.trim(), StandardCharsets.UTF_8)
        val normalizedProjectRoot = projectRoot.absoluteFile.normalize().path

        return when {
            decoded.startsWith("file://\$PROJECT_DIR\$/") ->
                File(decoded.removePrefix("file://").replace("\$PROJECT_DIR$", normalizedProjectRoot)).canonicalPath
            decoded.startsWith("\$PROJECT_DIR\$/") ->
                File(decoded.replace("\$PROJECT_DIR$", normalizedProjectRoot)).canonicalPath
            decoded.startsWith("file:/") ->
                runCatching { File(URI(decoded)).canonicalPath }.getOrNull()
            decoded.startsWith("/") ->
                runCatching { File(decoded).canonicalPath }.getOrNull()
            else ->
                runCatching { File(projectRoot, decoded).canonicalPath }.getOrNull()
        }
    }

    private fun relativize(projectRoot: File, absolutePath: String): String? {
        val root = projectRoot.canonicalFile.toPath().normalize()
        val candidate = File(absolutePath).canonicalFile.toPath().normalize()
        if (!candidate.startsWith(root)) return null
        return root.relativize(candidate).toString().replace(File.separatorChar, '/')
    }

    private fun Element.problemSeverity(): String? {
        val problemClass = directChild("problem_class") ?: return null
        return problemClass.getAttribute("severity").trim().ifBlank { null }
    }

    private fun Element.directChild(name: String): Element? {
        for (index in 0 until childNodes.length) {
            val child = childNodes.item(index)
            if (child.nodeType == Node.ELEMENT_NODE && child.nodeName == name) {
                return child as Element
            }
        }
        return null
    }

    private fun Element.directChildText(name: String): String? {
        return directChild(name)?.textContent?.trim()?.ifBlank { null }
    }

    private fun String.normalizeWhitespace(): String {
        return replace(Regex("""\s+"""), " ").trim()
    }

    private fun org.w3c.dom.NodeList.asElementSequence(): Sequence<Element> = sequence {
        for (index in 0 until length) {
            val node = item(index)
            if (node is Element) {
                yield(node)
            }
        }
    }
}

data class InspectionDescriptor(
    val shortName: String,
    val displayName: String?,
    val groupName: String?,
    val defaultSeverity: String?
)
