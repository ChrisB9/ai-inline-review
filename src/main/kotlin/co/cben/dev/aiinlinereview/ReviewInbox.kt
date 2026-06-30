package co.cben.dev.aiinlinereview

import com.google.gson.Gson
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.io.path.extension

/**
 * Reads notes back out of .claude/review/inbox snapshots, for the manual "Import" action.
 */
object ReviewInbox {

    private val LOG = logger<ReviewInbox>()
    private val gson = Gson()

    fun loadAll(project: Project): List<ReviewComment> {
        val basePath = project.basePath ?: return emptyList()
        val dir = Paths.get(basePath, ".claude", "review", "inbox")
        if (!Files.isDirectory(dir)) return emptyList()

        val items = mutableListOf<ReviewComment>()
        Files.list(dir).use { stream ->
            stream.filter { it.extension == "json" }.sorted().forEach { path ->
                runCatching {
                    val file = gson.fromJson(Files.readString(path), InboxFile::class.java) ?: return@runCatching
                    file.item?.let { items.add(it) }
                    items.addAll(file.items)
                }.onFailure { LOG.warn("Failed to parse inbox $path", it) }
            }
        }
        return items
    }

    private class InboxFile {
        @JvmField var item: ReviewComment? = null
        @JvmField var items: MutableList<ReviewComment> = mutableListOf()
    }
}
