package co.cben.dev.aiinlinereview.crit

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.stream.Stream

class CritSessionHubTest {
    private val executor = Executors.newSingleThreadScheduledExecutor()
    private val readers = Executors.newCachedThreadPool { runnable -> Thread(runnable).apply { isDaemon = true } }
    private val api = FakeApi()
    private val connected = mutableListOf<SessionEntry>()
    private var entries = listOf<SessionEntry>()
    private var locate: () -> List<SessionEntry> = { entries }
    private val hub = CritSessionHub(
        executor = executor,
        readers = readers,
        locate = { locate() },
        currentBranch = { "main" },
        isActive = { true },
        connect = { connected += it; api },
        sseDelayMs = 0,
    )

    @After
    fun tearDown() {
        hub.dispose()
        readers.shutdownNow()
    }

    private fun <T> onHub(block: () -> T): T = executor.submit(Callable(block)).get(5, TimeUnit.SECONDS)

    private fun entry(key: String, pid: Long = 1, startedAt: String = "2026-10-14T08:00:00Z") =
        SessionEntry(key = key, pid = pid, port = 1, branch = "main", startedAt = startedAt)

    @Test
    fun `tick survives unexpected exceptions`() {
        locate = { throw IllegalArgumentException("bad cwd") }
        onHub { hub.tick() }
        locate = { entries }
        entries = listOf(entry("a"))
        onHub { hub.tick() }
        assertEquals("a", hub.controller?.entry?.key)
    }

    @Test
    fun `event failures detach instead of escaping`() {
        entries = listOf(entry("a"))
        onHub { hub.tick() }
        val owner = hub.controller!!
        api.sessionError = IOException("crit went away")
        onHub { hub.onEvent(owner, CritEvent("file-changed", "session")) }
        assertNull(hub.controller)
    }

    @Test
    fun `stream opened after detach is closed`() {
        val opened = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closed = CountDownLatch(1)
        api.events = {
            opened.countDown()
            release.await()
            val queue = LinkedBlockingQueue<String>()
            CritEventStream(Stream.generate { queue.take() }.onClose { closed.countDown() })
        }
        entries = listOf(entry("a"))
        onHub { hub.tick() }
        assertTrue(opened.await(5, TimeUnit.SECONDS))
        onHub { hub.detach("test") }
        release.countDown()
        assertTrue("stream leaked", closed.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun `api rejection is remembered per daemon process`() {
        api.health = Health("ok", 2)
        entries = listOf(entry("a", pid = 1))
        onHub { hub.tick() }
        onHub { hub.tick() }
        assertEquals(1, connected.size)
        entries = listOf(entry("a", pid = 2))
        onHub { hub.tick() }
        assertEquals(2, connected.size)
    }

    @Test
    fun `newer session on the current branch replaces the attached one`() {
        entries = listOf(entry("a", startedAt = "2026-10-14T08:00:00Z"))
        onHub { hub.tick() }
        entries = entries + entry("older", startedAt = "2026-10-14T07:00:00Z")
        onHub { hub.tick() }
        assertEquals("a", hub.controller?.entry?.key)
        entries = entries + entry("b", startedAt = "2026-10-14T09:00:00Z")
        onHub { hub.tick() }
        assertEquals("b", hub.controller?.entry?.key)
    }
}
