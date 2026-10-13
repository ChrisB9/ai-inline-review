package co.cben.dev.aiinlinereview.crit

import org.junit.Assert.assertEquals
import org.junit.Test

class SseParserTest {
    private fun parse(vararg lines: String): List<CritEvent> {
        val parser = SseParser()
        return lines.mapNotNull { parser.feed(it) }
    }

    @Test
    fun `parses typed events and skips heartbeats`() {
        val events = parse(
            "event: comments-changed", """data: {"type":"comments-changed","content":""}""", "",
            ":", "",
            "event: edit-detected", """data: {"type":"edit-detected","content":"3"}""", "",
        )
        assertEquals(listOf(CritEvent("comments-changed", ""), CritEvent("edit-detected", "3")), events)
    }

    @Test
    fun `falls back to payload type and tolerates bad json`() {
        assertEquals(listOf(CritEvent("finish", "x")), parse("""data: {"type":"finish","content":"x"}""", ""))
        assertEquals(listOf(CritEvent("server-shutdown", "")), parse("event: server-shutdown", "data: not json", ""))
    }

    @Test
    fun `stream reads events from the daemon`() {
        FakeCrit().use { fake ->
            val body = "event: file-changed\ndata: {\"type\":\"file-changed\",\"content\":\"session\"}\n\n:\n\n"
            fake.on("GET", "/api/events", body)
            val seen = mutableListOf<CritEvent>()
            CritClient(fake.baseUrl).openEvents().use { it.forEach { event -> seen += event } }
            assertEquals(listOf(CritEvent("file-changed", "session")), seen)
        }
    }
}
