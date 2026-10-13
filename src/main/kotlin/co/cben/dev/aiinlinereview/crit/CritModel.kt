package co.cben.dev.aiinlinereview.crit

import com.google.gson.FieldNamingPolicy
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.TypeAdapter
import com.google.gson.TypeAdapterFactory
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter

// All constructor params have defaults so Gson uses the no-arg constructor and keeps defaults for missing keys.
data class SessionEntry(
    val key: String = "",
    val pid: Long = 0,
    val port: Int = 0,
    val host: String = "",
    val cwd: String = "",
    val args: List<String> = emptyList(),
    val branch: String = "",
    val reviewPath: String = "",
    val startedAt: String = "",
) {
    val baseUrl: String get() = "http://${host.ifBlank { "127.0.0.1" }}:$port"
    val isPlan: Boolean get() = args.any { "/.crit/plans/" in it.replace('\\', '/') }
}

data class Health(val status: String = "", val apiVersion: Int = 0)

data class CritConfig(val author: String = "", val version: String = "", val agentCmdEnabled: Boolean = false)

data class FileEntry(
    val path: String = "",
    val oldPath: String = "",
    val status: String = "",
    val fileType: String = "",
    val commentCount: Int = 0,
    val additions: Int = 0,
    val deletions: Int = 0,
    val orphaned: Boolean = false,
)

data class SessionInfo(
    val mode: String = "",
    val branch: String = "",
    val baseRef: String = "",
    val reviewRound: Int = 0,
    val files: List<FileEntry> = emptyList(),
    val reviewComments: List<Comment> = emptyList(),
    val cwd: String = "",
    val sessionKey: String = "",
)

data class Reply(
    val id: String = "",
    val body: String = "",
    val author: String = "",
    val createdAt: String = "",
    val reviewRound: Int = 0,
)

data class Comment(
    val id: String = "",
    val startLine: Int = 0,
    val endLine: Int = 0,
    val side: String = "",
    val body: String = "",
    val quote: String = "",
    val anchor: String = "",
    val drifted: Boolean = false,
    val author: String = "",
    val scope: String = "",
    val createdAt: String = "",
    val updatedAt: String = "",
    val resolved: Boolean = false,
    val resolvedRound: Int = 0,
    val carriedForward: Boolean = false,
    val reviewRound: Int = 0,
    val replies: List<Reply> = emptyList(),
) {
    val isOld: Boolean get() = side == "old"
    val isFileLevel: Boolean get() = scope == "file"
}

data class CommentDraft(
    val startLine: Int = 0,
    val endLine: Int = 0,
    val side: String = "",
    val body: String = "",
    val quote: String = "",
    val author: String = "",
    val scope: String = "",
)

data class FinishStats(val durationSeconds: Long = 0, val filesReviewed: Int = 0, val commentsSubmitted: Int = 0)

data class FinishResult(
    val status: String = "",
    val prompt: String = "",
    val approved: Boolean = false,
    val nextCommand: String = "",
    val stats: FinishStats = FinishStats(),
)

data class Attachment(val filename: String = "", val originalFilename: String = "", val url: String = "")

data class CritEvent(val type: String, val content: String)

object CritJson {
    val gson: Gson = GsonBuilder()
        .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
        .registerTypeAdapterFactory(NullListAsEmpty)
        .create()

    inline fun <reified T> parse(json: String): T = gson.fromJson(json, object : TypeToken<T>() {}.type)
}

// Go encodes nil slices as null; Kotlin callers must never see a null List.
private object NullListAsEmpty : TypeAdapterFactory {
    override fun <T> create(gson: Gson, type: TypeToken<T>): TypeAdapter<T>? {
        if (!List::class.java.isAssignableFrom(type.rawType)) return null
        val delegate = gson.getDelegateAdapter(this, type)
        return object : TypeAdapter<T>() {
            override fun write(out: JsonWriter, value: T?) = delegate.write(out, value)

            @Suppress("UNCHECKED_CAST")
            override fun read(reader: JsonReader): T {
                if (reader.peek() == JsonToken.NULL) {
                    reader.nextNull()
                    return emptyList<Any>() as T
                }
                return delegate.read(reader)
            }
        }
    }
}
