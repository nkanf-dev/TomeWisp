# SKMB-2026-07-19-020: Observable Game State and Request Visibility

- status: accepted
- decided_by: designer
- approval_source: the designer required that all player-observable game/client information be queryable through one elegant Tool, explicitly separated it from deep recipe/guide content and future map/block/container interaction, required read-only query-command support with future write approval reserved, and delegated Phase 4 decisions to the agent's best judgment
- date: 2026-07-19
- commit: 78c2122
- patterns:
  - A_async_wait
  - B_state_persistence
  - C_concurrent_operations
  - D_external_dependency
  - E_security_boundary
  - F_fail_semantics
- scope: request progress and timeout, stable streaming layout, input behavior, Tool argument/presentation contracts, prompt recovery, and a unified read-only player-observable game-state capability

## Context

Normal Fabric testing showed that a request could spend minutes waiting for a
model response while the screen showed only an enabled Stop button. History
confirmed network waits and transport failures rather than a GuideService
deadlock, but an HTTP streaming body had no total watchdog after response
headers. Per-delta Markdown remeasurement also moved the transcript, list
markers were laid out on their own lines, Enter did not follow normal chat
expectations, and collapsed Tool cards hid already-available summaries.

The same walkthrough showed an architectural gap above the narrow, high-volume
Recipes and Guides domains. The Agent could inspect only a small platform
summary and player context, even though the player can directly observe much
more through option screens, menus, HUD/F3, inventories, installed-content
screens, and read-only query facilities. The designer clarified that this
surface includes every setting and directly player-observable value, but not
world exploration, nearby block/container inspection, structure scanning, or
other information that requires interaction with world objects.

## Decision

### Request visibility and total timeout

Every active request publishes a redacted progress projection containing its
phase, request start, phase start, last progress time, attempt, optional retry
time, and optional configured deadline. The phase vocabulary distinguishes
preparation, durable-context loading, compaction, endpoint queueing, model
waiting, response streaming, Tool waiting, and completion. Provider content,
URLs, credentials, prompts, arguments, and raw exception text are never part of
this projection.

Server model events transport the attempt's relative timeout budget, not the
server's absolute wall-clock deadline. The client derives a local
display-only deadline at receipt; the server transport watchdog remains the
sole owner of actual timeout enforcement.

The Guide screen renders progress in one fixed-height region outside the
virtual transcript. Elapsed time and retry/deadline countdowns are computed
from the immutable snapshot at render/tick time; clocks do not create durable
history events or SQLite writes. Model attempt start, response start, stream
progress, Tool transition, retry, cancellation, and terminal completion update
`lastProgressAt`.

`requestTimeoutSeconds` is the total wall-clock budget for one dispatched model
attempt through the complete response body, including SSE decoding. A
cancellable watchdog closes the response stream and completes with
`model_timeout`; request-generation fencing suppresses late bytes. HTTP 429
continues to use the existing explicit scheduler and is not hidden inside the
transport. No separate arbitrary idle timeout is introduced.

### Stable native rendering and input

The screen coalesces multiple immutable view notifications into at most one UI
application per client tick. Rendering is pure with respect to scroll state.
While an assistant segment is streaming, its mutable incomplete tail uses a
stable literal-text layout; completed blocks are upgraded to semantic layout
only at validated boundaries. Auto-follow remains owned by whether the player
was already at the bottom, and manual reading anchors never move.

List markers share the first visual line of the first paragraph and subsequent
lines use a measured hanging indent. Enter submits when the composer owns
focus, Shift+Enter inserts a newline, and Ctrl+Enter remains a compatibility
submit shortcut. Enter on a focused content action continues to activate that
action rather than submitting the composer.

Collapsed Tool cards render a closed, localized invocation summary and two or
three player-friendly result lines where available. Raw arguments, normalized
JSON, internal IDs, and technical evidence remain Debug Mode-only. The friendly
projection is persisted so recovered history matches the live timeline.
The durable and bridge projection stores only a closed semantic message enum
plus control-character-free literal arguments; arbitrary translation keys are
unrepresentable. The client resolves those messages in its current locale.
This breaking pre-release durable shape is schema 5, so recognized schema 4
databases follow the accepted rebuild-without-migration policy.

### One player-observable game-state Tool

TomeWisp exposes one common Agent Tool, `tomewisp:inspect_game_state`, for the
outer game/client state layer. Its strict input selects one registered section
and an optional section-specific query. It never returns an unbounded dump.
Initial registered sections cover, without defining the future ceiling:

- overview and current connection/runtime identity;
- a complete lightweight installed-mod index followed by exact-ID public mod
  metadata on demand;
- every client option exposed through game UI, grouped by its native settings
  domains, including video, sound, controls/key mappings, accessibility,
  language/chat, resource packs, data packs where visible, shaders and public
  shader options when a compatible integration exists;
- HUD/F3-style diagnostics and directly visible player state, including
  dimension and coordinates;
- player-owned UI state such as inventory where already available;
- closed read-only query equivalents for information normally obtained through
  non-mutating commands, subject to actual client/server authority and player
  permission, rechecked independently before each operation is captured.

This is an extensible registry of typed section handlers behind one Tool ID,
not a raw reflection surface or a list of arbitrary field paths. Capture of
Minecraft objects happens only on the owning client/server thread and detaches
immediately into immutable records. Each section returns evidence and explicit
authority/completeness. Unsupported integrations or unavailable server facts
remain partial/unavailable rather than guessed.

A bundled Agent Skill teaches section discovery, narrow follow-up queries,
authority interpretation, and when to stop. The system prompt contains only
universal Tool discipline: resolve natural names before exact-ID fields, carry
stable handles unchanged, correct invalid arguments once, and stop on unchanged
empty/partial results. Tool schemas include field descriptions, enums and
resource-ID constraints that agree with runtime validation.

Recipes and Guides remain independent narrow, high-volume deep-content Tool
families. Future map, nearby block, structure, external container, and spatial
inspection receives a separate design. Arbitrary command strings, write
commands, approval cards, world mutation, and interaction-driven probing are
not registered in this Tool. A future write/approval protocol must use a
separate accepted authority boundary rather than adding a write section here.

## States and Transitions

- `model_wait -> response_streaming`: validated response headers/body start;
  record response progress and retain the same request deadline.
- `response_streaming -> tool_wait | completing | failed`: a decoded Tool call,
  complete answer, protocol failure, cancellation, or total timeout terminates
  streaming; late bytes cannot mutate the request.
- `any active -> same active phase`: a monotonic progress event updates only
  phase/attempt/last-progress metadata and its redacted UI projection.
- `client context capture -> observable_snapshot_ready`: capture registered
  player-observable sections on the Minecraft thread, detach immutable values,
  and release all live objects before asynchronous Tool execution.
- `observable_snapshot_ready -> tool_wait -> model_wait`: validate one section
  query, project available evidence, and continue the same Agent chronology.

## Invariants

1. The progress surface always distinguishes active work from terminal state
   and can never contain model content, credentials, endpoints, raw arguments,
   exception bodies, or durable scope identifiers.
2. The configured request timeout covers complete response consumption and
   cancellation suppresses every late stream event.
3. Render passes do not mutate scroll ownership, and streaming never moves a
   player who is reading older content.
4. `inspect_game_state` is one strict read-only Tool with registered typed
   sections; it cannot execute a command string, reflect arbitrary fields, or
   expand its own permissions.
5. Player-observable state includes direct UI/HUD/F3/query-visible information,
   but excludes world scanning and information requiring interaction with
   blocks, structures, nearby entities, or external containers.
6. Recipes and Guides retain independent deep-content schemas, sources,
   evidence, search, and exact-lookup workflows.
7. Live Minecraft state never crosses its owning thread; every factual success
   carries explicit evidence, authority, completeness, capture time, source,
   game version, and loader.
8. Missing optional mod/shader/query integrations degrade only their section
   and never fabricate an empty fact or disable unrelated sections.
9. Skills and prompts guide Tool selection but never grant Tool, command,
   filesystem, network, server, or write authority.

## Failure Semantics

- Complete response exceeds the configured deadline: close it, publish
  `model_timeout`, preserve completed visible chronology, and allow explicit
  retry as a new request.
- DNS, TLS, connect, reset, protocol, and provider failures map to stable
  redacted categories; normal UI shows friendly recovery guidance and Debug
  Mode may show only the stable code.
- A stale stream/progress completion arrives after cancel/retry/disconnect:
  suppress it by request generation and release resources.
- A section/query is unknown or malformed: return `invalid_tool_arguments`
  with the registered section vocabulary; do not infer a nearby operation.
- A section is unsupported or not authoritative in the current topology:
  return explicit unavailable/partial evidence and keep other sections usable.
- An option/integration cannot be read through verified public APIs: omit it,
  mark that section partial, and expose a scoped diagnostic rather than using
  unrestricted reflection.
- Natural resource resolution is ambiguous: return every deterministic match
  with stable exact IDs and require the model/user to disambiguate; never pick
  an arbitrary match.
- Repeated invalid or unchanged empty Tool searches: the prompt requires one
  corrected attempt and then a grounded explanation; no alternating retry
  loop without new information.

## Implementation Evidence

The current Phase 4 worktree implements the redacted progress lifecycle and
complete-response watchdog, stable tick-coalesced native projection and input,
streaming/list/card corrections, shared Tool guidance, and the sectioned
read-only `inspect_game_state` capability plus Skill. Focused deterministic
suites for those contracts have passed. Fabric and NeoForge graphical
controllers have each completed the native semantic/UI correction scenario
with six screenshots; both reports record all eight controlled component types
and 32 semantic blocks. Both loaders also completed the real-client game-state
scenario with all eight sections, exact successful section probes, and exact
Agent/tool chronology. The latest clean common/Fabric/NeoForge gate passed with
525 tests, package and SQLite verification passed, and the final
credential/diff/report/hash/manifest and screenshot audits passed. The retained
closing evidence is under `docs/verification/phase-4-final-corrections/`.

## Applies To

- shared HTTP transport, model adapters/scheduler, Guide request snapshots and
  immutable UI projection
- screen update coalescing, virtual transcript, semantic layout, scrolling,
  composer input and localization
- Tool event/history projection, friendly cards, JSON Schema generator,
  system prompt and natural resource resolution
- common context capability/snapshot/tool contracts and Fabric/NeoForge
  metadata/capture adapters
- bundled Agent Skills and deterministic transport, state, rendering, schema,
  context, security, E2E and both-loader tests

## Supersedes

- Supersedes SKMB-2026-07-17-004 only for composer submission: Enter now sends
  and Shift+Enter creates a new line; Ctrl+Enter remains compatible.
- Refines SKMB-2026-07-18-013 so the configured model timeout covers the full
  response stream, without changing shared-transport authority boundaries.
- Refines SKMB-2026-07-18-018 streaming-tail layout and scroll publication
  while preserving semantic safety and windowing contracts.

## Superseded By

None.
