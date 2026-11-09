package co.cben.dev.aiinlinereview

import com.google.gson.Gson
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.PlatformTestUtil
import java.nio.file.Files
import java.nio.file.Paths
import java.util.UUID

class ReviewGutterServiceTest : HeavyPlatformTestCase() {
    fun testOnlyOwningProjectAddsSelectionButton() {
        val otherProject = ProjectManager.getInstance().createProject("other", createTempDir("other").path)
        val factory = EditorFactory.getInstance()
        val editors = mutableListOf<Editor>()
        try {
            val owners = listOf(project, otherProject)
            owners.forEach { editors.add(factory.createEditor(factory.createDocument("selected text"), it)) }
            ReviewGutterService.getInstance(project).start()
            ReviewGutterService.getInstance(otherProject).start()
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

            // Editors created after both projects start go through the global factory listener.
            owners.forEach { editors.add(factory.createEditor(factory.createDocument("selected text"), it)) }
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            for (editor in editors) {
                editor.selectionModel.setSelection(0, 8)
                assertEquals("Only the owning project should add a button", 1, buttonCount(editor))
                editor.selectionModel.setSelection(1, 7)
                assertEquals("Changing selection should replace the button", 1, buttonCount(editor))
                editor.selectionModel.removeSelection()
                assertEquals(0, buttonCount(editor))
            }
        } finally {
            editors.forEach { factory.releaseEditor(it) }
            ApplicationManager.getApplication().runWriteAction { Disposer.dispose(otherProject) }
        }
    }

    fun testProjectlessEditorDoesNotGetSelectionButton() {
        val factory = EditorFactory.getInstance()
        val editor = factory.createEditor(factory.createDocument("selected text"))
        try {
            // Also exercise the startup scan of already-existing editors.
            ReviewGutterService.getInstance(project).start()
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            editor.selectionModel.setSelection(0, 8)
            assertEquals(0, buttonCount(editor))
        } finally {
            factory.releaseEditor(editor)
        }
    }

    fun testInboxChangesStayInOwningProject() {
        // A nested project must also be isolated, not just unrelated project paths.
        val otherPath = Files.createDirectories(Paths.get(project.basePath!!, "nested-project"))
        val otherProject = ProjectManager.getInstance().createProject("other", otherPath.toString())
        try {
            val owners = listOf(project, otherProject)
            val notes = owners.map { owner ->
                ReviewComment().apply {
                    id = UUID.randomUUID().toString()
                    comment = "Note for ${owner.name}"
                }
            }
            val files = owners.zip(notes).map { (owner, note) -> createInboxFile(owner, note) }
            owners.forEach { ReviewGutterService.getInstance(it).start() }
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

            // Real VFS writes exercise the application-wide file notifications and deferred reloads.
            ApplicationManager.getApplication().runWriteAction {
                files.zip(notes).forEach { (file, note) ->
                    note.replies.add(ReviewReply().apply { text = "External reply" })
                    file.setBinaryContent(Gson().toJson(mapOf("item" to note)).toByteArray())
                }
            }
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

            owners.zip(notes).forEach { (owner, note) ->
                val comments = ReviewStore.getInstance(owner).comments
                assertEquals("Only the owning project should import the note", listOf(note.id), comments.map { it.id })
                assertEquals("External reply", comments.single().replies.single().text)
                val foreignNote = notes.single { it.id != note.id }
                assertFalse("Foreign notes must not be copied into this inbox",
                    Files.exists(ReviewFiles.inboxDir(owner)!!.resolve("${foreignNote.id}.json")))
            }
        } finally {
            ApplicationManager.getApplication().runWriteAction { Disposer.dispose(otherProject) }
        }
    }

    private fun createInboxFile(owner: Project, note: ReviewComment): VirtualFile {
        val path = ReviewFiles.inboxDir(owner)!!.resolve("${note.id}.json")
        Files.writeString(path, Gson().toJson(mapOf("item" to note)))
        return LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)!!
    }

    private fun buttonCount(editor: Editor): Int = editor.markupModel.allHighlighters.count {
        it.gutterIconRenderer?.tooltipText == "Add review comment for selection"
    }
}
