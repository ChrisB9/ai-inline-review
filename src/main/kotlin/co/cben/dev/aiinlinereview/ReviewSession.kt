package co.cben.dev.aiinlinereview

/**
 * A note-writing session. Notes are scoped to a session; switching sessions filters the view,
 * finishing a session deletes its notes. `kind` distinguishes planning/spec work from code work.
 */
class ReviewSession {
    @JvmField var id: String = ""
    @JvmField var name: String = ""
    @JvmField var kind: String = KIND_CODE
    @JvmField var createdAt: Long = 0

    companion object {
        const val KIND_PLAN = "plan"
        const val KIND_CODE = "code"
    }
}
