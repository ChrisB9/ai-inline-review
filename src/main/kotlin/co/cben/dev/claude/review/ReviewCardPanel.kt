package co.cben.dev.claude.review

import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.fileTypes.FileTypes
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.ui.EditorTextField
import com.intellij.ui.JBColor
import com.intellij.ui.RoundedLineBorder
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.JPanel

/**
 * Inline review card hosted as an editor component inlay — the single interaction surface.
 * Editing uses [EditorTextField] (its own editor + document), so keystrokes never leak into the
 * host file. Styled after modern review UIs (Space / crit): author chips, threaded replies.
 */
class ReviewCardPanel(
    private val project: Project,
    private val comment: ReviewComment,
    private val isDraft: Boolean,
    private var cardWidth: Int,
) : JPanel(BorderLayout()) {

    private enum class Mode { VIEW, EDIT, REPLY }

    private var mode = if (isDraft) Mode.EDIT else Mode.VIEW
    private val attachments = comment.attachments.toMutableList()
    private val flex = mutableListOf<JComponent>()
    private var activeField: EditorTextField? = null

    var requestRelayout: () -> Unit = {}
    var onSave: (text: String, attachments: List<String>) -> Unit = { _, _ -> }
    var onCancelDraft: () -> Unit = {}
    var onResolve: () -> Unit = {}
    var onDelete: () -> Unit = {}
    var onReply: (text: String) -> Unit = {}
    var onModeChange: () -> Unit = {}

    init {
        isOpaque = false
        border = JBUI.Borders.empty(9, 13)
        rebuild()
    }

    fun setWidth(width: Int) {
        if (width != cardWidth) {
            cardWidth = width
            revalidate()
        }
    }

    private fun rebuild() {
        removeAll()
        flex.clear()
        activeField = null
        add(buildHeader(), BorderLayout.NORTH)
        add(buildCenter(), BorderLayout.CENTER)
        buildSouth()?.let { add(it, BorderLayout.SOUTH) }
        revalidate()
        repaint()
        requestRelayout()
        activeField?.let { field -> IdeFocusManager.getInstance(project).requestFocus(field, true) }
    }

    private fun buildHeader(): JPanel {
        val header = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.emptyBottom(6)
        }
        val author = comment.author.ifBlank { ReviewUi.currentAuthor }
        val items = mutableListOf<JComponent>(ReviewUi.chip(author, ReviewUi.authorColor(author)))
        items.add(muted(if (isDraft) "new" else "Line ${comment.startLine}"))
        ReviewUi.time(comment.createdAt).takeIf { it.isNotEmpty() }?.let { items.add(muted(it)) }
        header.add(row(items, 8), BorderLayout.WEST)

        val right = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply { isOpaque = false }
        right.add(modeToggle())
        if (!isDraft) {
            right.add(ReviewUi.iconButton(AllIcons.General.InspectionsOK, "Resolve") { onResolve() })
            if (mode == Mode.VIEW) {
                right.add(ReviewUi.iconButton(AllIcons.Actions.Edit, "Edit") { enterEdit() })
            }
            right.add(ReviewUi.iconButton(AllIcons.Actions.Close, "Delete") { onDelete() })
        }
        header.add(right, BorderLayout.EAST)
        return header
    }

    private fun buildCenter(): JComponent {
        val center = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
        }
        when (mode) {
            Mode.EDIT -> {
                val field = makeField(comment.comment, "Leave a note… (Markdown supported)")
                activeField = field
                center.add(
                    composer(
                        field,
                        primary = if (isDraft) "Add" else "Save",
                        onPrimary = {
                            onSave(field.text.trim(), attachments.toList())
                            if (!isDraft) { mode = Mode.VIEW; rebuild() }
                        },
                        onCancel = { if (isDraft) onCancelDraft() else { mode = Mode.VIEW; rebuild() } },
                    ),
                )
            }
            else -> {
                center.add(htmlPane(MarkdownPreview.toHtml(comment.comment, project.basePath)))
                comment.replies.forEach { center.add(replyBlock(it)) }
                if (mode == Mode.REPLY) {
                    val field = makeField("", "Leave a reply…")
                    activeField = field
                    center.add(
                        composer(
                            field,
                            primary = "Reply",
                            onPrimary = {
                                val text = field.text.trim()
                                if (text.isNotEmpty()) onReply(text)
                            },
                            onCancel = { mode = Mode.VIEW; rebuild() },
                            topDivider = comment.replies.isNotEmpty() || comment.comment.isNotEmpty(),
                        ),
                    )
                }
            }
        }
        return center
    }

    private fun buildSouth(): JComponent? = if (mode == Mode.VIEW) replyOpener() else null

    private fun replyOpener(): JComponent {
        val opener = JButton("Write a reply…").apply {
            horizontalAlignment = JButton.LEFT
            isFocusPainted = false
            isContentAreaFilled = false
            foreground = MUTED
            font = JBFont.small()
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            border = JBUI.Borders.compound(JBUI.Borders.customLine(BORDER, 1), JBUI.Borders.empty(4, 8))
            addActionListener { mode = Mode.REPLY; rebuild() }
        }
        return JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.emptyTop(6)
            add(opener, BorderLayout.CENTER)
        }
    }

    /** GitHub-style compose box: bordered input + attach affordance + action buttons below. */
    private fun composer(
        field: EditorTextField,
        primary: String,
        onPrimary: () -> Unit,
        onCancel: () -> Unit,
        topDivider: Boolean = false,
    ): JComponent {
        val box = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.compound(RoundedLineBorder(FIELD_BORDER, 10, 1), JBUI.Borders.empty(2))
            add(field, BorderLayout.CENTER)
        }
        val attach = JPanel(FlowLayout(FlowLayout.LEFT, 2, 0)).apply { isOpaque = false }
        attach.add(ReviewUi.iconButton(AllIcons.FileTypes.Image, "Attach image") {
            val chooser = javax.swing.JFileChooser()
            if (chooser.showOpenDialog(this) == javax.swing.JFileChooser.APPROVE_OPTION) {
                chooser.selectedFile?.let { insertImage(field, ReviewImages.saveFromFile(project, it)) }
            }
        })
        attach.add(ReviewUi.iconButton(AllIcons.Actions.MenuPaste, "Paste image") {
            ReviewImages.saveFromClipboard(project)?.let { insertImage(field, it) }
        })
        val actions = JPanel(FlowLayout(FlowLayout.RIGHT, 8, 0)).apply { isOpaque = false }
        actions.add(link(primary, ACCENT, bold = true, onClick = onPrimary))
        actions.add(link("Cancel", MUTED, bold = false, onClick = onCancel))
        val bar = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.emptyTop(5)
            add(attach, BorderLayout.WEST)
            add(actions, BorderLayout.EAST)
        }
        return JPanel(BorderLayout()).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            border = if (topDivider) {
                JBUI.Borders.compound(JBUI.Borders.customLine(DIVIDER, 1, 0, 0, 0), JBUI.Borders.empty(8, 0, 0, 0))
            } else {
                JBUI.Borders.emptyTop(4)
            }
            add(box, BorderLayout.CENTER)
            add(bar, BorderLayout.SOUTH)
        }
    }

    private fun replyBlock(reply: ReviewReply): JComponent {
        val panel = JPanel(BorderLayout()).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(DIVIDER, 1, 0, 0, 0),
                JBUI.Borders.empty(6, 0, 0, 0),
            )
        }
        val author = reply.author.ifBlank { "reply" }
        val items = mutableListOf<JComponent>(ReviewUi.chip(author, ReviewUi.authorColor(author)))
        ReviewUi.time(reply.createdAt).takeIf { it.isNotEmpty() }?.let { items.add(muted(it)) }
        val head = row(items, 8).apply { border = JBUI.Borders.emptyBottom(3) }
        panel.add(head, BorderLayout.NORTH)
        panel.add(htmlPane(MarkdownPreview.toHtml(reply.text, project.basePath)), BorderLayout.CENTER)
        return panel
    }

    private fun makeField(initial: String, placeholder: String): EditorTextField {
        val field = object : EditorTextField(
            EditorFactory.getInstance().createDocument(initial),
            project,
            FileTypes.PLAIN_TEXT,
            false,
            false,
        ) {
            override fun getPreferredSize(): Dimension {
                val base = super.getPreferredSize()
                val lineHeight = editor?.lineHeight ?: JBUI.scale(20)
                val lines = document.lineCount.coerceAtLeast(1)
                return Dimension(base.width, lines * lineHeight + JBUI.scale(10))
            }
        }
        field.setPlaceholder(placeholder)
        field.setFontInheritedFromLAF(true)
        field.addSettingsProvider { editor: EditorEx ->
            editor.settings.isLineNumbersShown = false
            editor.settings.isLineMarkerAreaShown = false
            editor.settings.isFoldingOutlineShown = false
            editor.settings.isUseSoftWraps = true
            editor.settings.additionalLinesCount = 0
            editor.settings.additionalColumnsCount = 0
            editor.setBorder(JBUI.Borders.empty(4, 6))
            editor.backgroundColor = FIELD_BG
        }
        field.document.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                revalidate()
                requestRelayout()
            }
        })
        return field
    }

    private fun insertImage(field: EditorTextField, relativePath: String) {
        attachments.add(relativePath)
        field.text = field.text + "\n![]($relativePath)\n"
    }

    private fun enterEdit() {
        mode = Mode.EDIT
        rebuild()
    }

    private fun modeToggle(): JComponent {
        val action = comment.mode == ReviewComment.MODE_ACTION
        val text = if (action) "Action" else "Comment"
        val icon = if (action) AllIcons.Actions.Execute else AllIcons.General.Balloon
        val color = if (action) ACTION else ACCENT
        return ReviewUi.toggleChip(text, icon, color) {
            comment.mode = if (action) ReviewComment.MODE_COMMENT else ReviewComment.MODE_ACTION
            rebuild()
            onModeChange()
        }
    }

    private fun htmlPane(html: String): JEditorPane =
        JEditorPane("text/html", html).apply {
            isEditable = false
            isOpaque = false
            putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
            font = JBFont.label()
            alignmentX = Component.LEFT_ALIGNMENT
            flex.add(this)
        }

    private fun <T : JComponent> T.alignLeft(): T = apply { alignmentX = Component.LEFT_ALIGNMENT }

    /** Horizontal row with gaps between items only (no leading gap, unlike FlowLayout). */
    private fun row(items: List<JComponent>, gap: Int): JPanel =
        JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            items.forEachIndexed { index, component ->
                if (index > 0) add(Box.createHorizontalStrut(JBUI.scale(gap)))
                component.alignmentY = Component.CENTER_ALIGNMENT
                add(component)
            }
        }

    private fun muted(text: String): JBLabel =
        JBLabel(text).apply {
            foreground = MUTED
            font = JBFont.small()
        }

    private fun link(text: String, color: JBColor, bold: Boolean, onClick: () -> Unit): JButton =
        JButton(text).apply {
            isContentAreaFilled = false
            isBorderPainted = false
            isFocusPainted = false
            isOpaque = false
            margin = JBUI.emptyInsets()
            foreground = color
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            font = if (bold) JBFont.label().asBold() else JBFont.small()
            addActionListener { onClick() }
        }

    override fun getPreferredSize(): Dimension {
        val inner = (cardWidth - 30).coerceAtLeast(160)
        flex.forEach {
            it.maximumSize = Dimension(inner, Int.MAX_VALUE)
            it.setSize(inner, Int.MAX_VALUE)
        }
        activeField?.setPreferredWidth(inner)
        val height = super.getPreferredSize().height
        return Dimension(cardWidth, height.coerceAtLeast(44))
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = CARD_BG
        g2.fillRoundRect(0, 1, width - 2, height - 4, 12, 12)
        g2.color = BORDER
        g2.drawRoundRect(0, 1, width - 2, height - 4, 12, 12)
        g2.dispose()
        super.paintComponent(g)
    }

    private companion object {
        val CARD_BG = JBColor(Color(0xFB, 0xFC, 0xFE), Color(0x2B, 0x2D, 0x30))
        val BORDER = JBColor(Color(0xD0, 0xD7, 0xDE), Color(0x42, 0x45, 0x4A))
        val DIVIDER = JBColor(Color(0xE3, 0xE6, 0xEA), Color(0x3A, 0x3D, 0x42))
        val ACCENT = JBColor(Color(0x35, 0x74, 0xF0), Color(0x6E, 0xA8, 0xFE))
        val ACTION = JBColor(Color(0xC2, 0x68, 0x10), Color(0xF0, 0xA9, 0x4D))
        val MUTED = JBColor(Color(0x8A, 0x90, 0x99), Color(0x8A, 0x90, 0x99))
        val FIELD_BG = JBColor(Color(0xFF, 0xFF, 0xFF), Color(0x1E, 0x1F, 0x22))
        val FIELD_BORDER = JBColor(Color(0xB6, 0xBF, 0xCC), Color(0x4E, 0x52, 0x59))
    }
}
