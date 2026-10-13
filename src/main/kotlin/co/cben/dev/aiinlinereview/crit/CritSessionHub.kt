package co.cben.dev.aiinlinereview.crit

import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.diagnostic.logger
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class CritSessionHub(
    private val executor: ScheduledExecutorService,
    private val readers: Executor,
    private val locate: () -> List<SessionEntry>,
    private val currentBranch: () -> String?,
    private val isActive: () -> Boolean,
    private val connect: (SessionEntry) -> CritConnection,
    private val warn: (String) -> Unit = {},
    private val sseDelayMs: Long = SSE_DELAY_MS,
) {
    private val listeners = CopyOnWriteArrayList<(CritSnapshot?) -> Unit>()
    private val rejected = mutableSetOf<String>()
    private val streams = ConcurrentHashMap<CritSessionController, CritEventStream>()

    @Volatile
    var controller: CritSessionController? = null
        private set

    @Volatile
    var candidates: List<SessionEntry> = emptyList()
        private set

    val snapshot: CritSnapshot? get() = controller?.snapshot

    fun addListener(listener: (CritSnapshot?) -> Unit): () -> Unit {
        listeners += listener
        return { listeners -= listener }
    }

    fun tick() = guarded {
        candidates = locate()
        val current = controller
        val preferred = CritSessionLocator.pick(candidates, currentBranch())
        when {
            current != null && candidates.none { it.key == current.entry.key } -> detach("session ended")
            current != null && preferred != null && preferred.key != current.entry.key && newer(preferred, current.entry) ->
                attachNow(preferred)
            current != null -> if (isActive()) current.refreshComments()
            preferred != null -> attachNow(preferred)
        }
    }

    fun attachNow(entry: SessionEntry) {
        if (processId(entry) in rejected || controller?.entry?.key == entry.key) return
        detach(null)
        val connection = connect(entry)
        val next = CritSessionController(entry, connection, ResolveNudger(connection))
        try {
            next.load()
        } catch (e: UnsupportedCritApi) {
            rejected += processId(entry)
            warn(e.message.orEmpty())
            return
        }
        next.addListener(::fire)
        controller = next
        fire(next.snapshot)
        LOG.info("crit attached: ${entry.key} ${entry.baseUrl} round ${next.snapshot?.session?.reviewRound}")
        // Late SSE connect: crit treats an SSE client as an open browser and would skip auto-opening one.
        schedule(sseDelayMs) { readEvents(connection, next) }
    }

    fun onEvent(owner: CritSessionController, event: CritEvent) = guarded {
        when {
            controller !== owner -> Unit
            event.type == "server-shutdown" -> detach("crit stopped")
            else -> owner.handle(event)
        }
    }

    fun detach(reason: String?) {
        val current = controller ?: return
        controller = null
        streams.remove(current)?.close()
        LOG.info("crit detached: ${current.entry.key} ${reason.orEmpty()}")
        fire(null)
    }

    fun dispose() {
        controller = null
        streams.values.forEach { it.close() }
        streams.clear()
        executor.shutdownNow()
    }

    private fun readEvents(connection: CritConnection, owner: CritSessionController) {
        readers.execute {
            var backoff = 1_000L
            while (controller === owner) {
                try {
                    val stream = connection.openEvents()
                    streams.put(owner, stream)?.close()
                    // detach may have run while openEvents was connecting; it could not close this stream yet.
                    if (controller !== owner) {
                        streams.remove(owner)?.close()
                        break
                    }
                    stream.use {
                        backoff = 1_000L
                        it.forEach { event -> executor.execute { onEvent(owner, event) } }
                    }
                    streams.remove(owner, stream)
                } catch (e: Exception) {
                    LOG.debug("crit events dropped", e)
                }
                if (controller !== owner) break
                Thread.sleep(backoff)
                backoff = (backoff * 2).coerceAtMost(30_000L)
            }
        }
    }

    private inline fun guarded(block: () -> Unit) {
        try {
            block()
        } catch (e: IOException) {
            detach("unreachable: ${e.message}")
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: RuntimeException) {
            if (e is ControlFlowException) throw e
            // A scheduled tick that throws is never rescheduled, so nothing may escape.
            LOG.warn("crit update failed", e)
        }
    }

    private fun newer(a: SessionEntry, b: SessionEntry) = CritSessionLocator.startedAt(a) > CritSessionLocator.startedAt(b)

    private fun processId(entry: SessionEntry) = "${entry.key}:${entry.pid}"

    private fun fire(snapshot: CritSnapshot?) {
        listeners.forEach { listener -> runCatching { listener(snapshot) }.onFailure { LOG.warn("crit listener failed", it) } }
    }

    private fun schedule(delayMs: Long, action: () -> Unit) {
        executor.schedule({ runCatching(action).onFailure { LOG.warn("crit task failed", it) } }, delayMs, TimeUnit.MILLISECONDS)
    }

    companion object {
        private val LOG = logger<CritSessionHub>()
        const val SSE_DELAY_MS = 3_000L
    }
}
