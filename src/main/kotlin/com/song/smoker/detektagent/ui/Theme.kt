package com.song.smoker.detektagent.ui

import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.UIUtil
import java.awt.Color
import java.awt.Font

object Theme {
    val bg = JBColor(c(0x2B2D30), c(0xF2F4F8))
    val bgEditor = JBColor(c(0x1E1F22), c(0xFFFFFF))
    val bgElevated = JBColor(c(0x313438), c(0xE6E8EB))
    val bgInput = JBColor(c(0x1E1F22), c(0xFCFCFD))
    val bgHover = JBColor(rgba(86, 117, 167, 31), rgba(86, 117, 167, 24))
    val bgActive = JBColor(rgba(86, 117, 167, 56), rgba(86, 117, 167, 48))
    val border = JBColor(c(0x1F1F22), c(0xD3D5DA))
    val borderSoft = JBColor(c(0x393B40), c(0xE0E2E6))
    val borderStrong = JBColor(c(0x4E5157), c(0xBEC1C6))
    val fg = JBColor(c(0xDFE1E5), c(0x1F2125))
    val fgSecondary = JBColor(c(0xA1A3AB), c(0x575A60))
    val fgMuted = JBColor(c(0x6F737A), c(0x80838A))
    val fgDisabled = JBColor(c(0x585A60), c(0x9DA0A6))
    val accent = JBColor(c(0x588BE5), c(0x356BC9))
    val accentSoft = JBColor(rgba(88, 139, 229, 41), rgba(88, 139, 229, 38))
    val accentGlow = JBColor(rgba(88, 139, 229, 115), rgba(88, 139, 229, 90))
    val success = JBColor(c(0x5FB865), c(0x2D8B3D))
    val successSoft = JBColor(rgba(95, 184, 101, 36), rgba(95, 184, 101, 36))
    val warn = JBColor(c(0xD6B86A), c(0xA68228))
    val warnSoft = JBColor(rgba(214, 184, 106, 36), rgba(214, 184, 106, 36))
    val error = JBColor(c(0xE26B6B), c(0xC23838))
    val errorSoft = JBColor(rgba(226, 107, 107, 36), rgba(226, 107, 107, 36))
    val info = JBColor(c(0x7A9CC6), c(0x4F7AB0))
    val purple = JBColor(c(0xB48EAD), c(0x8E5282))
    val purpleSoft = JBColor(rgba(180, 142, 173, 36), rgba(180, 142, 173, 36))
    val purpleBorder = JBColor(rgba(180, 142, 173, 76), rgba(180, 142, 173, 76))

    val headerGradTop = JBColor(c(0x2E3034), c(0xEBEDF0))
    val headerGradBottom = JBColor(c(0x2B2D30), c(0xF2F4F8))
    val avatarTop = JBColor(c(0x588BE5), c(0x4A6FAA))
    val avatarBottom = JBColor(c(0x4A6FAA), c(0x356BC9))
    val avatarRing = JBColor(rgba(88, 139, 229, 115), rgba(88, 139, 229, 115))

    fun ui(size: Int = 11): Font = UIUtil.getLabelFont().deriveFont(size.toFloat())
    fun uiBold(size: Int = 11): Font = UIUtil.getLabelFont().deriveFont(Font.BOLD, size.toFloat())
    fun mono(size: Int = 11): Font {
        val base = runCatching {
            EditorColorsManager.getInstance().globalScheme.getFont(EditorFontType.PLAIN)
        }.getOrNull() ?: JBFont.create(Font(Font.MONOSPACED, Font.PLAIN, size), false)
        return base.deriveFont(size.toFloat())
    }

    private fun c(hex: Int): Color = Color(hex)
    private fun rgba(r: Int, g: Int, b: Int, a: Int): Color = Color(r, g, b, a)
}
