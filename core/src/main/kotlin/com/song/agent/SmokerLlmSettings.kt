package com.song.agent

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil

@Service(Service.Level.APP)
@State(name = "SmokerLlmSettings", storages = [Storage("smoker.xml")])
class SmokerLlmSettings : PersistentStateComponent<SmokerLlmSettings.SettingsState> {

    class SettingsState {
        var endpoint: String = ""
        var modelId: String = ""
    }

    private var state = SettingsState()

    override fun getState(): SettingsState = state

    override fun loadState(state: SettingsState) {
        XmlSerializerUtil.copyBean(state, this.state)
    }

    var endpoint: String
        get() = state.endpoint
        set(value) { state.endpoint = value }

    var modelId: String
        get() = state.modelId
        set(value) { state.modelId = value }

    fun isConfigured(): Boolean = state.endpoint.isNotBlank() && state.modelId.isNotBlank()

    companion object {
        fun getInstance(): SmokerLlmSettings =
            ApplicationManager.getApplication().getService(SmokerLlmSettings::class.java)
    }
}
