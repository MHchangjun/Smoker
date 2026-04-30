package com.song.smoker.detektagent.ui

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.song.smoker.detektagent.model.FeedEvent
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.time.Duration
import java.time.Instant
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants

class ActivityFeed : JBPanel<ActivityFeed>(BorderLayout()) {

    private val itemsPanel = JPanel().apply {
        isOpaque = false
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
    }

    private val scroll = JBScrollPane(itemsPanel).apply {
        border = JBUI.Borders.empty()
        viewport.isOpaque = false
        isOpaque = false
        horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
    }

    init {
        isOpaque = false
        add(scroll, BorderLayout.CENTER)
    }

    fun render(events: List<FeedEvent>) {
        itemsPanel.removeAll()
        events.forEachIndexed { index, ev ->
            itemsPanel.add(FeedItem(ev, isFirst = index == 0, isLast = index == events.lastIndex))
        }
        if (events.isEmpty()) {
            itemsPanel.add(emptyState())
        }
        itemsPanel.add(Box.createVerticalGlue())
        itemsPanel.revalidate()
        itemsPanel.repaint()
    }

    private fun emptyState(): JComponent = JBLabel("No activity yet").apply {
        font = Theme.ui(11)
        foreground = Theme.fgMuted
        border = JBUI.Borders.empty(20, 14)
        alignmentX = LEFT_ALIGNMENT
    }
}

private enum class FeedKind(val color: JBColor, val filled: Boolean) {
    RUN(Theme.accent, false),
    BATCH(Theme.accent, true),
    FIX(Theme.success, true),
    SKIP(Theme.warn, true),
    ERROR(Theme.error, true),
    DONE(Theme.success, true),
    DONE_FAIL(Theme.error, true);
}

private class FeedItem(
    private val event: FeedEvent,
    private val isFirst: Boolean,
    private val isLast: Boolean,
) : JBPanel<FeedItem>() {

    private val kind = kindOf(event)
    private var hover = false

    init {
        isOpaque = false
        layout = GridBagLayout()
        border = JBUI.Borders.empty(6, 8, 6, 14)
        cursor = Cursor.getDefaultCursor()

        val railSlot = JBPanel<JBPanel<*>>().apply {
            isOpaque = false
            preferredSize = Dimension(JBUI.scale(26), 0)
            minimumSize = Dimension(JBUI.scale(26), 0)
        }

        val body = buildBody()

        val cRail = GridBagConstraints().apply {
            gridx = 0; gridy = 0
            anchor = GridBagConstraints.NORTHWEST
            fill = GridBagConstraints.VERTICAL
            weighty = 1.0
        }
        val cBody = GridBagConstraints().apply {
            gridx = 1; gridy = 0
            anchor = GridBagConstraints.NORTHWEST
            fill = GridBagConstraints.HORIZONTAL
            weightx = 1.0
        }
        add(railSlot, cRail)
        add(body, cBody)

        addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) { hover = true; repaint() }
            override fun mouseExited(e: MouseEvent) { hover = false; repaint() }
        })
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            if (hover) {
                g2.color = Theme.bgHover
                g2.fillRect(0, 0, width, height)
            }
            // rail line
            val railCx = JBUI.scale(8) + JBUI.scale(13)
            g2.color = Theme.borderSoft
            val markerY = JBUI.scale(14)
            val top = if (isFirst) markerY else 0
            val bottom = if (isLast) markerY else height
            g2.fillRect(railCx - JBUI.scale(1) / 2, top, 1, bottom - top)
            // marker
            val mSize = JBUI.scale(14)
            val mX = railCx - mSize / 2
            val mY = markerY - mSize / 2
            g2.color = Theme.bg
            g2.fillOval(mX, mY, mSize, mSize)
            g2.color = kind.color
            g2.stroke = java.awt.BasicStroke(JBUI.scale(2).toFloat())
            g2.drawOval(mX, mY, mSize, mSize)
            if (kind.filled) {
                val inner = JBUI.scale(6)
                g2.color = kind.color
                g2.fillOval(railCx - inner / 2, markerY - inner / 2, inner, inner)
            }
        } finally {
            g2.dispose()
        }
        super.paintComponent(g)
    }

    private fun buildBody(): JComponent {
        val body = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
        }
        body.add(buildRow1())
        buildRow2()?.let { body.add(it) }
        buildDiff()?.let {
            body.add(Box.createVerticalStrut(JBUI.scale(6)))
            body.add(it)
        }
        buildExtras()?.let {
            body.add(Box.createVerticalStrut(JBUI.scale(4)))
            body.add(it)
        }
        return body
    }

    private fun buildRow1(): JComponent {
        val verb = verbFor(event)
        val verbLabel = JBLabel(verb).apply {
            font = Theme.uiBold(11)
            foreground = kind.color
        }
        val ts = JBLabel(formatTimestamp(event.timestamp)).apply {
            font = Theme.mono(10)
            foreground = Theme.fgMuted
        }
        val left = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
            isOpaque = false
            border = JBUI.Borders.empty()
            add(verbLabel)
            ruleChipFor(event)?.let { add(it) }
        }
        return JPanel(BorderLayout()).apply {
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
            add(left, BorderLayout.WEST)
            add(ts, BorderLayout.EAST)
        }
    }

    private fun buildRow2(): JComponent? {
        return when (val ev = event) {
            is FeedEvent.RunStarted -> simpleLine(ev.projectName, Theme.fg)
            is FeedEvent.RuleBatchStarted -> simpleLine("${ev.fileCount} files queued", Theme.fg)
            is FeedEvent.FileFixed -> pathLine(ev.filePath, ev.line.takeIf { it > 0 }, ev.summary)
            is FeedEvent.FileSkipped -> pathLine(ev.filePath, null, ev.reason)
            is FeedEvent.ToolError -> simpleLine(ev.message, Theme.fgMuted)
            is FeedEvent.RunFinished -> simpleLine(
                ev.failure ?: "${ev.fixedCount} fixes",
                if (ev.failure != null) Theme.error else Theme.fg,
            )
        }
    }

    private fun simpleLine(text: String, fg: JBColor): JComponent =
        JBLabel(text).apply {
            font = Theme.mono(11)
            foreground = fg
            alignmentX = LEFT_ALIGNMENT
            border = JBUI.Borders.emptyTop(2)
        }

    private fun pathLine(filePath: String, line: Int?, description: String?): JComponent {
        val row = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            alignmentX = LEFT_ALIGNMENT
            border = JBUI.Borders.emptyTop(2)
        }
        val pathLabel = JBLabel(truncMiddle(filePath, 44)).apply {
            font = Theme.mono(11)
            foreground = Theme.fg
        }
        row.add(pathLabel)
        if (line != null) {
            val lineLabel = JBLabel(":$line").apply {
                font = Theme.mono(11)
                foreground = Theme.accent
            }
            row.add(lineLabel)
        }
        if (!description.isNullOrBlank()) {
            row.add(Box.createHorizontalStrut(JBUI.scale(6)))
            row.add(JBLabel("— $description").apply {
                font = Theme.mono(11)
                foreground = Theme.fgMuted
            })
        }
        row.add(Box.createHorizontalGlue())
        return row
    }

    private fun buildDiff(): JComponent? {
        val ev = event as? FeedEvent.FileFixed ?: return null
        if (ev.diffRemoved.isEmpty() && ev.diffAdded.isEmpty()) return null
        return DiffBlock(ev.diffRemoved, ev.diffAdded)
    }

    private fun buildExtras(): JComponent? {
        val pills = mutableListOf<JComponent>()
        when (val ev = event) {
            is FeedEvent.FileFixed -> {
                pills += extraPill(formatDuration(ev.durationMs), Theme.fgMuted, Theme.bgElevated)
                ev.commitSha?.takeIf { it.isNotBlank() }?.let {
                    pills += extraPill(it.take(7), Theme.purple, Theme.purpleSoft)
                }
            }
            is FeedEvent.FileSkipped -> {
                pills += extraPill(formatDuration(ev.durationMs), Theme.fgMuted, Theme.bgElevated)
            }
            is FeedEvent.RunFinished -> {
                pills += extraPill(formatDuration(ev.durationMs), Theme.fgMuted, Theme.bgElevated)
                if (ev.failure == null && ev.fixedCount > 0) {
                    pills += extraPill("${ev.fixedCount} fixed", Theme.success, Theme.successSoft)
                }
            }
            is FeedEvent.RuleBatchStarted, is FeedEvent.RunStarted, is FeedEvent.ToolError -> {}
        }
        if (pills.isEmpty()) return null
        return JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
            pills.forEach { add(it) }
        }
    }

    private fun formatDuration(ms: Long): String {
        val seconds = (ms / 1000).coerceAtLeast(0)
        return when {
            seconds < 60 -> "${seconds}s"
            seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
            else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
        }
    }

    private fun extraPill(text: String, fg: JBColor, bg: JBColor): JComponent =
        ExtraPill(text, fg, bg)

    private fun ruleChipFor(ev: FeedEvent): JComponent? {
        val ruleId = when (ev) {
            is FeedEvent.RuleBatchStarted -> ev.ruleId
            is FeedEvent.FileFixed -> ev.ruleId
            is FeedEvent.FileSkipped -> ev.ruleId
            else -> null
        } ?: return null
        return MiniRuleChip(ruleId)
    }

    private fun kindOf(ev: FeedEvent): FeedKind = when (ev) {
        is FeedEvent.RunStarted -> FeedKind.RUN
        is FeedEvent.RuleBatchStarted -> FeedKind.BATCH
        is FeedEvent.FileFixed -> FeedKind.FIX
        is FeedEvent.FileSkipped -> FeedKind.SKIP
        is FeedEvent.ToolError -> FeedKind.ERROR
        is FeedEvent.RunFinished -> if (ev.failure == null) FeedKind.DONE else FeedKind.DONE_FAIL
    }

    private fun verbFor(ev: FeedEvent): String = when (ev) {
        is FeedEvent.RunStarted -> "Run started"
        is FeedEvent.RuleBatchStarted -> "Rule batch"
        is FeedEvent.FileFixed -> "Fixed"
        is FeedEvent.FileSkipped -> "Skipped"
        is FeedEvent.ToolError -> "Tool error"
        is FeedEvent.RunFinished -> if (ev.failure == null) "Run finished" else "Run failed"
    }

    private fun truncMiddle(s: String, max: Int): String {
        if (s.length <= max) return s
        val keep = max - 1
        val front = keep / 2
        val back = keep - front
        return s.take(front) + "…" + s.takeLast(back)
    }

    private fun formatTimestamp(ts: Instant): String {
        val sec = Duration.between(ts, Instant.now()).seconds.coerceAtLeast(0)
        return when {
            sec < 60 -> "${sec}s"
            sec < 3600 -> "${sec / 60}m"
            sec < 86400 -> "${sec / 3600}h"
            else -> "${sec / 86400}d"
        }
    }

    override fun getMaximumSize(): Dimension {
        val pref = preferredSize
        return Dimension(Int.MAX_VALUE, pref.height)
    }
}

private class MiniRuleChip(name: String) : JBPanel<MiniRuleChip>(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)) {
    init {
        isOpaque = false
        border = JBUI.Borders.empty(1, 6)
        add(JBLabel(name).apply {
            font = Theme.mono(10)
            foreground = Theme.purple
        })
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

private class ExtraPill(text: String, fg: JBColor, private val bg: JBColor) : JBPanel<ExtraPill>(FlowLayout(FlowLayout.LEFT, 0, 0)) {
    init {
        isOpaque = false
        border = JBUI.Borders.empty(1, 6)
        add(JBLabel(text).apply {
            font = Theme.mono(10)
            foreground = fg
        })
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val r = JBUI.scale(3)
            g2.color = bg
            g2.fillRoundRect(0, 0, width - 1, height - 1, r, r)
        } finally {
            g2.dispose()
        }
        super.paintComponent(g)
    }
}

private class DiffBlock(
    private val removed: List<String>,
    private val added: List<String>,
) : JBPanel<DiffBlock>() {
    init {
        isOpaque = false
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        alignmentX = LEFT_ALIGNMENT
        border = JBUI.Borders.empty(6, 8)
        removed.forEach { add(line("−", it, Theme.error, true)) }
        added.forEach { add(line("+", it, Theme.success, false)) }
    }

    private fun line(prefix: String, text: String, fg: JBColor, isRemoved: Boolean): JComponent {
        val bg: Color = if (isRemoved) Color(226, 107, 107, 36) else Color(95, 184, 101, 36)
        return JPanel(BorderLayout()).apply {
            isOpaque = true
            background = bg
            border = JBUI.Borders.empty(1, 4)
            alignmentX = LEFT_ALIGNMENT
            val label = JBLabel("$prefix $text").apply {
                font = Theme.mono(10)
                foreground = fg
            }
            add(label, BorderLayout.WEST)
        }
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val r = JBUI.scale(4)
            g2.color = Color(0, 0, 0, 56)
            g2.fillRoundRect(0, 0, width - 1, height - 1, r, r)
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
