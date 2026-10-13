package co.cben.dev.aiinlinereview.crit

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.util.concurrency.AppExecutorUtil
import git4idea.repo.GitRepositoryManager
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

@Service(Service.Level.PROJECT)
class CritSessionService(private val project: Project) : Disposable {
    // One thread owns the controller; every read/write of crit state goes through it.
    private val executor = AppExecutorUtil.createBoundedScheduledExecutorService("Crit session", 1)
    private var ticker: ScheduledFuture<*>? = null

    private val hub = CritSessionHub(
        executor = executor,
        readers = AppExecutorUtil.getAppExecutorService(),
        locate = ::locate,
        currentBranch = { GitRepositoryManager.getInstance(project).repositories.firstOrNull()?.currentBranchName },
        isActive = { ApplicationManager.getApplication().isActive },
        connect = { CritClient(it.baseUrl) },
        warn = ::notifyWarning,
    )

    val snapshot: CritSnapshot? get() = hub.snapshot

    val candidates: List<SessionEntry> get() = hub.candidates

    fun start() {
        if (ticker == null) ticker = executor.scheduleWithFixedDelay(hub::tick, 0, TICK_MS, TimeUnit.MILLISECONDS)
    }

    fun addListener(parent: Disposable, listener: (CritSnapshot?) -> Unit) {
        val remove = hub.addListener(listener)
        Disposer.register(parent) { remove() }
    }

    fun attach(entry: SessionEntry): CompletableFuture<Unit> = CompletableFuture.supplyAsync({ hub.attachNow(entry) }, executor)

    fun <T> submit(action: (CritSessionController) -> T): CompletableFuture<T> =
        CompletableFuture.supplyAsync({ action(hub.controller ?: error("No crit session attached")) }, executor)

    private fun locate(): List<SessionEntry> {
        val root = project.basePath?.let(Path::of) ?: return emptyList()
        val entries = CritSessionLocator.readEntries(CritSessionLocator.registryDir())
        return CritSessionLocator.forProject(entries, root).filter(CritSessionLocator::isAlive)
    }

    private fun notifyWarning(message: String) {
        NotificationGroupManager.getInstance().getNotificationGroup("AI Inline Review")
            .createNotification(message, NotificationType.WARNING)
            .notify(project)
    }

    override fun dispose() {
        ticker?.cancel(false)
        hub.dispose()
    }

    companion object {
        private const val TICK_MS = 2_000L

        fun getInstance(project: Project): CritSessionService = project.getService(CritSessionService::class.java)
    }
}
