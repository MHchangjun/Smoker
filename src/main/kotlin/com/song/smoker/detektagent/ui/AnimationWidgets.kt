package com.song.smoker.detektagent.ui

import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.JComponent
import javax.swing.Timer

class HeartbeatDot(
    private val dotPx: Int = 6,
    private val haloPx: Int = 6,
) : JComponent() {

    var color: Color = Color.GRAY
        set(value) {
            field = value
            repaint()
        }

    var animated: Boolean = true
        set(value) {
            field = value
            if (value) timer.start() else timer.stop()
            repaint()
        }

    private var phase = 0f
    private val frameMs = 33
    private val cycleMs = 1600
    private val timer = Timer(frameMs) {
        phase = ((phase + frameMs.toFloat() / cycleMs) % 1f)
        repaint()
    }

    init {
        val total = JBUI.scale(dotPx + haloPx * 2)
        preferredSize = Dimension(total, total)
        minimumSize = preferredSize
        isOpaque = false
    }

    override fun addNotify() {
        super.addNotify()
        if (animated) timer.start()
    }

    override fun removeNotify() {
        timer.stop()
        super.removeNotify()
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val cx = width / 2
            val cy = height / 2
            val dot = JBUI.scale(dotPx)
            val haloMax = JBUI.scale(haloPx)
            if (animated) {
                val haloRadius = (dot / 2f) + (phase * haloMax)
                val alphaF = (1f - phase).coerceIn(0f, 1f)
                val alpha = (alphaF * color.alpha.coerceAtLeast(180) * 0.45f).toInt().coerceIn(0, 255)
                g2.color = Color(color.red, color.green, color.blue, alpha)
                g2.fillOval(
                    (cx - haloRadius).toInt(),
                    (cy - haloRadius).toInt(),
                    (haloRadius * 2).toInt(),
                    (haloRadius * 2).toInt(),
                )
            }
            g2.color = color
            g2.fillOval(cx - dot / 2, cy - dot / 2, dot, dot)
        } finally {
            g2.dispose()
        }
    }
}

class CycleRing(
    private val sizePx: Int = 8,
    private val strokePx: Float = 1.5f,
) : JComponent() {

    var color: Color = Color.GRAY
        set(value) {
            field = value
            repaint()
        }

    var animated: Boolean = true
        set(value) {
            field = value
            if (value) timer.start() else timer.stop()
            repaint()
        }

    private var angleDeg = 0f
    private val frameMs = 33
    private val cycleMs = 1400
    private val timer = Timer(frameMs) {
        angleDeg = (angleDeg + 360f * frameMs / cycleMs) % 360f
        repaint()
    }

    init {
        val total = JBUI.scale(sizePx + 2)
        preferredSize = Dimension(total, total)
        minimumSize = preferredSize
        isOpaque = false
    }

    override fun addNotify() {
        super.addNotify()
        if (animated) timer.start()
    }

    override fun removeNotify() {
        timer.stop()
        super.removeNotify()
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val s = JBUI.scale(sizePx)
            val stroke = strokePx * JBUI.scale(1).toFloat()
            val cx = width / 2
            val cy = height / 2
            val faint = Color(color.red, color.green, color.blue, 60)
            g2.color = faint
            g2.stroke = java.awt.BasicStroke(stroke)
            g2.drawOval(cx - s / 2, cy - s / 2, s, s)
            g2.color = color
            g2.rotate(Math.toRadians(angleDeg.toDouble()), cx.toDouble(), cy.toDouble())
            g2.drawArc(cx - s / 2, cy - s / 2, s, s, 0, 90)
        } finally {
            g2.dispose()
        }
    }
}

class BlinkingCursor(
    private val widthPx: Int = 6,
    private val heightPx: Int = 11,
) : JComponent() {

    var color: Color = Color.LIGHT_GRAY
        set(value) {
            field = value
            repaint()
        }

    private var visible = true
    private val timer = Timer(500) {
        visible = !visible
        repaint()
    }

    init {
        preferredSize = Dimension(JBUI.scale(widthPx), JBUI.scale(heightPx))
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
        if (!visible) return
        val g2 = g.create() as Graphics2D
        try {
            g2.color = color
            g2.fillRect(0, 0, width, height)
        } finally {
            g2.dispose()
        }
    }
}
