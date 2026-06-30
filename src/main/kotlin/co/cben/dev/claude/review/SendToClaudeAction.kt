package co.cben.dev.claude.review

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ui.Messages

/**
 * Flush all pending review comments to .claude/review/inbox.
 */
class SendToClaudeAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabled =
            project != null && ReviewStore.getInstance(project).comments.isNotEmpty()
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        ReviewExporter.export(project) ?: return
        Messages.showInfoMessage(
            project,
            "Synced notes to .claude/review/inbox.\nTell Claude: read .claude/review/inbox.",
            "Sent to Claude",
        )
    }
}
