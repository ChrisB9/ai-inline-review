# Claude Review

A PhpStorm / IntelliJ plugin for human-curated code review with Claude Code.

You attach review notes to lines in the editor or diff view. Each note is a small JSON file in your
project. Claude Code reads those files, replies in-thread, and — for notes you mark as **Action** —
implements the change. Notes, replies and Claude's answers all live in the note files, so the review
is just shared state in your repo.

## In-editor UI

- **Add a note**: select lines → press `Ctrl+Alt+C`, or click the `+` that appears in the gutter on
  the selected line. A draft card opens inline; type your note (Markdown), then **Add**.
- **Inline cards**: each note renders as a card between the lines (author chip, timestamp, rendered
  body, threaded replies). Edit and reply happen inline; images can be inserted/pasted.
- **Per-note mode** (chip in the card header, click to toggle):
  - **💬 Comment** (default) — you want Claude's reply/opinion; it does not change code.
  - **⚡ Action** — "work on it"; Claude implements the change and replies.
- **Resolve / Edit / Delete** in the card header. Resolve or Delete removes the note and its file.

## Sessions

Notes are scoped to a **session**, which is either **Planning / Spec** or **Code**. In the Notes tool
window: switch sessions to filter the view, **New session** to start one, **Finish** to delete a
session and all its notes. New notes attach to the active session.

## The note file

Each note is `.claude/review/inbox/<id>.json`; the filesystem mirrors the live notes (removing a note
deletes its file):

```json
{ "version": 1, "status": "pending", "item": {
    "id": "uuid", "filePath": "src/...", "startLine": 13, "endLine": 14,
    "snippet": "the exact selected code", "comment": "the note (markdown)",
    "mode": "comment", "sessionId": "uuid", "author": "you", "createdAt": 0,
    "attachments": [".claude/review/assets/x.png"],
    "replies": [ { "author": "Claude", "text": "...", "createdAt": 0 } ]
} }
```

Claude replies by appending to `item.replies` with `author: "Claude"`. The plugin watches the inbox
folder and reloads external edits, so replies show up in the card automatically — no `done/` files.

## Claude skill

On first open the plugin installs `.claude/skills/claude-review/SKILL.md`. Run **`/claude-review`** in
Claude Code to drive the loop: it reads the inbox, replies to Comment notes and implements Action
notes, writing answers back into the note files.

## Build / run

Requires a JDK; the Gradle wrapper provisions JDK 21 via the Foojay resolver.

```bash
./gradlew runIde          # launch a sandbox PhpStorm with the plugin loaded
./gradlew buildPlugin     # build/distributions/claude-review-*.zip (Settings → Plugins → Install from Disk)
```

## Roadmap

- Live line-number re-anchoring after edits
- Watch mode for the skill (auto-react to new notes)
