package co.cben.dev.aiinlinereview

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.PlatformTestUtil

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

    private fun buttonCount(editor: Editor): Int = editor.markupModel.allHighlighters.count {
        it.gutterIconRenderer?.tooltipText == "Add review comment for selection"
    }
}
