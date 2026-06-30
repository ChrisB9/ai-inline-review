package co.cben.dev.claude.review

/**
 * One review note: a code range plus what the human wants Claude to do with it, with author,
 * timestamp and a reply thread.
 * Plain mutable fields so the platform XML serializer (persistence) and Gson (export) handle it.
 */
class ReviewComment {
    @JvmField var id: String = ""
    @JvmField var filePath: String = ""
    @JvmField var startLine: Int = 0
    @JvmField var endLine: Int = 0
    @JvmField var snippet: String = ""
    @JvmField var comment: String = ""
    @JvmField var source: String = "editor"
    @JvmField var author: String = ""
    @JvmField var createdAt: Long = 0
    @JvmField var mode: String = MODE_COMMENT
    @JvmField var sessionId: String = ""
    @JvmField var attachments: MutableList<String> = mutableListOf()
    @JvmField var replies: MutableList<ReviewReply> = mutableListOf()

    companion object {
        const val MODE_COMMENT = "comment"
        const val MODE_ACTION = "action"
    }
}

/**
 * One reply in a note's thread.
 */
class ReviewReply {
    @JvmField var author: String = ""
    @JvmField var text: String = ""
    @JvmField var createdAt: Long = 0
}
