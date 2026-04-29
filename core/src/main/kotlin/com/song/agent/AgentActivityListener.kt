package com.song.agent

interface AgentActivityListener {
    fun onAgentStart(input: String) {}
    fun onToolCallStart(name: String, args: String) {}
    fun onToolCallCompleted(name: String, summary: String) {}
    fun onToolCallFailed(name: String, message: String) {}
    fun onReasoning(text: String) {}
    fun onAssistant(text: String) {}

    object NONE : AgentActivityListener
}
