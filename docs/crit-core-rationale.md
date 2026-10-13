# Crit core: rationale

## Scope
- Headless core of the crit frontend; no UI yet
- Code: `src/main/kotlin/co/cben/dev/aiinlinereview/crit/`
- Full story: `docs/crit-frontend-plan.md`

## Units
| Unit | Job |
|---|---|
| `CritModel.kt` | DTOs mirroring crit JSON; null lists become empty |
| `CritClient.kt` | Only HTTP code; 503 loading retry (max 10s); `api_version` 1 gate |
| `SseParser.kt` | `/api/events` lines to `CritEvent` |
| `CritSessionLocator.kt` | Reads `~/.crit/sessions`, matches project root through symlinks, picks newest on branch |
| `CritSessionController.kt` | Snapshot, refresh, events, writes, sync nudge |
| `CritSessionHub.kt` | Attach/detach, tick, SSE reader; plain class, fully tested |
| `CritSessionService.kt` | Platform wiring only |

## Decisions
| Decision | Reason |
|---|---|
| Talk to the crit daemon over HTTP, never write `review.json` | Single writer; browser stays in sync |
| Agent starts sessions, IDE attaches | Agent workflow unchanged |
| Poll comments every 2s while the IDE is focused | crit v0.21.0 sends no SSE for comment create/edit |
| Nudge by re-sending an unchanged resolve state | Touching `review.json` emits nothing; crit only broadcasts on content change |
| Sync after a write is best effort | A failed refresh must not make the UI retry and duplicate a comment |
| SSE connects 3s after attach | crit counts SSE clients as browsers and would skip auto-opening one |
| API rejection remembered per daemon process | A restarted daemon reuses its session key |
| Newer session on the current branch takes over | The agent's latest `crit` call is what the user looks at |

## Known gaps (crit v0.21.0)
- No SSE for comment create/edit: polling + nudge
- Browser ignores `finish` from another client
- No API for "waiting for agent": state kept in memory
- Outside-diff comments freeze after a round; UI must re-place them by `anchor`

## Build
- JDK 21 required; Gradle 8.10.2 cannot run on JDK 25
- `./gradlew test` runs 46 unit tests without an IDE
