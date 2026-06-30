package co.cben.dev.aiinlinereview

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.extension

/**
 * Keeps .claude/review/inbox in sync with the store: one file per note (`<id>.json`).
 * Removing/resolving a note deletes its file, so the filesystem always mirrors the live notes.
 */
object ReviewFiles {

    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val parser = Gson()
    private val UUID_RE = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

    // What we last wrote per note, to tell our own writes apart from external (Claude) edits.
    private val lastWritten = mutableMapOf<String, String>()

    fun isSelfWrite(id: String, content: String): Boolean = lastWritten[id]?.trim() == content.trim()

    fun readNote(content: String): ReviewComment? =
        runCatching { parser.fromJson(content, NoteFile::class.java)?.item }.getOrNull()

    private class NoteFile {
        @JvmField var item: ReviewComment? = null
    }

    fun inboxDir(project: Project): Path? {
        val base = project.basePath ?: return null
        val dir = Paths.get(base, ".claude", "review", "inbox")
        Files.createDirectories(dir)
        return dir
    }

    /** Write a file for every current note and delete per-note files whose id is gone. */
    fun sync(project: Project, comments: List<ReviewComment>): Path? {
        val dir = inboxDir(project) ?: return null
        val ids = comments.mapTo(HashSet()) { it.id }
        comments.forEach { comment ->
            val payload = mapOf("version" to 1, "status" to "pending", "item" to comment)
            val json = gson.toJson(payload)
            Files.writeString(dir.resolve("${comment.id}.json"), json)
            lastWritten[comment.id] = json
        }
        Files.list(dir).use { stream ->
            stream.filter { it.extension == "json" }.forEach { path ->
                val name = path.fileName.toString().removeSuffix(".json")
                if (UUID_RE.matches(name) && name !in ids) {
                    runCatching { Files.deleteIfExists(path) }
                }
            }
        }
        refresh(dir)
        return dir
    }

    fun delete(project: Project, id: String) {
        val dir = inboxDir(project) ?: return
        runCatching { Files.deleteIfExists(dir.resolve("$id.json")) }
        refresh(dir)
    }

    private fun refresh(dir: Path) {
        LocalFileSystem.getInstance().refreshAndFindFileByPath(dir.toString())
    }
}
