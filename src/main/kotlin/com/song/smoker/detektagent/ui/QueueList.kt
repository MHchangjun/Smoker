package com.song.smoker.detektagent.ui

import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.song.smoker.detektagent.model.QueuedTask
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants

class QueueList : JBPanel<QueueList>(BorderLayout()) {

    private val itemsPanel = JPanel().apply {
        isOpaque = false
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
    }

    private val countBadge = CountBadge("0").apply { active = true }
    private val sectionLabel = JBLabel("UP NEXT").apply {
        font = Theme.uiBold(9).deriveFont(mapOf(java.awt.font.TextAttribute.TRACKING to 0.06))
        foreground = Theme.fgMuted
    }
    private val sortLabel = JBLabel("grouped by rule").apply {
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
            add(sortLabel, BorderLayout.EAST)
        }
    }

    fun render(queue: List<QueuedTask>) {
        countBadge.setValue(queue.size.toString())
        itemsPanel.removeAll()
        if (queue.isEmpty()) {
            itemsPanel.add(JBLabel("Queue is empty").apply {
                font = Theme.ui(11)
                foreground = Theme.fgMuted
                border = JBUI.Borders.empty(20, 14)
                alignmentX = LEFT_ALIGNMENT
            })
        } else {
            queue.forEach { itemsPanel.add(QueueRow(it)) }
        }
        itemsPanel.add(Box.createVerticalGlue())
        itemsPanel.revalidate()
        itemsPanel.repaint()
    }
}

internal class CountBadge(initial: String) : JBPanel<CountBadge>() {
    var active: Boolean = false
        set(value) {
            field = value
            repaint()
        }

    private val label = JBLabel(initial).apply {
        font = Theme.mono(9)
        foreground = Theme.fgMuted
    }

    init {
        isOpaque = false
        border = JBUI.Borders.empty(1, 6)
        add(label)
    }

    fun setValue(text: String) {
        label.text = text
        if (active) label.foreground = Theme.accent
        repaint()
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val r = height
            g2.color = if (active) Theme.accentSoft else Theme.bgElevated
            g2.fillRoundRect(0, 0, width - 1, height - 1, r, r)
        } finally {
            g2.dispose()
        }
        super.paintComponent(g)
    }
}

private class QueueRow(task: QueuedTask) : JBPanel<QueueRow>(BorderLayout()) {
    private var hover = false

    init {
        isOpaque = false
        border = JBUI.Borders.empty(6, 12)

        val positionChip = PositionChip(task.position.toString())

        val ruleLabel = JBLabel(task.ruleId).apply {
            font = Theme.mono(10)
            foreground = Theme.purple
        }
        val pathLabel = JBLabel(truncMiddle(task.filePath, 42)).apply {
            font = Theme.mono(11)
            foreground = Theme.fg
        }
        val center = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(0, 8, 0, 8)
            pathLabel.alignmentX = LEFT_ALIGNMENT
            ruleLabel.alignmentX = LEFT_ALIGNMENT
            add(pathLabel)
            add(ruleLabel)
        }

        val eta = JBLabel("~${task.etaSeconds}s").apply {
            font = Theme.mono(10)
            foreground = Theme.fgMuted
        }

        add(positionChip, BorderLayout.WEST)
        add(center, BorderLayout.CENTER)
        add(eta, BorderLayout.EAST)

        addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) { hover = true; repaint() }
            override fun mouseExited(e: MouseEvent) { hover = false; repaint() }
        })
    }

    override fun paintComponent(g: Graphics) {
        if (hover) {
            val g2 = g.create() as Graphics2D
            try {
                g2.color = Theme.bgHover
                g2.fillRect(0, 0, width, height)
            } finally {
                g2.dispose()
            }
        }
        super.paintComponent(g)
    }

    override fun getMaximumSize(): Dimension {
        val pref = preferredSize
        return Dimension(Int.MAX_VALUE, pref.height)
    }

    private fun truncMiddle(s: String, max: Int): String {
        if (s.length <= max) return s
        val keep = max - 1
        val front = keep / 2
        val back = keep - front
        return s.take(front) + "…" + s.takeLast(back)
    }
}

private class PositionChip(text: String) : JBPanel<PositionChip>() {
    private val label = JBLabel(text).apply {
        font = Theme.mono(10)
        foreground = Theme.fgSecondary
        horizontalAlignment = javax.swing.SwingConstants.CENTER
    }

    init {
        isOpaque = false
        layout = BorderLayout()
        add(label, BorderLayout.CENTER)
        val s = JBUI.scale(18)
        preferredSize = Dimension(s, s)
        minimumSize = preferredSize
        maximumSize = preferredSize
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val r = JBUI.scale(4)
            g2.color = Theme.bgElevated
            g2.fillRoundRect(0, 0, width - 1, height - 1, r, r)
        } finally {
            g2.dispose()
        }
        super.paintComponent(g)
    }
}
