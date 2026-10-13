package co.cben.dev.aiinlinereview

import co.cben.dev.aiinlinereview.crit.CritSessionService
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

class ReviewGutterStartup : ProjectActivity {
    override suspend fun execute(project: Project) {
        CritSessionService.getInstance(project).start()
        ReviewGutterService.getInstance(project).start()
        ReviewWebServer.getInstance(project).start()
    }
}
