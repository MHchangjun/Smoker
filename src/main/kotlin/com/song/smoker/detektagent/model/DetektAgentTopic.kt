package com.song.smoker.detektagent.model

import com.intellij.util.messages.Topic

fun interface DetektAgentListener {
    fun onSnapshot(snapshot: AgentSnapshot)
}

object DetektAgentTopic {
    @JvmField
    val TOPIC: Topic<DetektAgentListener> =
        Topic.create("DetektAgent.Snapshot", DetektAgentListener::class.java)
}
