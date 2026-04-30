package com.song.smoker.detektagent.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import com.song.smoker.detektagent.DetektAgentBridge
import com.song.smoker.detektagent.model.AgentSnapshot
import com.song.smoker.detektagent.model.AgentStatus
import com.song.smoker.detektagent.model.DetektAgentListener
import com.song.smoker.detektagent.model.DetektAgentTopic
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Dimension
import javax.swing.JPanel

class DetektAgentPanel(project: Project) : JBPanel<DetektAgentPanel>(BorderLayout()) {

    private val header = AgentHeader()
    private val nowCard = NowCard()
    private val tabs = PanelTabs(::onTabChange)

    private val activityFeed = ActivityFeed()
    private val queueList = QueueList()
    private val ruleList = RuleList()

    private val tabContent = JPanel(CardLayout()).apply {
        isOpaque = false
        add(activityFeed, PanelTab.ACTIVITY.name)
        add(queueList, PanelTab.QUEUE.name)
        add(ruleList, PanelTab.RULES.name)
    }

    private val footer = AgentFooter()

    init {
        background = Theme.bg
        isOpaque = true

        val stack = JPanel().apply {
            isOpaque = false
            layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)
            add(header.alignedLeft())
            add(nowCard.alignedLeft())
            add(tabs.alignedLeft())
        }
        add(stack, BorderLayout.NORTH)
        add(tabContent, BorderLayout.CENTER)
        add(footer, BorderLayout.SOUTH)

        preferredSize = Dimension(JBUI.scale(420), JBUI.scale(720))
        minimumSize = Dimension(JBUI.scale(340), JBUI.scale(400))

        // Subscribe to MessageBus topic for live snapshots
        project.messageBus.connect(project).subscribe(
            DetektAgentTopic.TOPIC,
            DetektAgentListener { snapshot ->
                ApplicationManager.getApplication().invokeLater { render(snapshot) }
            },
        )

        // Render the bridge's current state on first open (empty until a run starts)
        render(project.service<DetektAgentBridge>().snapshot())
    }

    private fun onTabChange(tab: PanelTab) {
        (tabContent.layout as CardLayout).show(tabContent, tab.name)
    }

    private fun render(snapshot: AgentSnapshot) {
        header.render(snapshot)
        if (snapshot.status == AgentStatus.SLEEPING) {
            nowCard.render(null)
        } else {
            nowCard.render(snapshot.nowWorking)
        }
        tabs.setBadges(snapshot.queue.size, snapshot.rules.size)
        activityFeed.render(snapshot.feed)
        queueList.render(snapshot.queue)
        ruleList.render(snapshot.rules)
        footer.render(snapshot)
        revalidate()
        repaint()
    }
}

private fun <T : javax.swing.JComponent> T.alignedLeft(): T {
    alignmentX = java.awt.Component.LEFT_ALIGNMENT
    return this
}
