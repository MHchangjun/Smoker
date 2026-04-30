package com.song.smoker.detektagent.ui

import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BoxLayout

enum class PanelTab { ACTIVITY, QUEUE, RULES }

class PanelTabs(
    private val onChange: (PanelTab) -> Unit,
) : JBPanel<PanelTabs>() {

    private val activityBtn = TabButton("Activity", "∞")
    private val queueBtn = TabButton("Queue", "0")
    private val rulesBtn = TabButton("Rules", "0")

    private var current: PanelTab = PanelTab.ACTIVITY

    override fun getMaximumSize(): Dimension =
        Dimension(Int.MAX_VALUE, preferredSize.height)

    init {
        isOpaque = false
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        border = JBUI.Borders.empty()
        add(activityBtn)
        add(queueBtn)
        add(rulesBtn)
        activityBtn.setActive(true)
        wire(activityBtn, PanelTab.ACTIVITY)
        wire(queueBtn, PanelTab.QUEUE)
        wire(rulesBtn, PanelTab.RULES)
    }

    fun setBadges(queueCount: Int, ruleCount: Int) {
        queueBtn.setBadge(queueCount.toString())
        rulesBtn.setBadge(ruleCount.toString())
    }

    fun select(tab: PanelTab) {
        current = tab
        activityBtn.setActive(tab == PanelTab.ACTIVITY)
        queueBtn.setActive(tab == PanelTab.QUEUE)
        rulesBtn.setActive(tab == PanelTab.RULES)
    }

    private fun wire(btn: TabButton, tab: PanelTab) {
        btn.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (current == tab) return
                select(tab)
                onChange(tab)
            }
        })
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.color = Theme.borderSoft
            g2.fillRect(0, height - 1, width, 1)
        } finally {
            g2.dispose()
        }
        super.paintComponent(g)
    }
}

private class TabButton(label: String, badgeText: String) : JBPanel<TabButton>(BorderLayout()) {

    private var active = false
    private val labelText = label
    private var badgeValue = badgeText

    init {
        isOpaque = false
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        border = JBUI.Borders.empty(7, 10)
        preferredSize = Dimension(JBUI.scale(120), JBUI.scale(28))
    }

    fun setActive(value: Boolean) {
        active = value
        repaint()
    }

    fun setBadge(text: String) {
        badgeValue = text
        repaint()
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

            val labelColor: Color = if (active) Theme.fg else Theme.fgMuted
            g2.font = if (active) Theme.uiBold(11) else Theme.ui(11)
            g2.color = labelColor
            val fm = g2.fontMetrics
            val padX = JBUI.scale(10)
            val labelY = height / 2 - fm.height / 2 + fm.ascent
            g2.drawString(labelText, padX, labelY)
            val labelW = fm.stringWidth(labelText)

            // badge
            val badgePadX = JBUI.scale(6)
            val badgeH = JBUI.scale(14)
            g2.font = Theme.mono(9)
            val bfm = g2.fontMetrics
            val badgeW = bfm.stringWidth(badgeValue) + badgePadX * 2
            val badgeX = padX + labelW + JBUI.scale(6)
            val badgeY = height / 2 - badgeH / 2
            val r = badgeH

            if (active) {
                g2.color = Theme.accentSoft
                g2.fillRoundRect(badgeX, badgeY, badgeW, badgeH, r, r)
                g2.color = Theme.accent
            } else {
                g2.color = Theme.bgElevated
                g2.fillRoundRect(badgeX, badgeY, badgeW, badgeH, r, r)
                g2.color = Theme.fgMuted
            }
            val tx = badgeX + badgePadX
            val ty = badgeY + badgeH / 2 - bfm.height / 2 + bfm.ascent
            g2.drawString(badgeValue, tx, ty)

            // active underline
            if (active) {
                g2.color = Theme.accent
                val underlineW = labelW + badgeW + JBUI.scale(8)
                g2.fillRect(padX, height - JBUI.scale(2), underlineW, JBUI.scale(2))
            }
        } finally {
            g2.dispose()
        }
    }
}
