package co.cben.dev.aiinlinereview

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.time.Instant
import java.time.ZoneId
import javax.swing.Icon
import javax.swing.JButton
import kotlin.math.abs

/** Shared identity, formatting and small widgets for the review cards. */
object ReviewUi {

    val currentAuthor: String
        get() = System.getProperty("user.name")?.takeIf { it.isNotBlank() } ?: "You"

    fun time(epochMillis: Long): String {
        if (epochMillis <= 0) return ""
        val t = Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).toLocalTime()
        return "%02d:%02d".format(t.hour, t.minute)
    }

    private val PALETTE = listOf(
        JBColor(Color(0x0E, 0x7C, 0x86), Color(0x2D, 0xD4, 0xBF)),
        JBColor(Color(0x35, 0x74, 0xF0), Color(0x6E, 0xA8, 0xFE)),
        JBColor(Color(0x8B, 0x5C, 0xF6), Color(0xC4, 0xB5, 0xFD)),
        JBColor(Color(0x1A, 0x7F, 0x37), Color(0x5C, 0xC9, 0x76)),
        JBColor(Color(0xC2, 0x68, 0x10), Color(0xF0, 0xA9, 0x4D)),
        JBColor(Color(0xC0, 0x36, 0x5E), Color(0xF7, 0x7C, 0xA6)),
    )

    fun authorColor(name: String): JBColor = PALETTE[abs(name.hashCode()) % PALETTE.size]

    fun chip(text: String, color: JBColor): JBLabel = object : JBLabel("@$text") {
        override fun isOpaque(): Boolean = false
        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = JBColor(Color(color.red, color.green, color.blue, 40), Color(color.red, color.green, color.blue, 60))
            g2.fillRoundRect(0, 0, width - 1, height - 1, height, height)
            g2.dispose()
            super.paintComponent(g)
        }
    }.apply {
        foreground = color
        font = JBFont.small().asBold()
        border = JBUI.Borders.empty(2, 9)
    }

    /** A rounded chip the user can click to toggle a note's mode. */
    fun toggleChip(text: String, icon: Icon, color: JBColor, onClick: () -> Unit): JBLabel {
        val label = object : JBLabel(text, icon, javax.swing.SwingConstants.LEFT) {
            override fun isOpaque(): Boolean = false
            override fun paintComponent(g: Graphics) {
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = JBColor(Color(color.red, color.green, color.blue, 40), Color(color.red, color.green, color.blue, 60))
                g2.fillRoundRect(0, 0, width - 1, height - 1, height, height)
                g2.dispose()
                super.paintComponent(g)
            }
        }
        label.foreground = color
        label.font = JBFont.small().asBold()
        label.iconTextGap = JBUI.scale(3)
        label.border = JBUI.Borders.empty(2, 9)
        label.toolTipText = "Click to toggle Comment / Action"
        label.cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
        label.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) = onClick()
        })
        return label
    }

    fun iconButton(icon: Icon, tooltip: String, onClick: () -> Unit): JButton =
        JButton(icon).apply {
            isContentAreaFilled = false
            isBorderPainted = false
            isFocusPainted = false
            isOpaque = false
            isRolloverEnabled = true
            margin = JBUI.emptyInsets()
            border = JBUI.Borders.empty()
            val size = JBUI.size(24, 24)
            preferredSize = size
            minimumSize = size
            maximumSize = size
            toolTipText = tooltip
            cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
            addActionListener { onClick() }
        }
}
