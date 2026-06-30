package co.cben.dev.aiinlinereview

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.impl.EditorEmbeddedComponentManager
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.event.SelectionEvent
import com.intellij.openapi.editor.event.SelectionListener
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import java.nio.file.Files
import java.nio.file.Paths
import java.util.UUID
import javax.swing.Icon
import javax.swing.JComponent

/**
 * Owns all in-editor review decorations:
 * - a gutter marker on the commented line (aligned with the card),
 * - a Space-style inline card hosting view/edit/reply for each stored comment,
 * - a "+" gutter affordance on the selected line, and drafts: selecting + "+"/shortcut opens an
 *   inline card in edit mode (no dialog); Save stores it, Cancel discards.
 */
@Service(Service.Level.PROJECT)
class ReviewGutterService(private val project: Project) : Disposable {

    private data class CardRef(val editor: Editor, val card: ReviewCardPanel, val inlay: Inlay<*>)

    private val commentHighlighters = mutableListOf<Pair<Editor, RangeHighlighter>>()
    private val cards = mutableListOf<CardRef>()
    private val draftInlays = mutableListOf<Inlay<*>>()
    private val addAffordances = mutableMapOf<Editor, RangeHighlighter>()
    private val editorListenersInstalled = mutableSetOf<Editor>()

    private val storeListener = Runnable {
        ApplicationManager.getApplication().invokeLater {
            ReviewFiles.sync(project, ReviewStore.getInstance(project).comments)
            refresh()
        }
    }

    fun start() {
        ReviewStore.getInstance(project).ensureSession()
        ReviewSkill.install(project)
        ReviewStore.getInstance(project).addListener(storeListener)
        EditorFactory.getInstance().addEditorFactoryListener(
            object : EditorFactoryListener {
                override fun editorCreated(event: EditorFactoryEvent) {
                    ApplicationManager.getApplication().invokeLater {
                        installEditorListeners(event.editor)
                        decorate(event.editor)
                    }
                }

                override fun editorReleased(event: EditorFactoryEvent) {
                    addAffordances.remove(event.editor)
                    editorListenersInstalled.remove(event.editor)
                }
            },
            this,
        )
        // Watch .claude/review/inbox so external edits (e.g. Claude appending a reply to a note
        // file) are reloaded into the cards. Self-writes are filtered out to avoid a loop.
        project.messageBus.connect(this).subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    val changed = events.filter {
                        val p = it.path.replace('\\', '/')
                        p.contains("/.claude/review/inbox/") && p.endsWith(".json")
                    }
                    if (changed.isNotEmpty()) {
                        ApplicationManager.getApplication().invokeLater { reloadExternal(changed.map { it.path }) }
                    }
                }
            },
        )
        // Reconcile from note files on startup (file IO off the EDT; the store listener defers any UI).
        ReviewInbox.loadAll(project).forEach { if (it.id.isNotBlank()) ReviewStore.getInstance(project).upsert(it) }

        // Editor decoration touches the editor model — must run on the EDT, not the startup coroutine.
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            EditorFactory.getInstance().allEditors
                .filter { it.project == null || it.project == project }
                .forEach { installEditorListeners(it) }
            refresh()
        }
    }

    private fun refresh() {
        clearCards()
        EditorFactory.getInstance().allEditors
            .filter { it.project == null || it.project == project }
            .forEach { decorate(it) }
    }

    private fun decorate(editor: Editor) {
        if (editor !is EditorEx) return
        val projectDir = project.guessProjectDir() ?: return
        val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return
        val relativePath = VfsUtilCore.getRelativePath(file, projectDir) ?: return
        val lineCount = editor.document.lineCount
        val store = ReviewStore.getInstance(project)
        val activeSession = store.activeSessionId

        store.comments
            .filter { it.filePath == relativePath && it.sessionId == activeSession }
            .forEach { comment ->
                val endLine = (comment.endLine - 1).coerceIn(0, maxOf(0, lineCount - 1))

                val highlighter = editor.markupModel.addLineHighlighter(null, endLine, HighlighterLayer.WARNING)
                highlighter.gutterIconRenderer = MarkerIconRenderer(comment)
                commentHighlighters.add(editor to highlighter)

                val anchorOffset = editor.document.getLineEndOffset(endLine)
                val card = ReviewCardPanel(project, comment, isDraft = false, cardWidth(editor))
                val inlay = mountCard(editor, card, anchorOffset) ?: return@forEach
                cards.add(CardRef(editor, card, inlay))
                card.requestRelayout = { inlay.update() }
                card.onResolve = { ReviewStore.getInstance(project).remove(comment) }
                card.onDelete = { ReviewStore.getInstance(project).remove(comment) }
                card.onModeChange = { ReviewStore.getInstance(project).update() }
                card.onSave = { text, atts ->
                    comment.comment = text
                    comment.attachments = atts.toMutableList()
                    ReviewStore.getInstance(project).update()
                }
                card.onReply = { text ->
                    comment.replies.add(
                        ReviewReply().apply {
                            author = ReviewUi.currentAuthor
                            this.text = text
                            createdAt = System.currentTimeMillis()
                        },
                    )
                    ReviewStore.getInstance(project).update()
                }
            }
    }

    fun startDraft(editor: Editor) {
        if (editor !is EditorEx) return
        val selection = editor.selectionModel
        if (!selection.hasSelection()) return
        val document = editor.document
        val snippet = selection.selectedText ?: return
        val startLine = document.getLineNumber(selection.selectionStart) + 1
        val endLine = document.getLineNumber(selection.selectionEnd) + 1

        val projectDir = project.guessProjectDir()
        val file = FileDocumentManager.getInstance().getFile(document)
        val relativePath = when {
            file != null && projectDir != null -> VfsUtilCore.getRelativePath(file, projectDir) ?: file.path
            file != null -> file.path
            else -> "(unknown)"
        }

        val draft = ReviewComment().apply {
            id = UUID.randomUUID().toString()
            filePath = relativePath
            this.startLine = startLine
            this.endLine = endLine
            this.snippet = snippet
            source = "editor"
            sessionId = ReviewStore.getInstance(project).activeSessionId
        }
        val anchor = (endLine - 1).coerceIn(0, maxOf(0, document.lineCount - 1))
        val card = ReviewCardPanel(project, draft, isDraft = true, cardWidth(editor))
        val inlay = mountCard(editor, card, document.getLineEndOffset(anchor)) ?: return
        draftInlays.add(inlay)
        card.requestRelayout = { inlay.update() }
        card.onSave = { text, atts ->
            if (text.isNotBlank()) {
                draft.comment = text
                draft.attachments = atts.toMutableList()
                draft.author = ReviewUi.currentAuthor
                draft.createdAt = System.currentTimeMillis()
                ReviewStore.getInstance(project).add(draft)
            }
            disposeDraft(inlay)
        }
        card.onCancelDraft = { disposeDraft(inlay) }
    }

    private fun mountCard(editor: EditorEx, card: JComponent, offset: Int): Inlay<*>? {
        val properties = EditorEmbeddedComponentManager.Properties(
            EditorEmbeddedComponentManager.ResizePolicy.none(),
            null,
            true,
            false,
            0,
            offset,
        )
        return runCatching {
            EditorEmbeddedComponentManager.getInstance().addComponent(editor, card, properties)
        }.getOrNull()
    }

    /** Reload externally-edited note files (e.g. Claude appended a reply) into the store. */
    private fun reloadExternal(paths: List<String>) {
        val store = ReviewStore.getInstance(project)
        paths.forEach { raw ->
            val path = Paths.get(raw)
            if (!Files.exists(path)) return@forEach
            val id = path.fileName.toString().removeSuffix(".json")
            val content = runCatching { Files.readString(path) }.getOrNull() ?: return@forEach
            if (ReviewFiles.isSelfWrite(id, content)) return@forEach
            val note = ReviewFiles.readNote(content) ?: return@forEach
            if (note.id.isNotBlank()) store.upsert(note)
        }
    }

    private fun cardWidth(editor: Editor): Int = editor.contentComponent.width.coerceIn(360, 1100)

    private fun disposeDraft(inlay: Inlay<*>) {
        draftInlays.remove(inlay)
        runCatching { inlay.dispose() }
    }

    private fun clearCards() {
        commentHighlighters.forEach { (editor, highlighter) ->
            runCatching { editor.markupModel.removeHighlighter(highlighter) }
        }
        commentHighlighters.clear()
        cards.forEach { runCatching { it.inlay.dispose() } }
        cards.clear()
    }

    private fun installEditorListeners(editor: Editor) {
        if (!editorListenersInstalled.add(editor)) return
        editor.selectionModel.addSelectionListener(
            object : SelectionListener {
                override fun selectionChanged(e: SelectionEvent) = updateAddAffordance(editor)
            },
            this,
        )
        // Keep card widths in sync with the editor — also fixes initial 0-width on load.
        editor.contentComponent.addComponentListener(
            object : java.awt.event.ComponentAdapter() {
                override fun componentResized(e: java.awt.event.ComponentEvent) {
                    val width = cardWidth(editor)
                    cards.filter { it.editor == editor }.forEach {
                        it.card.setWidth(width)
                        it.inlay.update()
                    }
                }
            },
        )
    }

    private fun updateAddAffordance(editor: Editor) {
        addAffordances.remove(editor)?.let { runCatching { editor.markupModel.removeHighlighter(it) } }
        val selection = editor.selectionModel
        if (!selection.hasSelection()) return
        val line = editor.document.getLineNumber(selection.selectionStart)
        val highlighter = editor.markupModel.addLineHighlighter(null, line, HighlighterLayer.LAST)
        highlighter.gutterIconRenderer = AddIconRenderer(editor)
        addAffordances[editor] = highlighter
    }

    override fun dispose() {
        clearCards()
        draftInlays.forEach { runCatching { it.dispose() } }
        draftInlays.clear()
        addAffordances.clear()
        editorListenersInstalled.clear()
        ReviewStore.getInstance(project).removeListener(storeListener)
    }

    private inner class MarkerIconRenderer(private val comment: ReviewComment) : GutterIconRenderer() {
        override fun getIcon(): Icon = AllIcons.General.Balloon
        override fun getTooltipText(): String = comment.comment.lineSequence().firstOrNull().orEmpty()
        override fun isNavigateAction(): Boolean = false
        override fun equals(other: Any?): Boolean =
            other is MarkerIconRenderer && other.comment.id == comment.id

        override fun hashCode(): Int = comment.id.hashCode()
    }

    private inner class AddIconRenderer(private val editor: Editor) : GutterIconRenderer() {
        override fun getIcon(): Icon = AllIcons.General.InlineAdd
        override fun getTooltipText(): String = "Add review comment for selection"
        override fun isNavigateAction(): Boolean = true
        override fun getClickAction(): AnAction = object : AnAction() {
            override fun actionPerformed(e: AnActionEvent) = startDraft(editor)
        }

        override fun equals(other: Any?): Boolean = other is AddIconRenderer && other.editor == editor
        override fun hashCode(): Int = editor.hashCode()
    }

    companion object {
        fun getInstance(project: Project): ReviewGutterService =
            project.getService(ReviewGutterService::class.java)
    }
}
