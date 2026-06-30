package co.cben.dev.aiinlinereview

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

class ReviewGutterStartup : ProjectActivity {
    override suspend fun execute(project: Project) {
        ReviewGutterService.getInstance(project).start()
        ReviewWebServer.getInstance(project).start()
    }
}
