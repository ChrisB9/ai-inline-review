package co.cben.dev.aiinlinereview.crit

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.util.concurrency.AppExecutorUtil
import git4idea.repo.GitRepositoryManager
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

@Service(Service.Level.PROJECT)
class CritSessionService(private val project: Project) : Disposable {
    // One thread owns the controller; every read/write of crit state goes through it.
    private val executor = AppExecutorUtil.createBoundedScheduledExecutorService("Crit session", 1)
    private val listeners = CopyOnWriteArrayList<(CritSnapshot?) -> Unit>()
    private val rejected = mutableSetOf<String>()
    private var ticker: ScheduledFuture<*>? = null

    @Volatile
    private var controller: CritSessionController? = null

    @Volatile
    private var events: CritEventStream? = null

    @Volatile
    var candidates: List<SessionEntry> = emptyList()
        private set

    val snapshot: CritSnapshot? get() = controller?.snapshot

    fun start() {
        if (ticker == null) ticker = executor.scheduleWithFixedDelay(::tick, 0, TICK_MS, TimeUnit.MILLISECONDS)
    }

    fun addListener(parent: Disposable, listener: (CritSnapshot?) -> Unit) {
        listeners += listener
        Disposer.register(parent) { listeners -= listener }
    }

    fun attach(entry: SessionEntry): CompletableFuture<Unit> = CompletableFuture.supplyAsync({ attachNow(entry) }, executor)

    fun <T> submit(action: (CritSessionController) -> T): CompletableFuture<T> =
        CompletableFuture.supplyAsync({ action(controller ?: error("No crit session attached")) }, executor)

    private fun tick() {
        try {
            val root = project.basePath?.let(Path::of) ?: return
            val entries = CritSessionLocator.readEntries(CritSessionLocator.registryDir())
            candidates = CritSessionLocator.forProject(entries, root).filter(CritSessionLocator::isAlive)
            val current = controller
            when {
                current != null && candidates.none { it.key == current.entry.key } -> detach("session ended")
                current != null -> if (ApplicationManager.getApplication().isActive) current.refreshComments()
                else -> CritSessionLocator.pick(candidates, currentBranch())?.let(::attachNow)
            }
        } catch (e: IOException) {
            detach("unreachable: ${e.message}")
        } catch (e: CritHttpException) {
            LOG.warn("crit request failed", e)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun attachNow(entry: SessionEntry) {
        if (entry.key in rejected || controller?.entry?.key == entry.key) return
        detach(null)
        val client = CritClient(entry.baseUrl)
        val next = CritSessionController(entry, client, ResolveNudger(client))
        try {
            next.load()
        } catch (e: UnsupportedCritApi) {
            rejected += entry.key
            notifyWarning(e.message.orEmpty())
            return
        }
        next.addListener(::fire)
        controller = next
        fire(next.snapshot)
        LOG.info("crit attached: ${entry.key} ${entry.baseUrl} round ${next.snapshot?.session?.reviewRound}")
        // Late SSE connect: crit treats an SSE client as an open browser and would skip auto-opening one.
        schedule(SSE_DELAY_MS) { readEvents(client, next) }
    }

    private fun readEvents(client: CritClient, owner: CritSessionController) {
        AppExecutorUtil.getAppExecutorService().execute {
            var backoff = 1_000L
            while (controller === owner) {
                try {
                    client.openEvents().also { events = it }.use { stream ->
                        backoff = 1_000L
                        stream.forEach { event ->
                            executor.execute {
                                when {
                                    controller !== owner -> Unit
                                    event.type == "server-shutdown" -> detach("crit stopped")
                                    else -> owner.handle(event)
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    LOG.debug("crit events dropped", e)
                }
                if (controller !== owner) break
                Thread.sleep(backoff)
                backoff = (backoff * 2).coerceAtMost(30_000L)
            }
        }
    }

    private fun detach(reason: String?) {
        val current = controller ?: return
        controller = null
        events?.close()
        events = null
        LOG.info("crit detached: ${current.entry.key} ${reason.orEmpty()}")
        fire(null)
    }

    private fun fire(snapshot: CritSnapshot?) {
        listeners.forEach { listener -> runCatching { listener(snapshot) }.onFailure { LOG.warn("crit listener failed", it) } }
    }

    private fun schedule(delayMs: Long, action: () -> Unit) {
        executor.schedule({ runCatching(action).onFailure { LOG.warn("crit task failed", it) } }, delayMs, TimeUnit.MILLISECONDS)
    }

    private fun currentBranch(): String? =
        GitRepositoryManager.getInstance(project).repositories.firstOrNull()?.currentBranchName

    private fun notifyWarning(message: String) {
        NotificationGroupManager.getInstance().getNotificationGroup("AI Inline Review")
            .createNotification(message, NotificationType.WARNING)
            .notify(project)
    }

    override fun dispose() {
        ticker?.cancel(false)
        controller = null
        events?.close()
        executor.shutdownNow()
    }

    companion object {
        private val LOG = logger<CritSessionService>()
        private const val TICK_MS = 2_000L
        private const val SSE_DELAY_MS = 3_000L

        fun getInstance(project: Project): CritSessionService = project.getService(CritSessionService::class.java)
    }
}
