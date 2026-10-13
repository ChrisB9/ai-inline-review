package co.cben.dev.aiinlinereview.crit

import java.io.Closeable
import java.util.stream.Stream

class SseParser {
    private var type = ""
    private val data = StringBuilder()

    fun feed(line: String): CritEvent? {
        when {
            line.isEmpty() -> return flush()
            line.startsWith(":") -> Unit
            line.startsWith("event:") -> type = line.removePrefix("event:").trim()
            line.startsWith("data:") -> {
                if (data.isNotEmpty()) data.append('\n')
                data.append(line.removePrefix("data:").removePrefix(" "))
            }
        }
        return null
    }

    private fun flush(): CritEvent? {
        if (type.isEmpty() && data.isEmpty()) return null
        val payload = runCatching { CritJson.parse<RawEvent>(data.toString()) }.getOrNull()
        val event = CritEvent(type.ifEmpty { payload?.type.orEmpty() }, payload?.content.orEmpty())
        type = ""
        data.setLength(0)
        return event
    }

    private data class RawEvent(val type: String = "", val content: String = "")
}

class CritEventStream(private val lines: Stream<String>) : Closeable {
    fun forEach(onEvent: (CritEvent) -> Unit) {
        val parser = SseParser()
        lines.forEach { line -> parser.feed(line)?.let(onEvent) }
    }

    override fun close() = lines.close()
}
