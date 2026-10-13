# Crit frontend: implementation story

## Story at a glance
| Chapter | Question | Answer |
|---|---|---|
| 1 Where we are | What does the plugin do today? | Own review store + own agent skill |
| 2 Where we want to be | What should change? | IDE becomes a second crit frontend next to the browser |
| 3 What crit offers | Can the IDE plug into crit? | Yes: local daemon HTTP API + SSE, `api_version: 1` |
| 4 Options | Which role, which transport? | IDE + browser in sync; daemon HTTP client |
| 5 Pros and cons | Is it worth it? | Yes, if the undocumented API risk is managed |
| 6 Implementation | How do we build it? | 9 phases, spike first |
| 7 Risks and upstream | What can break, what to fix in crit? | Sync gaps G1-G4, orphaned comments |
| 8 Next steps | What happens now? | Spike, then implementation plan |

## 1 Where we are
### Plugin 0.1.1
- ~2k LOC Kotlin, since-build 242, built against PhpStorm 2024.2 (`build.gradle.kts:20`)
- Own persistent store of notes and sessions (`src/main/kotlin/co/cben/dev/aiinlinereview/ReviewStore.kt:15`)
- Notes mirrored to `.claude/review/inbox/<id>.json` (`ReviewFiles.kt:42`)
- Inline cards via embedded components (`ReviewGutterService.kt:115`, `ReviewCardPanel.kt:38`)
- Card callbacks are the seam: `onSave`, `onResolve`, `onDelete`, `onReply` (`ReviewCardPanel.kt:53-58`)
- Comment/Action modes, Plan/Code sessions (`ReviewComment.kt:8`, `ReviewSession.kt:7`)
- Web screenshot capture server (`ReviewWebServer.kt:25`)
- Bundled `/claude-review` skill drives the agent (`ReviewSkill.kt:12`)
- No tests (`src/test` missing)

### Pain
- Second review system next to crit, which is used daily
- Claude-only loop; crit already supports ~15 agents
- No re-anchoring across agent rounds, no share, no PR push/pull

## 2 Where we want to be
### User stories
| As a reviewer I want to | So that |
|---|---|
| See crit comments as cards in my JetBrains editor | I review with go-to-definition, find usages, inspections |
| Open the crit diff in the JetBrains diff viewer | I use the native diff instead of the browser diff |
| Add, reply, edit, resolve comments in the IDE | I never leave the IDE during a crit round |
| Comment on code outside the diff | I can point the agent at callers, configs, related classes |
| Press Finish Review in the IDE | The agent's blocking `crit` continues |
| Keep the browser open on the same session | I can switch devices or share without losing state |
| Review `crit plan` files in the IDE | Plans get the same flow as code |

### Success criteria
- A comment written in either UI appears in the other within ~2s
- Finish Review from either UI unblocks the agent
- Agent skill and `crit` invocation unchanged
- Plugin shrinks: inbox store, skill and web server removed

## 3 What crit offers (v0.21.0)
### Discovery
- Session registry `~/.crit/sessions/<key>.json`: `pid`, `port`, `host`, `cwd`, `args`, `branch`, `review_path`, `started_at`
- One daemon per session; several per project are normal (branch, plan, files)
- Liveness: PID alive and `GET /api/health` returns `{status:"ok", api_version:1}`

### API surface used
| Route | Use |
|---|---|
| GET `/api/config` | `author`, `version`, `agent_cmd_enabled` |
| GET `/api/session` | `mode`, `branch`, `base_ref`, `review_round`, `files[]`, `review_comments[]` |
| GET/POST `/api/file/comments?path=` | List / create line and file comments (`side`, `quote`, `scope`) |
| PUT/DELETE `/api/comment/{id}?path=` | Edit body / delete |
| PUT `/api/comment/{id}/resolve?path=` | `{resolved}` |
| POST/PUT/DELETE `/api/comment/{id}/replies[/{rid}]?path=` | Reply CRUD |
| GET/POST `/api/comments` | Review-level list / create; never DELETE (wipes all) |
| `/api/review-comment/{id}[/resolve\|/replies]` | Review-level edit, resolve, replies |
| POST `/api/attachments` | Image upload, then `![](attachments/<file>)` in body |
| POST `/api/finish` | Finish Review: `approved`, `prompt`, `comments`, `stats` |
| POST `/api/agent/request` | "Ask agent" via `agent_cmd` |
| GET `/api/events` | SSE: `comments-changed`, `file-changed`, `edit-detected`, `finish`, `server-shutdown` |
- Off-limits: `/api/review-cycle`, `/api/round-complete`; they belong to the agent's `crit`

### Security model
- No auth; loopback Host check; CSRF check passes without `Sec-Fetch-Site`
- Plain JDK `HttpClient` works; a JCEF page on another origin would not

### Round lifecycle
1. Agent runs `crit`; it blocks on the daemon until Finish Review
2. Finish: daemon renders prompt, `approved = unresolved == 0`, emits SSE `finish`
3. Agent edits code, replies via `crit comment --reply-to`, runs `crit` again
4. Round complete: LCS remap + anchor check, `drifted`, `carried_forward`, `review_round++`, SSE `file-changed`
5. Approved: agent's `crit` exits and stops the daemon

## 4 Options
### Role of the IDE
| Option | Pros | Cons | Verdict |
|---|---|---|---|
| IDE replaces browser | Simplest sync story | Loses phone/remote review, share UI | Rejected |
| IDE + browser in sync | Best of both | Must handle sync gaps | Chosen |
| Keep own store, bridge to crit | Plugin works without crit | Two sources of truth, double maintenance | Rejected |

### Who starts sessions
| Option | Pros | Cons | Verdict |
|---|---|---|---|
| Agent starts, IDE attaches | Zero agent changes | IDE idle until agent runs `crit` | Chosen |
| IDE can also start | Review without agent | Extra flow, session handoff to agent | Later |
| IDE only starts | Full IDE control | Breaks `/crit` skill | Rejected |

### Transport
| Option | Pros | Cons | Verdict |
|---|---|---|---|
| A Daemon HTTP + SSE | Single writer, full CRUD, Finish works | Undocumented API | Chosen |
| B `review.json` + `crit comment` CLI | Documented CLI | No edit/delete, no Finish, two writers | Rejected |
| C Hybrid A + read-only file fallback | Browse old reviews | Two code paths for little value | Rejected |

### Legacy features
| Option | Pros | Cons | Verdict |
|---|---|---|---|
| Replace everything | Smallest plugin, one model | Comment/Action mode lost | Chosen |
| Keep Comment/Action via tag | Keeps intent signal | Needs crit skill changes | Rejected |
| Two backends | No user loses anything | Double code, double tests | Rejected |

## 5 Pros and cons of the chosen path
### Pros
| Pro | Effect |
|---|---|
| One backend, one source of truth | No state drift between plugin and crit |
| Agent loop unchanged | `/crit` skill keeps working |
| crit features for free | Re-anchoring, plan versions, share, PR push/pull, `agent_cmd`, ~15 agents |
| Native JetBrains diff and navigation | What the browser cannot offer |
| Smaller plugin | ~900 LOC removed |
| Browser stays usable | Phone/remote review on the same session |
| Single writer | No races on `review.json` |

### Cons
| Con | Severity | Mitigation |
|---|---|---|
| Undocumented daemon API | High | Fixtures, contract test, `api_version` gate, upstream docs |
| Sync gaps G1-G4 | Medium | Polling + nudge now, upstream fix later |
| Hard dependency on crit | Medium | Clear empty state, README install steps |
| crit release pace | Medium | Contract test per crit release |
| IDE cannot start sessions | Low-Medium | Possible later as separate action |
| Breaking for 0.1.x users | Low | Change notes, README |
| Comment/Action distinction lost | Low | Every crit comment means "address this" |
| Diff mismatch in range-focus sessions | Low | Document; optional `/api/file/diff` later |
| Outside-diff comments orphan after a round | Low | IDE re-places them by `anchor` |
| Plan files outside the project | Low | Open read-only via local file system |

## 6 Implementation
### Target layout
| Unit | File | Job |
|---|---|---|
| `CritModel` | `crit/CritModel.kt` | DTOs: `SessionEntry`, `SessionInfo`, `FileEntry`, `Comment`, `Reply`, `FinishResult`, `CritEvent` |
| `CritClient` | `crit/CritClient.kt` | JDK `HttpClient` + Gson; typed calls; SSE line parser; `api_version` check |
| `CritSessionLocator` | `crit/CritSessionLocator.kt` | `WatchService` on `~/.crit/sessions` + 5s rescan; cwd/branch match; health check |
| `CritSessionService` | `crit/CritSessionService.kt` | Project service: attach/detach, comment cache, round state, polling, nudge, listeners |
| `CritPathMapper` | `crit/CritPathMapper.kt` | API path <-> `VirtualFile`; plan `<slug>.md` -> `~/.crit/plans/<slug>/current.md` |
| `CritDiffOpener` | `crit/CritDiffOpener.kt` | `SimpleDiffRequest`: git `base_ref` content vs working tree; plan versions |
| `CritDiffExtension` | `crit/CritDiffExtension.kt` | Tags left diff editor with `side=old` + path so cards render there |
| `CritPanel` | `ui/CritPanel.kt` | Tool window: picker, state, Finish, Files tab, Threads tab |
| `ReviewCardPanel` | existing | Rendering only; takes `Comment` DTO instead of `ReviewComment` |
| `ReviewGutterService` | existing | Decorates editors from `CritSessionService` instead of `ReviewStore` |

### Phase 0: spike (throwaway)
1. Start `crit --no-open` on a scratch git repo; capture JSON of every used route as fixtures
2. Nudge a: touch `review.json` mtime; check browser refresh and no data change
3. Nudge b: re-PUT `/resolve` with current value; check `resolved_round` unchanged
4. G4: connect SSE immediately vs after 3s; check browser auto-open
5. Outside diff: comment on unchanged file and on off-hunk line; check browser rendering
6. Run one agent round; confirm orphan behavior and `anchor` text
- Done when: nudge chosen, fixtures in `src/test/resources/crit/v0.21.0/`, findings in the spec

### Phase 1: client and model
1. Add `src/test` with JUnit 5; platform test framework dependency
2. `CritModel` DTOs mirror crit JSON field names (`start_line` via `@SerializedName`)
3. `CritClient` calls: `health`, `config`, `session`, `fileComments`, `createComment`, `editComment`, `deleteComment`, `resolve`, `reply*`, `reviewComments*`, `upload`, `finish`, `askAgent`
4. 503 `loading`: retry with `Retry-After`, max 10s
5. `events(): Flow<CritEvent>` over `BodyHandlers.ofLines()`; ignore `:` heartbeats
- Tests: `HttpServer` fake serving fixtures; SSE parser cases; version gate
- Done when: all calls round-trip against fixtures

### Phase 2: discovery and session service
1. Locator parses session files, resolves symlinks, drops dead PIDs and failed health checks
2. Match `cwd` == project root; several: prefer current git branch, else picker
3. Service attaches, loads config, session, comments; exposes `StateFlow<CritState>`
4. SSE starts 3s after attach; reconnect backoff 1s -> 30s
5. Poll comments every 2s while IDE frame is active (`ApplicationActivationListener`); hash payload
6. Writes: API call, update cache from response, then nudge if the route emits no SSE
- Tests: locator matching, cache diffing, state transitions with fake client
- Done when: service tracks a live crit session in `runIde` logs

### Phase 3: editor cards
1. `ReviewGutterService.decorate` reads comments for the file's API path from the service (`ReviewGutterService.kt:115`)
2. Anchor cards to `RangeMarker`; on `drifted` or orphaned comments search `anchor` text in the live document
3. Card chips: round, `carried`, `drifted`, `outside diff`; drop mode chip
4. Card callbacks map to client calls; Edit only when `author` == config author
5. Draft (`ReviewGutterService.kt:159`): partial line selection becomes `quote`
6. Image paste: upload via `/api/attachments`, embed link; render from `<review_path>/attachments/`
7. Files outside repo root: draft action disabled, tooltip "crit can only review files inside the repo"
- Tests: platform test with fake client; inlays per comment; callbacks hit client
- Done when: create/reply/edit/resolve/delete in IDE show in browser within ~2s

### Phase 4: tool window
1. Replace `ClaudeReviewPanel` (`ClaudeReviewPanel.kt:36`) with `CritPanel`
2. Header: session picker (2+ sessions), `Round N`, state badge, Finish Review with unresolved count, Open in browser
3. Files tab: status, +/-, comment count; double-click diff, Enter editor
4. Threads tab: review-level first, grouped by file, unresolved/all filter, navigate on click
5. Finish: confirm on approve; show result; state `Agent working · N edits` until `file-changed`
6. Empty state: "No crit session for this project. Run `crit` in your agent."
- Done when: full round driven from the tool window

### Phase 5: diff
1. Base content via Git4Idea at `base_ref`; current side = the real `VirtualFile` so it stays editable
2. Register `CritDiffExtension` (`com.intellij.diff.DiffExtension`) to tag the left editor
3. `decorate` renders `side:"old"` comments on tagged left editors; drafts there post `side:"old"`
4. "Show crit diff" action on files in the session
- Done when: cards appear on both sides; left-side comment shows in browser on the old side

### Phase 6: plan mode
1. Mapper resolves `<slug>.md` to `~/.crit/plans/<slug>/current.md` via `LocalFileSystem`
2. Editors for plan files set viewer mode (agent rewrites the file each round)
3. "Show plan diff": previous `vNNN.md` vs `current.md`
- Done when: `crit plan` round works end to end in the IDE

### Phase 7: removal
1. Delete `ReviewStore`, `ReviewInbox`, `ReviewFiles`, `ReviewSession`, `ReviewExporter`, `ReviewSkill`, `SendToClaudeAction`, `ReviewWebServer`
2. Delete `.claude/skills/claude-review/SKILL.md` and its installer call
3. Remove `AiInlineReview.Send` action from `plugin.xml`; add `Git4Idea` dependency
- Done when: build, plugin verifier and tests green

### Phase 8: release 0.2.0
1. README: crit install, attach flow, known limitations G1-G4, outside-diff behavior
2. `plugin.xml` description and change notes: requires crit >= 0.21, inbox removed
3. Manual checklist in `runIde`: each sync direction, finish from each UI, plan round, outside-diff comment
4. Opt-in Gradle task `critContractTest` against real `crit` binary
- Done when: signed 0.2.0 on Marketplace

## 7 Risks and upstream
### Sync gaps
| Gap | Cause | Now | Upstream fix |
|---|---|---|---|
| G1 | Comment create/edit, review-comment CRUD emit no SSE | Poll + nudge | Emit `comments-changed` from every write handler |
| G2 | Browser ignores SSE `finish` | Document | Browser shows waiting dialog on `finish` |
| G3 | Waiting-for-agent not readable | In-memory state | `/api/session` exposes `waiting_for_agent` |
| G4 | SSE client counts as browser | Delay SSE 3s | `/api/events?client=editor` excluded |

### Outside-diff comments
| Case | crit v0.21.0 behavior | Plugin handling |
|---|---|---|
| Unchanged line in changed file | Accepted; no hunk check | Card with `outside diff` chip |
| Unchanged repo file | Accepted; registered as `modified`, no hunks | Card with `outside diff` chip |
| After next round | File dropped from list; comments orphaned, line numbers frozen | Re-place by `anchor`; `drifted` if not found |
| File outside repo root | 404 | Action disabled |
| Resolved orphan | Hidden from `/api/session` | Threads tab "all" filter only |
- Upstream: status `unchanged` instead of `modified`; keep commented files across rounds

### Other risks
| Risk | Mitigation |
|---|---|
| API changes without version bump | Contract test per crit release; pin supported versions in README |
| Maintainer declines public API | Open issue early; keep all HTTP in `CritClient` |
| EDT violations from network callbacks | All IO on background scope; UI via `invokeLater` (lesson from 0.1.1) |
| Polling cost | Only while frame active and attached; hash before repaint |

### Upstream roadmap
1. Issue on crit: propose editor-client API contract and G1-G4
2. PR: SSE on all comment writes + `client=editor`
3. PR: `waiting_for_agent` in `/api/session`; browser `finish` listener
4. PR: `docs/api.md`; comment payloads in SSE `content`
5. Plugin: gate workarounds on `/api/config.version`

## 8 Next steps
1. Spike (phase 0)
2. Open the crit issue in parallel
3. Implementation plan from this story
4. Phases 1-8 on branch `crit-frontend`

## Decisions
| Decision | Reason | Date |
|---|---|---|
| IDE + browser in sync on one crit session | JetBrains navigation plus crit tooling | 2026-10-13 |
| Agent starts sessions, IDE attaches | Zero agent changes | 2026-10-13 |
| Daemon HTTP + SSE only | Single writer, full CRUD, Finish | 2026-10-13 |
| Replace inbox, sessions, skill, web server | One backend | 2026-10-13 |
| Workarounds now, upstream later | Unblock without waiting on crit | 2026-10-13 |
| Same plugin id, 0.2.0 | Existing users get the update | 2026-10-13 |
| Allow outside-diff comments on repo files | crit accepts them; key IDE use case | 2026-10-14 |
| `WatchService` for session registry | `~/.crit` is outside the project VFS | 2026-10-14 |
