package co.cben.dev.aiinlinereview.crit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

class CritSessionLocatorTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun entry(key: String, cwd: String = "/p", branch: String = "main", startedAt: String = "2026-10-14T08:00:00Z") =
        SessionEntry(key = key, pid = 1, port = 1, cwd = cwd, branch = branch, startedAt = startedAt)

    @Test
    fun `reads registry files and skips junk`() {
        val dir = tmp.newFolder("sessions").toPath()
        Files.writeString(dir.resolve("abc.json"), """{"pid":1,"port":4000,"cwd":"/p","branch":"main"}""")
        Files.writeString(dir.resolve("abc.log"), "log")
        Files.writeString(dir.resolve("broken.json"), "{")
        Files.writeString(dir.resolve("noport.json"), """{"pid":1}""")
        val entries = CritSessionLocator.readEntries(dir)
        assertEquals(listOf("abc"), entries.map { it.key })
        assertEquals(4000, entries.single().port)
    }

    @Test
    fun `missing registry dir gives no entries`() {
        assertEquals(emptyList<SessionEntry>(), CritSessionLocator.readEntries(tmp.root.toPath().resolve("nope")))
    }

    @Test
    fun `matches project through a symlink`() {
        val real = tmp.newFolder("real").toPath()
        val link = Files.createSymbolicLink(tmp.root.toPath().resolve("link"), real)
        val entries = listOf(entry("a", cwd = real.toString()), entry("b", cwd = "/elsewhere"))
        assertEquals(listOf("a"), CritSessionLocator.forProject(entries, link).map { it.key })
    }

    @Test
    fun `single candidate wins regardless of branch`() {
        assertEquals("a", CritSessionLocator.pick(listOf(entry("a", branch = "other")), "main")?.key)
    }

    @Test
    fun `newest session on the current branch wins`() {
        val diff = entry("diff", startedAt = "2026-10-14T08:00:00Z")
        val plan = entry("plan", startedAt = "2026-10-14T09:00:00+01:00").copy(args = listOf("/h/.crit/plans/x/current.md"))
        val newer = entry("newer", startedAt = "2026-10-14T08:30:00Z")
        assertEquals("newer", CritSessionLocator.pick(listOf(diff, plan, newer), "main")?.key)
    }

    @Test
    fun `ambiguous without branch match`() {
        assertNull(CritSessionLocator.pick(listOf(entry("a", branch = "x"), entry("b", branch = "y")), "main"))
    }

    @Test
    fun `liveness follows the pid`() {
        assertTrue(CritSessionLocator.isAlive(entry("a").copy(pid = ProcessHandle.current().pid())))
        assertFalse(CritSessionLocator.isAlive(entry("a").copy(pid = Long.MAX_VALUE)))
    }
}
