package com.song.smoker.detektagent.ui

import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import com.song.smoker.detektagent.model.NowTask
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.Timer

class NowCard : JBPanel<NowCard>(BorderLayout()) {
    override fun getMaximumSize(): Dimension =
        Dimension(Int.MAX_VALUE, preferredSize.height)


    private val elapsedLabel = JBLabel("⏱ 00:00s").apply {
        font = Theme.mono(11)
        foreground = Theme.fgMuted
    }
    private var taskStartedAtMs: Long = 0L
    private val tickTimer = Timer(1000) {
        if (taskStartedAtMs > 0L) {
            elapsedLabel.text = "⏱ ${formatElapsed(System.currentTimeMillis() - taskStartedAtMs)}"
        }
    }
    private val ruleChip = RuleChip()
    private val ruleGroupLabel = JBLabel(" ").apply {
        font = Theme.ui(10)
        foreground = Theme.fgMuted
    }
    private val pathLabel = JBLabel(" ").apply {
        font = Theme.mono(11)
        foreground = Theme.fg
    }
    private val thinkingBox = ThinkingBox()
    private val progressBar = AccentProgressBar()
    private val stepLabel = JBLabel("step 0 / 0").apply {
        font = Theme.mono(10)
        foreground = Theme.fgMuted
    }
    private val percentLabel = JBLabel("0%").apply {
        font = Theme.mono(10)
        foreground = Theme.fgMuted
    }
    private val readingLabel = JBLabel(" ").apply {
        font = Theme.mono(11)
        foreground = Theme.fgMuted
    }
    private val readingTag = JBLabel("↳ reading").apply {
        font = Theme.mono(11)
        foreground = Theme.accent
    }

    init {
        isOpaque = false
        border = JBUI.Borders.empty(12)
        add(buildBody(), BorderLayout.CENTER)
    }

    override fun removeNotify() {
        tickTimer.stop()
        super.removeNotify()
    }

    fun render(now: NowTask?) {
        isVisible = now != null
        if (now == null) {
            taskStartedAtMs = 0L
            tickTimer.stop()
            return
        }
        taskStartedAtMs = System.currentTimeMillis() - now.elapsedMs
        elapsedLabel.text = "⏱ ${formatElapsed(now.elapsedMs)}"
        if (!tickTimer.isRunning) tickTimer.start()
        ruleChip.setRule(now.ruleId)
        ruleGroupLabel.text = now.ruleGroup
        pathLabel.text = truncMiddle(now.filePath, 56) + ":" + now.lineNumber
        thinkingBox.setStep(now.step.tag, now.step.text)
        progressBar.setPercent(now.progressPercent)
        stepLabel.text = "step ${now.stepIndex} / ${now.stepTotal}"
        percentLabel.text = "${now.progressPercent}%"
        readingLabel.text = now.readingNow?.let { truncMiddle(it, 50) } ?: " "
        revalidate()
        repaint()
    }

    private fun buildBody(): JComponent {
        val card = JPanel(BorderLayout()).apply {
            isOpaque = false
        }

        val head = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(8, 10, 6, 12)
            val label = JBLabel("NOW WORKING").apply {
                font = Theme.uiBold(9).deriveFont(mapOf(java.awt.font.TextAttribute.TRACKING to 0.06))
                foreground = Theme.fgMuted
            }
            add(label, BorderLayout.WEST)
            add(elapsedLabel, BorderLayout.EAST)
        }

        val divider = JPanel().apply {
            background = Theme.borderSoft
            preferredSize = Dimension(0, JBUI.scale(1))
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(1))
        }

        val ruleRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
            isOpaque = false
            border = JBUI.Borders.empty(0, 0, 4, 0)
            add(ruleChip)
            add(ruleGroupLabel)
        }

        val progressRow = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            border = JBUI.Borders.empty(8, 0, 0, 0)
            stepLabel.alignmentY = CENTER_ALIGNMENT
            percentLabel.alignmentY = CENTER_ALIGNMENT
            progressBar.alignmentY = CENTER_ALIGNMENT
            add(stepLabel)
            add(Box.createHorizontalStrut(JBUI.scale(8)))
            add(progressBar)
            add(Box.createHorizontalStrut(JBUI.scale(8)))
            add(percentLabel)
        }

        val readingRow = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            border = JBUI.Borders.empty(8, 0, 0, 0)
            readingTag.alignmentY = CENTER_ALIGNMENT
            readingLabel.alignmentY = CENTER_ALIGNMENT
            add(readingTag)
            add(Box.createHorizontalStrut(JBUI.scale(6)))
            add(readingLabel)
            add(Box.createHorizontalGlue())
        }

        val body = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(10, 12, 12, 12)
            ruleRow.alignmentX = LEFT_ALIGNMENT
            pathLabel.alignmentX = LEFT_ALIGNMENT
            thinkingBox.alignmentX = LEFT_ALIGNMENT
            progressRow.alignmentX = LEFT_ALIGNMENT
            readingRow.alignmentX = LEFT_ALIGNMENT
            add(ruleRow)
            add(Box.createVerticalStrut(JBUI.scale(2)))
            add(pathLabel)
            add(Box.createVerticalStrut(JBUI.scale(8)))
            add(thinkingBox)
            add(progressRow)
            add(readingRow)
        }

        card.add(head, BorderLayout.NORTH)
        val center = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(divider, BorderLayout.NORTH)
            add(body, BorderLayout.CENTER)
        }
        card.add(center, BorderLayout.CENTER)
        return CardWrapper(card)
    }

    private fun formatElapsed(ms: Long): String {
        val totalSec = (ms / 1000).toInt().coerceAtLeast(0)
        val mm = (totalSec / 60).toString().padStart(2, '0')
        val ss = (totalSec % 60).toString().padStart(2, '0')
        return "$mm:${ss}s"
    }

    private fun truncMiddle(s: String, max: Int): String {
        if (s.length <= max) return s
        val keep = max - 1
        val front = keep / 2
        val back = keep - front
        return s.take(front) + "…" + s.takeLast(back)
    }
}

private class CardWrapper(inner: JComponent) : JBPanel<CardWrapper>(BorderLayout()) {
    init {
        isOpaque = false
        add(inner, BorderLayout.CENTER)
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val r = JBUI.scale(6)
            g2.color = Theme.bgEditor
            g2.fillRoundRect(0, 0, width - 1, height - 1, r, r)
            g2.color = Theme.borderSoft
            g2.drawRoundRect(0, 0, width - 1, height - 1, r, r)
            // accent stripe with glow on left edge
            val stripeW = JBUI.scale(2)
            val glowColor = Theme.accentGlow
            g2.color = Color(glowColor.red, glowColor.green, glowColor.blue, 130)
            g2.fillRect(0, JBUI.scale(2), stripeW + JBUI.scale(2), height - JBUI.scale(4))
            g2.color = Theme.accent
            g2.fillRect(0, JBUI.scale(2), stripeW, height - JBUI.scale(4))
        } finally {
            g2.dispose()
        }
        super.paintComponent(g)
    }
}

private class RuleChip : JBPanel<RuleChip>(FlowLayout(FlowLayout.LEFT, JBUI.scale(5), 0)) {
    private val dot = HeartbeatDot(dotPx = 5, haloPx = 0).apply {
        animated = false
        color = Theme.purple
    }
    private val ruleLabel = JBLabel(" ").apply {
        font = Theme.mono(11)
        foreground = Theme.purple
    }

    init {
        isOpaque = false
        border = JBUI.Borders.empty(2, 6)
        add(dot)
        add(ruleLabel)
    }

    fun setRule(name: String) {
        ruleLabel.text = name
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val r = JBUI.scale(3)
            g2.color = Theme.purpleSoft
            g2.fillRoundRect(0, 0, width - 1, height - 1, r, r)
            g2.color = Theme.purpleBorder
            g2.drawRoundRect(0, 0, width - 1, height - 1, r, r)
        } finally {
            g2.dispose()
        }
        super.paintComponent(g)
    }
}

private class ThinkingBox : JBPanel<ThinkingBox>() {
    private val tagLabel = JBLabel("[plan]").apply {
        font = Theme.mono(11).deriveFont(java.awt.Font.BOLD)
        foreground = Theme.accent
    }
    private val textLabel = JBLabel(" ").apply {
        font = Theme.mono(11)
        foreground = Theme.fg
    }
    private val cursor = BlinkingCursor().apply { color = Theme.accent }

    init {
        isOpaque = false
        layout = FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)
        border = JBUI.Borders.empty(8, 10)
        add(tagLabel)
        add(textLabel)
        add(cursor)
    }

    fun setStep(tag: String, text: String) {
        tagLabel.text = "[$tag]"
        textLabel.text = text
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val r = JBUI.scale(4)
            g2.color = Color(0, 0, 0, 46)
            g2.fillRoundRect(0, 0, width - 1, height - 1, r, r)
            g2.color = Theme.accent
            g2.fillRect(0, JBUI.scale(2), JBUI.scale(2), height - JBUI.scale(4))
        } finally {
            g2.dispose()
        }
        super.paintComponent(g)
    }

    override fun getMaximumSize(): Dimension {
        val pref = preferredSize
        return Dimension(Int.MAX_VALUE, pref.height)
    }
}

private class AccentProgressBar : JComponent() {
    private var target = 0
    private var displayed = 0f
    private val timer = Timer(16) {
        val delta = target - displayed
        if (kotlin.math.abs(delta) < 0.5f) {
            displayed = target.toFloat()
            (it.source as Timer).stop()
        } else {
            displayed += delta * 0.18f
        }
        repaint()
    }

    init {
        preferredSize = Dimension(JBUI.scale(140), JBUI.scale(4))
        minimumSize = Dimension(JBUI.scale(60), JBUI.scale(4))
        maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(4))
        isOpaque = false
    }

    fun setPercent(percent: Int) {
        target = percent.coerceIn(0, 100)
        timer.stop()
        timer.start()
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val r = JBUI.scale(2)
            g2.color = Theme.borderSoft
            g2.fillRoundRect(0, 0, width, height, r, r)
            val fillW = (width * (displayed / 100f)).toInt()
            if (fillW > 0) {
                val glow = Theme.accentGlow
                g2.color = Color(glow.red, glow.green, glow.blue, 80)
                g2.fillRoundRect(0, 0, fillW, height, r, r)
                g2.color = Theme.accent
                g2.fillRoundRect(0, 0, fillW, height, r, r)
            }
        } finally {
            g2.dispose()
        }
    }
}

@Suppress("unused")
private fun spacer(w: Int = 0, h: Int = 0): JComponent = JBPanel<JBPanel<*>>().apply {
    isOpaque = false
    preferredSize = Dimension(w, h)
    maximumSize = Dimension(if (w == 0) Int.MAX_VALUE else w, if (h == 0) Int.MAX_VALUE else h)
}
