package co.cben.dev.aiinlinereview

import com.intellij.icons.AllIcons
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.JBColor
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.UUID
import javax.swing.BoxLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer
import javax.swing.ListSelectionModel
import javax.swing.DefaultListModel

class ClaudeReviewPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val store = ReviewStore.getInstance(project)
    private val listModel = DefaultListModel<ReviewComment>()
    private val list = JBList(listModel)
    private val sessionModel = DefaultComboBoxModel<ReviewSession>()
    private val sessionCombo = JComboBox(sessionModel)
    private val detailHost = JPanel(BorderLayout())
    private val detailScroll = JBScrollPane(detailHost)
    private val refreshListener = Runnable { reload() }
    private var updatingSessions = false
    private var detailNote: ReviewComment? = null
    private var detailCard: ReviewCardPanel? = null

    init {
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.cellRenderer = CommentRenderer()
        list.addListSelectionListener {
            if (!it.valueIsAdjusting) list.selectedValue?.let { note -> showDetail(note) }
        }
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) jumpToSelected()
            }
        })

        sessionCombo.renderer = SessionRenderer()
        sessionCombo.addActionListener {
            if (!updatingSessions) {
                (sessionCombo.selectedItem as? ReviewSession)?.let {
                    if (it.id != store.activeSessionId) store.activeSessionId = it.id
                }
            }
        }

        val sessionRow = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            add(sessionCombo)
            add(JButton("New session").apply { addActionListener { newSession() } })
            add(JButton("Finish").apply { addActionListener { finishSession() } })
        }
        val actionRow = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            add(JButton("New note").apply { addActionListener { newNote() } })
            add(JButton("Web review").apply { addActionListener { webReview() } })
            add(JButton("Send to Claude").apply { addActionListener { doSend() } })
            add(JButton("Clear").apply { addActionListener { clearActive() } })
        }
        val top = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(sessionRow)
            add(actionRow)
        }

        detailScroll.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) {
                detailCard?.setWidth(detailScroll.viewport.width.coerceIn(320, 1100))
                detailHost.revalidate()
            }
        })

        val splitter = OnePixelSplitter(true, 0.5f).apply {
            firstComponent = JBScrollPane(list)
            secondComponent = detailScroll
        }

        add(top, BorderLayout.NORTH)
        add(splitter, BorderLayout.CENTER)

        reload()
    }

    override fun addNotify() {
        super.addNotify()
        store.addListener(refreshListener)
        reload()
    }

    override fun removeNotify() {
        store.removeListener(refreshListener)
        super.removeNotify()
    }

    private fun reload() {
        store.ensureSession()
        updatingSessions = true
        sessionModel.removeAllElements()
        store.sessions.forEach { sessionModel.addElement(it) }
        store.activeSession?.let { sessionModel.selectedItem = it }
        updatingSessions = false

        listModel.clear()
        store.activeComments.forEach { listModel.addElement(it) }

        // Keep the detail pane in sync with the (possibly reloaded) note instance.
        val openId = detailNote?.id
        val current = openId?.let { id -> store.activeComments.firstOrNull { it.id == id } }
        if (current != null) showDetail(current) else clearDetail()
    }

    private fun webReview() {
        val server = ReviewWebServer.getInstance(project)
        if (server.port == 0) {
            Messages.showErrorDialog(project, "Web-review server isn't running.", "Web Review")
            return
        }
        java.awt.datatransfer.StringSelection(server.bookmarklet()).let {
            java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(it, it)
        }
        Messages.showInfoMessage(
            project,
            "Bookmarklet copied. Make a browser bookmark with it as the URL, open any page, click the " +
                "bookmark, then Capture screen + Comment + Send. Captures become session notes here.\n\n" +
                "Server: http://localhost:${server.port}",
            "Web Review",
        )
    }

    private fun newNote() {
        val note = ReviewComment().apply {
            id = UUID.randomUUID().toString()
            filePath = ""
            startLine = 0
            endLine = 0
            sessionId = store.activeSessionId
            author = ReviewUi.currentAuthor
            createdAt = System.currentTimeMillis()
        }
        store.add(note)
        list.setSelectedValue(note, true)
        showDetail(note)
        detailCard?.beginEdit()
    }

    private fun showDetail(note: ReviewComment) {
        detailNote = note
        val card = cardFor(note)
        detailCard = card
        detailHost.removeAll()
        detailHost.add(card, BorderLayout.NORTH)
        card.setWidth(detailScroll.viewport.width.coerceIn(320, 1100))
        detailHost.revalidate()
        detailHost.repaint()
    }

    private fun clearDetail() {
        detailNote = null
        detailCard = null
        detailHost.removeAll()
        detailHost.add(
            JBLabel("Select a note, or create one with “New note”.").apply {
                foreground = UIUtil.getContextHelpForeground()
                border = JBUI.Borders.empty(12)
            },
            BorderLayout.NORTH,
        )
        detailHost.revalidate()
        detailHost.repaint()
    }

    private fun cardFor(note: ReviewComment): ReviewCardPanel =
        ReviewCardPanel(project, note, isDraft = false, detailScroll.viewport.width.coerceIn(320, 1100)).apply {
            requestRelayout = { detailHost.revalidate(); detailHost.repaint() }
            onResolve = { store.remove(note) }
            onDelete = { store.remove(note) }
            onModeChange = { store.update() }
            onSave = { text, atts ->
                note.comment = text
                note.attachments = atts.toMutableList()
                store.update()
            }
            onReply = { text ->
                note.replies.add(
                    ReviewReply().apply {
                        author = ReviewUi.currentAuthor
                        this.text = text
                        createdAt = System.currentTimeMillis()
                    },
                )
                store.update()
            }
        }

    private fun newSession() {
        val name = Messages.showInputDialog(project, "Session name", "New Review Session", null)
            ?.trim()?.ifBlank { null } ?: return
        val isPlan = Messages.showYesNoDialog(
            project,
            "Is this a planning / spec session?\n(No = code session)",
            "Session Type",
            "Planning / Spec",
            "Code",
            null,
        ) == Messages.YES
        store.addSession(name, if (isPlan) ReviewSession.KIND_PLAN else ReviewSession.KIND_CODE)
    }

    private fun finishSession() {
        val session = store.activeSession ?: return
        val notes = store.activeComments.size
        val ok = Messages.showYesNoDialog(
            project,
            "Finish session \"${session.name}\"? This deletes its $notes note(s) and their files.",
            "Finish Session",
            Messages.getQuestionIcon(),
        ) == Messages.YES
        if (ok) store.removeSession(session.id)
    }

    private fun clearActive() {
        store.activeComments.toList().forEach { store.remove(it) }
    }

    private fun doSend() {
        ReviewExporter.export(project) ?: return
        val active = store.activeComments
        val action = active.count { it.mode == ReviewComment.MODE_ACTION }
        Messages.showInfoMessage(
            project,
            "Synced ${store.comments.size} note(s) to .claude/review/inbox.\n" +
                "Active session: $action ⚡ Action, ${active.size - action} 💬 Comment.\n" +
                "Tell Claude: read .claude/review/inbox (or run the claude-review skill).",
            "Sent to Claude",
        )
    }

    private fun jumpToSelected() {
        val comment = list.selectedValue ?: return
        val basePath = project.basePath ?: return
        val file = LocalFileSystem.getInstance()
            .findFileByPath("$basePath/${comment.filePath}") ?: return
        OpenFileDescriptor(project, file, (comment.startLine - 1).coerceAtLeast(0), 0).navigate(true)
    }

    private class SessionRenderer : ListCellRenderer<ReviewSession> {
        private val delegate = javax.swing.DefaultListCellRenderer()
        override fun getListCellRendererComponent(
            list: JList<out ReviewSession>?,
            value: ReviewSession?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean,
        ): Component {
            val label = if (value == null) {
                ""
            } else {
                val kind = if (value.kind == ReviewSession.KIND_PLAN) "Plan" else "Code"
                "${value.name}  ·  $kind"
            }
            return delegate.getListCellRendererComponent(list, label, index, isSelected, cellHasFocus)
        }
    }

    private class CommentRenderer : ListCellRenderer<ReviewComment> {
        override fun getListCellRendererComponent(
            list: JList<out ReviewComment>,
            value: ReviewComment,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean,
        ): Component {
            val fg = if (isSelected) UIUtil.getListSelectionForeground(true) else UIUtil.getListForeground()
            val mutedColor = if (isSelected) fg else MUTED

            val title = JBLabel(titleOf(value)).apply {
                font = JBFont.label()
                foreground = fg
            }
            val author = value.author.ifBlank { ReviewUi.currentAuthor }
            val meta = buildString {
                append(if (value.filePath.isBlank()) "Session note" else "${value.filePath}:${value.startLine}")
                append("  ·  @$author")
                ReviewUi.time(value.createdAt).takeIf { it.isNotEmpty() }?.let { append("  ·  $it") }
            }
            val subtitle = JBLabel(meta).apply {
                font = JBFont.small()
                foreground = mutedColor
            }

            val text = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
                add(title)
                add(subtitle)
            }

            val right = JPanel(FlowLayout(FlowLayout.RIGHT, 8, 0)).apply { isOpaque = false }
            val modeText = if (value.mode == ReviewComment.MODE_ACTION) "ACTION" else "COMMENT"
            right.add(
                JBLabel(modeText).apply {
                    font = JBFont.small()
                    foreground = mutedColor
                },
            )
            if (value.replies.isNotEmpty()) {
                right.add(
                    JBLabel(value.replies.size.toString(), AllIcons.General.Balloon, JBLabel.LEFT).apply {
                        font = JBFont.small()
                        foreground = mutedColor
                    },
                )
            }

            // Truncate to available width so long titles/paths ellipsize instead of overflowing.
            val available = (list.width - right.preferredSize.width - JBUI.scale(48)).coerceAtLeast(JBUI.scale(80))
            listOf(title, subtitle).forEach {
                val size = Dimension(available, it.preferredSize.height)
                it.preferredSize = size
                it.maximumSize = size
            }

            return JPanel(BorderLayout(8, 0)).apply {
                border = JBUI.Borders.empty(7, 12)
                isOpaque = true
                background = if (isSelected) UIUtil.getListSelectionBackground(true) else UIUtil.getListBackground()
                add(text, BorderLayout.CENTER)
                add(right, BorderLayout.EAST)
            }
        }

        private fun titleOf(comment: ReviewComment): String {
            val first = comment.comment.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
                ?: return "(empty note)"
            return if (first.startsWith("![")) "🖼 Image note" else first
        }

        private companion object {
            val MUTED = JBColor(Color(0x8A, 0x90, 0x99), Color(0x8A, 0x90, 0x99))
        }
    }
}
