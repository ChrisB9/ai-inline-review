package co.cben.dev.claude.review

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import java.nio.file.Files
import java.nio.file.Paths

/**
 * Drops a Claude Code skill into the project on first run, so the user can just start the skill
 * to drive the review loop. Never overwrites an existing file (the user may have customized it).
 */
object ReviewSkill {

    private val LOG = logger<ReviewSkill>()

    fun install(project: Project) {
        val base = project.basePath ?: return
        val file = Paths.get(base, ".claude", "skills", "claude-review", "SKILL.md")
        if (Files.exists(file)) return
        runCatching {
            Files.createDirectories(file.parent)
            Files.writeString(file, CONTENT)
        }.onFailure { LOG.warn("Could not install claude-review skill", it) }
    }

    private val CONTENT = """
        ---
        name: claude-review
        description: Run the human-curated code-review loop. Read review notes the user wrote in the
          editor (via the Claude Review plugin), reply to them and act on them. Use when the user says
          "run claude review", "process my review notes", "check the review inbox", or starts this skill.
        ---

        # Claude Review loop

        The Claude Review IntelliJ/PhpStorm plugin lets the user attach notes to lines of code. Each
        note is one JSON file under `.claude/review/inbox/<id>.json`:

        ```json
        { "version": 1, "status": "pending", "item": {
            "id": "...", "filePath": "src/...", "startLine": 10, "endLine": 14,
            "snippet": "the exact selected code", "comment": "the note (markdown)",
            "mode": "comment" | "action", "sessionId": "...", "author": "...",
            "attachments": ["..."], "replies": [ { "author": "...", "text": "...", "createdAt": 0 } ]
        } }
        ```

        ## What to do

        1. List `.claude/review/inbox/*.json`. For each note, read `item`.
        2. Decide if it needs your attention: the last reply is from the user (not "Claude"), or there
           is no Claude reply yet. Skip notes already answered.
        3. Locate the code with `filePath` + `snippet` (re-anchor by `snippet` if line numbers drifted).
        4. Act by `mode`:
           - **comment** — DO NOT change code. Answer/discuss the note.
           - **action** — Implement the requested change in `filePath`, then summarize what you did.
        5. **Reply by editing the note's JSON file**: append an object to `item.replies` with
           `author: "Claude"`, your `text` (markdown), and a `createdAt` (epoch millis). Preserve the
           rest of the file. Do NOT create a `done/` directory — the plugin watches `inbox/` and shows
           your reply in the card automatically.
        6. If the note's image attachments are referenced (`.claude/review/assets/*`), read them for
           context.

        ## Sessions

        Notes carry a `sessionId`. A session is either planning/spec (`kind: plan`) or code
        (`kind: code`). In a plan session, favor discussion, specs and proposals over editing code;
        in a code session, implement `action` notes. Process the notes the user points you at.

        ## Watch mode (react automatically)

        To react to new notes without being asked again, run this skill on a loop:

        - `/loop claude-review` — re-invokes the skill on an interval; each tick, re-scan the inbox and
          handle any note whose latest reply is NOT from "Claude" (and any `action` note not yet done).
        - Between ticks there is nothing to do if every note's last reply is already from Claude — say
          so briefly and wait for the next tick.
        - Stop when the user ends the loop.

        Keep replies concise and reference `file:line` where useful.
    """.trimIndent() + "\n"
}
