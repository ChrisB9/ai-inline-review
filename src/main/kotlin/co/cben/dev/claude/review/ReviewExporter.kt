package co.cben.dev.claude.review

import com.intellij.openapi.project.Project
import java.nio.file.Path

/**
 * "Send to Claude" syncs the current notes to .claude/review/inbox (one file per note).
 */
object ReviewExporter {

    fun export(project: Project): Path? {
        val store = ReviewStore.getInstance(project)
        if (store.comments.isEmpty()) return null
        return ReviewFiles.sync(project, store.comments)
    }
}
