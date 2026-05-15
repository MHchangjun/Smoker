package com.song.smoker

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.song.agent.SmokerLlmSettings
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.JComponent
import javax.swing.JPanel

class SmokerLlmSettingsDialog(project: Project?) : DialogWrapper(project, true) {

    private val settings = SmokerLlmSettings.getInstance()

    private val endpointField = JBTextField(
        settings.endpoint.ifBlank { System.getenv("SMOKER_LLM_ENDPOINT").orEmpty() },
        30,
    )
    private val modelIdField = JBTextField(settings.modelId, 30)

    init {
        title = "Smoker LLM Settings"
        setOKButtonText("Save")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(GridBagLayout())
        panel.border = JBUI.Borders.empty(12)
        val gbc = GridBagConstraints().apply {
            insets = JBUI.insets(4)
            anchor = GridBagConstraints.WEST
        }

        gbc.gridx = 0; gbc.gridy = 0
        panel.add(JBLabel("Endpoint URL:"), gbc)
        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0
        panel.add(endpointField, gbc)

        gbc.gridx = 0; gbc.gridy = 1; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0.0
        panel.add(JBLabel("Model ID:"), gbc)
        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0
        panel.add(modelIdField, gbc)

        return panel
    }

    override fun getPreferredFocusedComponent(): JComponent =
        if (endpointField.text.isBlank()) endpointField else modelIdField

    override fun doValidate(): ValidationInfo? = when {
        endpointField.text.isBlank() -> ValidationInfo("Endpoint URL is required", endpointField)
        modelIdField.text.isBlank() -> ValidationInfo("Model ID is required", modelIdField)
        else -> null
    }

    override fun doOKAction() {
        settings.endpoint = endpointField.text.trim()
        settings.modelId = modelIdField.text.trim()
        super.doOKAction()
    }
}
