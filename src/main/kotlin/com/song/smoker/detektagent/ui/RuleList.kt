package com.song.smoker.detektagent.ui

import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.song.smoker.detektagent.model.RuleProgress
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.LinearGradientPaint
import java.awt.RenderingHints
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants
import javax.swing.Timer

class RuleList : JBPanel<RuleList>(BorderLayout()) {

    private val itemsPanel = JPanel().apply {
        isOpaque = false
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
    }
    private val countBadge = CountBadge("0").apply { active = true }
    private val sectionLabel = JBLabel("RULE PROGRESS").apply {
        font = Theme.uiBold(9).deriveFont(mapOf(java.awt.font.TextAttribute.TRACKING to 0.06))
        foreground = Theme.fgMuted
    }
    private val rightLabel = JBLabel("today").apply {
        font = Theme.ui(10)
        foreground = Theme.fgMuted
    }

    init {
        isOpaque = false
        add(buildHead(), BorderLayout.NORTH)
        add(JBScrollPane(itemsPanel).apply {
            border = JBUI.Borders.empty()
            viewport.isOpaque = false
            isOpaque = false
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        }, BorderLayout.CENTER)
    }

    private fun buildHead(): JComponent {
        val left = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(sectionLabel)
            add(Box.createHorizontalStrut(JBUI.scale(8)))
            add(countBadge)
        }
        return JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(10, 12, 8, 12)
            add(left, BorderLayout.WEST)
            add(rightLabel, BorderLayout.EAST)
        }
    }

    fun render(rules: List<RuleProgress>) {
        countBadge.setValue(rules.size.toString())
        val sorted = rules.sortedByDescending { it.remaining }
        val runningTop3 = sorted.filter { it.running }.take(3).map { it.ruleId }.toSet()
        itemsPanel.removeAll()
        if (sorted.isEmpty()) {
            itemsPanel.add(JBLabel("No rules yet").apply {
                font = Theme.ui(11)
                foreground = Theme.fgMuted
                border = JBUI.Borders.empty(20, 14)
                alignmentX = LEFT_ALIGNMENT
            })
        } else {
            sorted.forEach {
                itemsPanel.add(RuleRow(it, animate = it.ruleId in runningTop3))
            }
        }
        itemsPanel.add(Box.createVerticalGlue())
        itemsPanel.revalidate()
        itemsPanel.repaint()
    }
}

private class RuleRow(rule: RuleProgress, animate: Boolean) : JBPanel<RuleRow>() {

    private val bar = RuleProgressBar(rule.done, rule.total, animate)

    init {
        isOpaque = false
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.empty(6, 12)

        val name = JBLabel(rule.ruleId).apply {
            font = Theme.mono(11)
            foreground = Theme.fg
        }
        val group = JBLabel(" · ${rule.ruleGroup}").apply {
            font = Theme.mono(10)
            foreground = Theme.fgMuted
        }
        val left = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            alignmentX = LEFT_ALIGNMENT
            add(name)
            add(group)
        }

        val rightText = if (rule.remaining == 0) "✓" else "${rule.done}/${rule.total} · ${rule.remaining} left"
        val rightColor = if (rule.remaining == 0) Theme.success else Theme.fgMuted
        val right = JBLabel(rightText).apply {
            font = Theme.mono(10)
            foreground = rightColor
        }

        val topRow = JPanel(BorderLayout()).apply {
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
            add(left, BorderLayout.WEST)
            add(right, BorderLayout.EAST)
        }
        bar.alignmentX = LEFT_ALIGNMENT
        add(topRow)
        add(Box.createVerticalStrut(JBUI.scale(4)))
        add(bar)
    }

    override fun getMaximumSize(): Dimension {
        val pref = preferredSize
        return Dimension(Int.MAX_VALUE, pref.height)
    }
}

private class RuleProgressBar(
    private val done: Int,
    private val total: Int,
    private val animate: Boolean,
) : JComponent() {

    private var shimmerOffset = 0f
    private val frameMs = 33
    private val cycleMs = 1600
    private val timer = Timer(frameMs) {
        shimmerOffset = (shimmerOffset + frameMs.toFloat() / cycleMs) % 1f
        repaint()
    }

    init {
        preferredSize = Dimension(JBUI.scale(80), JBUI.scale(3))
        minimumSize = Dimension(JBUI.scale(40), JBUI.scale(3))
        maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(3))
        isOpaque = false
    }

    override fun addNotify() {
        super.addNotify()
        if (animate) timer.start()
    }

    override fun removeNotify() {
        timer.stop()
        super.removeNotify()
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val r = JBUI.scale(2)
            g2.color = Theme.borderSoft
            g2.fillRoundRect(0, 0, width, height, r, r)

            val ratio = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
            val fillW = (width * ratio).toInt()
            if (fillW > 0) {
                g2.color = Theme.success
                g2.fillRoundRect(0, 0, fillW, height, r, r)
            }
            if (animate && ratio < 1f) {
                val shimmerW = (width * 0.04f).toInt().coerceAtLeast(JBUI.scale(6))
                val x = (fillW + (width - fillW) * shimmerOffset).toInt()
                val color = Theme.success
                val transparent = Color(color.red, color.green, color.blue, 0)
                val opaque = Color(color.red, color.green, color.blue, 180)
                val gradient = LinearGradientPaint(
                    x.toFloat(), 0f,
                    (x + shimmerW).toFloat(), 0f,
                    floatArrayOf(0f, 0.5f, 1f),
                    arrayOf(transparent, opaque, transparent),
                )
                g2.paint = gradient
                g2.fillRoundRect(x, 0, shimmerW, height, r, r)
            }
        } finally {
            g2.dispose()
        }
    }
}
