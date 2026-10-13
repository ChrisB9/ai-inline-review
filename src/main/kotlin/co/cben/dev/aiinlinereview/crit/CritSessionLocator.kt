package co.cben.dev.aiinlinereview.crit

import java.nio.file.Files
import java.nio.file.Path
import java.time.OffsetDateTime

object CritSessionLocator {
    fun registryDir(home: Path = Path.of(System.getProperty("user.home"))): Path = home.resolve(".crit").resolve("sessions")

    fun readEntries(dir: Path): List<SessionEntry> {
        if (!Files.isDirectory(dir)) return emptyList()
        val files = Files.list(dir).use { stream -> stream.filter { it.fileName.toString().endsWith(".json") }.toList() }
        return files.mapNotNull { file ->
            runCatching {
                CritJson.parse<SessionEntry>(Files.readString(file)).copy(key = file.fileName.toString().removeSuffix(".json"))
            }.getOrNull()
        }.filter { it.port > 0 }
    }

    fun forProject(entries: List<SessionEntry>, projectRoot: Path): List<SessionEntry> {
        val root = real(projectRoot)
        return entries.filter { it.cwd.isNotBlank() && real(Path.of(it.cwd)) == root }
    }

    fun pick(candidates: List<SessionEntry>, currentBranch: String?): SessionEntry? {
        candidates.singleOrNull()?.let { return it }
        return candidates.filter { it.branch == currentBranch }.maxByOrNull { startedAt(it) }
    }

    fun isAlive(entry: SessionEntry): Boolean = ProcessHandle.of(entry.pid).map { it.isAlive }.orElse(false)

    private fun startedAt(entry: SessionEntry): OffsetDateTime =
        runCatching { OffsetDateTime.parse(entry.startedAt) }.getOrDefault(OffsetDateTime.MIN)

    private fun real(path: Path): Path = runCatching { path.toRealPath() }.getOrElse { path.toAbsolutePath().normalize() }
}
