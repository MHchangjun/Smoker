package com.song.smoker.detektagent.ui

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import com.song.smoker.detektagent.model.AgentSnapshot
import com.song.smoker.detektagent.model.AgentStatus
import com.song.smoker.detektagent.model.TodayStats
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.GradientPaint
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridLayout
import java.awt.RenderingHints
import java.text.NumberFormat
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingConstants
import javax.swing.Timer

class AgentHeader : JBPanel<AgentHeader>(BorderLayout()) {
    override fun getMaximumSize(): Dimension =
        Dimension(Int.MAX_VALUE, preferredSize.height)


    private val avatar = AvatarTile()
    private val nameLabel = JBLabel("detekt-fixer").apply {
        font = Theme.uiBold(13)
        foreground = Theme.fg
    }
    private val sublineLabel = JBLabel(" ").apply {
        font = Theme.mono(11)
        foreground = Theme.fgMuted
    }
    private val statusPill = StatusPill()

    private val fixedValue = monoValue("0")
    private val remainingValue = monoValue("0")
    private val etaValue = monoValue("--")

    init {
        isOpaque = false
        border = JBUI.Borders.empty(14, 14, 12, 14)
        add(buildIdentityRow(), BorderLayout.NORTH)
        add(buildStatStrip(), BorderLayout.SOUTH)
    }

    fun render(snapshot: AgentSnapshot) {
        nameLabel.text = snapshot.identity.name
        sublineLabel.text =
            "${snapshot.identity.version} · ${snapshot.identity.uptime} · cycle #${formatThousands(snapshot.identity.cycleNumber)}"
        statusPill.setStatus(snapshot.status)
        renderStats(snapshot.stats)
    }

    private fun renderStats(stats: TodayStats) {
        fixedValue.text = formatThousands(stats.fixedToday)
        fixedValue.foreground = Theme.success
        remainingValue.text = formatThousands(stats.remaining)
        remainingValue.foreground = Theme.fg
        etaValue.text = stats.etaClear
        etaValue.foreground = Theme.accent
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.paint = GradientPaint(0f, 0f, Theme.headerGradTop, 0f, height.toFloat(), Theme.headerGradBottom)
            g2.fillRect(0, 0, width, height)
            g2.color = Theme.border
            g2.fillRect(0, height - 1, width, 1)
        } finally {
            g2.dispose()
        }
        super.paintComponent(g)
    }

    private fun buildIdentityRow(): JComponent {
        val nameBlock = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            nameLabel.alignmentX = LEFT_ALIGNMENT
            sublineLabel.alignmentX = LEFT_ALIGNMENT
            add(nameLabel)
            add(Box.createVerticalStrut(JBUI.scale(2)))
            add(sublineLabel)
        }

        val left = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(avatar)
            add(Box.createHorizontalStrut(JBUI.scale(10)))
            add(nameBlock)
        }

        return JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.emptyBottom(10)
            add(left, BorderLayout.WEST)
            add(statusPill, BorderLayout.EAST)
        }
    }

    private fun buildStatStrip(): JComponent {
        return JPanel(GridLayout(1, 3, JBUI.scale(8), 0)).apply {
            isOpaque = false
            add(StatCell("FIXED TODAY", fixedValue))
            add(StatCell("REMAINING", remainingValue))
            add(StatCell("ETA CLEAR", etaValue))
        }
    }

    private fun monoValue(initial: String): JBLabel = JBLabel(initial).apply {
        font = Theme.mono(13)
        foreground = Theme.fg
    }

    private fun formatThousands(n: Int): String =
        NumberFormat.getIntegerInstance().format(n.toLong())
}

private class StatCell(label: String, valueLabel: JBLabel) : JBPanel<StatCell>() {
    init {
        isOpaque = false
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.empty(7, 8)

        val labelLine = JBLabel(label).apply {
            font = Theme.uiBold(9).deriveFont(mapOf(java.awt.font.TextAttribute.TRACKING to 0.06))
            foreground = Theme.fgMuted
            alignmentX = LEFT_ALIGNMENT
        }
        valueLabel.alignmentX = LEFT_ALIGNMENT
        add(labelLine)
        add(Box.createVerticalStrut(JBUI.scale(2)))
        add(valueLabel)
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val r = JBUI.scale(4)
            g2.color = Theme.bgEditor
            g2.fillRoundRect(0, 0, width - 1, height - 1, r, r)
            g2.color = Theme.borderSoft
            g2.drawRoundRect(0, 0, width - 1, height - 1, r, r)
        } finally {
            g2.dispose()
        }
        super.paintComponent(g)
    }
}

private class AvatarTile : JComponent() {
    private val sizePx = 30
    private var ringPhase = 0f
    private val frameMs = 33
    private val cycleMs = 2400
    private val timer = Timer(frameMs) {
        ringPhase = (ringPhase + frameMs.toFloat() / cycleMs) % 1f
        repaint()
    }

    init {
        val s = JBUI.scale(sizePx + 8)
        preferredSize = Dimension(s, s)
        minimumSize = preferredSize
        isOpaque = false
    }

    override fun addNotify() {
        super.addNotify()
        timer.start()
    }

    override fun removeNotify() {
        timer.stop()
        super.removeNotify()
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            val s = JBUI.scale(sizePx)
            val cx = width / 2
            val cy = height / 2
            val ax = cx - s / 2
            val ay = cy - s / 2
            val radius = JBUI.scale(7)

            // Outer animated ring
            val scale = 1f + ringPhase * 0.12f
            val alphaF = (0.5f * (1f - ringPhase)).coerceIn(0f, 1f)
            val ringSize = (s * scale).toInt() + JBUI.scale(6)
            val ringRadius = JBUI.scale(9)
            val ringColor = Theme.avatarRing
            g2.color = Color(ringColor.red, ringColor.green, ringColor.blue, (alphaF * 255).toInt())
            g2.stroke = java.awt.BasicStroke(JBUI.scale(1).toFloat())
            g2.drawRoundRect(cx - ringSize / 2, cy - ringSize / 2, ringSize, ringSize, ringRadius, ringRadius)

            // Avatar body
            g2.paint = GradientPaint(
                ax.toFloat(), ay.toFloat(), Theme.avatarTop,
                ax.toFloat(), (ay + s).toFloat(), Theme.avatarBottom,
            )
            g2.fillRoundRect(ax, ay, s, s, radius, radius)

            // Glyph
            g2.color = Color(0xFF, 0xFF, 0xFF, 220)
            g2.font = Theme.mono(15).deriveFont(Font.BOLD)
            val fm = g2.fontMetrics
            val glyph = "D"
            val tx = cx - fm.stringWidth(glyph) / 2
            val ty = cy - fm.height / 2 + fm.ascent
            g2.drawString(glyph, tx, ty)
        } finally {
            g2.dispose()
        }
    }
}

private class StatusPill : JBPanel<StatusPill>() {

    private val dot = HeartbeatDot(dotPx = 6, haloPx = 5)
    private val text = JBLabel("Active · Fixing").apply {
        font = Theme.ui(11)
        foreground = Theme.success
        horizontalAlignment = SwingConstants.LEFT
    }

    private var fillColor: Color = Theme.successSoft
    private var textColor: JBColor = Theme.success

    init {
        isOpaque = false
        border = JBUI.Borders.empty(3, 9)
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        add(dot)
        add(JBPanel<JBPanel<*>>().apply {
            isOpaque = false
            preferredSize = Dimension(JBUI.scale(6), 0)
            maximumSize = Dimension(JBUI.scale(6), Int.MAX_VALUE)
        })
        add(text)
    }

    fun setStatus(status: AgentStatus) {
        when (status) {
            AgentStatus.FIXING -> apply("Active · Fixing", Theme.success, Theme.successSoft, true)
            AgentStatus.SCANNING -> apply("Active · Scanning", Theme.accent, Theme.accentSoft, true)
            AgentStatus.AWAITING_VERIFY -> apply("Awaiting Verify", Theme.warn, Theme.warnSoft, true)
            AgentStatus.SLEEPING -> apply("Sleeping · 4m", Theme.fgMuted, Theme.bgElevated, false)
        }
    }

    private fun apply(label: String, fg: JBColor, bg: JBColor, animated: Boolean) {
        text.text = label
        text.foreground = fg
        textColor = fg
        fillColor = bg
        dot.color = fg
        dot.animated = animated
        repaint()
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = fillColor
            g2.fillRoundRect(0, 0, width, height, height, height)
        } finally {
            g2.dispose()
        }
        super.paintComponent(g)
    }
}
