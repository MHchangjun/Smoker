package com.song.smoker.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.song.smoker.SmokerService
import kotlinx.coroutines.runBlocking
import java.awt.BorderLayout
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea

class SmokerPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val logArea = JTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
    }
    private val runButton = JButton("Run Smoker")

    init {
        add(JScrollPane(logArea), BorderLayout.CENTER)
        add(runButton, BorderLayout.SOUTH)

        runButton.addActionListener { onRunClick() }
    }

    private fun onRunClick() {
        runButton.isEnabled = false
        appendLine("---")
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Smoker", true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    runBlocking {
                        SmokerService(project, ::appendLine).run()
                    }
                } catch (e: Throwable) {
                    appendLine("ERROR: ${e.javaClass.simpleName}: ${e.message}")
                    e.printStackTrace()
                }
            }

            override fun onFinished() {
                runButton.isEnabled = true
            }
        })
    }

    private fun appendLine(line: String) {
        ApplicationManager.getApplication().invokeLater {
            logArea.append("$line\n")
            logArea.caretPosition = logArea.document.length
        }
    }
}
