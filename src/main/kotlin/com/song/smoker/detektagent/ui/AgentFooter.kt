package com.song.smoker.detektagent.ui

import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import com.song.smoker.detektagent.model.AgentSnapshot
import com.song.smoker.detektagent.model.AgentStatus
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.Timer

class AgentFooter : JBPanel<AgentFooter>(BorderLayout()) {

    private val heartbeatDot = HeartbeatDot(dotPx = 6, haloPx = 4).apply {
        color = Theme.success
    }
    private val lastActionLabel = JBLabel("last action 0s ago").apply {
        font = Theme.mono(10)
        foreground = Theme.fgMuted
    }
    private val cycleRing = CycleRing(sizePx = 8).apply {
        color = Theme.accent
    }
    private val cycleLabel = JBLabel("cycle #0").apply {
        font = Theme.mono(10)
        foreground = Theme.fgMuted
    }

    private var lastEventAtMs: Long = 0L
    private val tickTimer = Timer(1000) { refreshLastActionLabel() }

    init {
        isOpaque = false
        border = JBUI.Borders.empty(6, 12)
        add(buildLeft(), BorderLayout.WEST)
        add(buildRight(), BorderLayout.EAST)
    }

    override fun removeNotify() {
        tickTimer.stop()
        super.removeNotify()
    }

    fun render(snapshot: AgentSnapshot) {
        val animated = snapshot.status != AgentStatus.SLEEPING
        heartbeatDot.animated = animated
        cycleRing.animated = animated
        lastEventAtMs = System.currentTimeMillis() - snapshot.heartbeatSecondsAgo * 1000L
        refreshLastActionLabel()
        cycleLabel.text = "cycle #${snapshot.identity.cycleNumber}"
        if (animated) {
            if (!tickTimer.isRunning) tickTimer.start()
        } else {
            tickTimer.stop()
        }
    }

    private fun refreshLastActionLabel() {
        val seconds = if (lastEventAtMs > 0) {
            ((System.currentTimeMillis() - lastEventAtMs) / 1000).coerceAtLeast(0)
        } else 0
        lastActionLabel.text = "last action ${seconds}s ago"
    }

    private fun buildLeft(): JComponent = JPanel().apply {
        isOpaque = false
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        add(heartbeatDot)
        add(Box.createHorizontalStrut(JBUI.scale(6)))
        add(lastActionLabel)
    }

    private fun buildRight(): JComponent = JPanel().apply {
        isOpaque = false
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        add(cycleLabel)
        add(Box.createHorizontalStrut(JBUI.scale(6)))
        add(cycleRing)
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.color = Theme.headerGradBottom
            g2.fillRect(0, 0, width, height)
            g2.color = Theme.borderSoft
            g2.fillRect(0, 0, width, JBUI.scale(1))
        } finally {
            g2.dispose()
        }
        super.paintComponent(g)
    }

    override fun getPreferredSize(): Dimension {
        val base = super.getPreferredSize()
        return Dimension(base.width, base.height.coerceAtLeast(JBUI.scale(28)))
    }
}
