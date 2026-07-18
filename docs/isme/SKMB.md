# State Machine Knowledge Base

This index records externally visible state and failure decisions for TomeWisp.
Designer decisions are authoritative only when the referenced decision is
accepted and contains explicit approval evidence.

## Decision Index

| id | status | scope | patterns | file | commit |
| --- | --- | --- | --- | --- | --- |
| SKMB-2026-07-17-001 | accepted | Phase 2 client-first Agent runtime | A, B, C, D, E, F, G | decisions/2026-07-17-001-client-first-agent-runtime.md | 77b4970 |
| SKMB-2026-07-17-002 | accepted | shared server model queue | A, B, C, D, F | decisions/2026-07-17-002-shared-server-model-queue.md | fc55c60 |
| SKMB-2026-07-17-003 | accepted | multi-session endpoint rate scheduling | A, B, C, D, F | decisions/2026-07-17-003-multi-session-rate-scheduling.md | 17b2f20 |
| SKMB-2026-07-17-004 | accepted | Phase 3 product state | A, B, C, D, E, F | decisions/2026-07-17-004-phase-3-product-state.md | e4a77ad |
| SKMB-2026-07-18-005 | accepted | Phase 4 product state | A, B, C, D, E, F, G | decisions/2026-07-18-005-phase-4-product-state.md | ad8ad52 |
| SKMB-2026-07-18-006 | reviewable_default | Phase 4 durable history execution | A, B, C, E, F, G | decisions/2026-07-18-006-durable-history-execution.md | cb77181 |
| SKMB-2026-07-18-007 | reviewable_default | Phase 4 recipe provider execution | A, B, D, E, F | decisions/2026-07-18-007-recipe-provider-execution.md | 7e89bed |
| SKMB-2026-07-18-008 | reviewable_default | Phase 4 context compaction execution | A, B, C, D, E, F | decisions/2026-07-18-008-context-compaction-execution.md | pending |
| SKMB-2026-07-18-009 | accepted | per-session model selection and metadata discovery | B, C, D, E, F | decisions/2026-07-18-009-session-model-selection.md | 98a40bf, e63ecb4, 558ba67, fc20505, 7e2a735, 555ed3c |
| SKMB-2026-07-18-010 | accepted | normal/debug UI projection | B, E, F | decisions/2026-07-18-010-debug-ui-projection.md | pending |
| SKMB-2026-07-18-011 | accepted | pre-release durable schema policy | B, F, G | decisions/2026-07-18-011-pre-release-durable-schema.md | pending |
| SKMB-2026-07-18-012 | accepted | model metadata cache and refresh | A, B, D, F | decisions/2026-07-18-012-model-metadata-cache.md | 7e2a735 |
| SKMB-2026-07-18-013 | accepted | shared outbound HTTP authority boundaries | D, E, F | decisions/2026-07-18-013-outbound-http-boundaries.md | 7e2a735 |
| SKMB-2026-07-18-014 | accepted | player history administration | B, C, F, G | decisions/2026-07-18-014-history-administration.md | adaffaf; implemented through a3ae197 |
| SKMB-2026-07-18-015 | accepted | settings model administration and live connection testing | A, B, C, D, E, F, G | decisions/2026-07-18-015-settings-model-administration.md | f1ba74b; implemented through 6498516 |
| SKMB-2026-07-18-016 | accepted | native settings coordination and domain config writes | A, B, C, E, F | decisions/2026-07-18-016-native-settings-coordination.md | e7acf43, 507d628; implemented through a3ae197 |
| SKMB-2026-07-18-017 | accepted | knowledge/capability catalog and local Tool/Skill policy | B, C, D, E, F | decisions/2026-07-18-017-capability-settings-policy.md | e7acf43, 507d628; implemented through 771cc94 |
| SKMB-2026-07-18-018 | accepted | semantic messages, controlled components, and windowed history | A, B, C, D, E, F | decisions/2026-07-18-018-semantic-history-windowing.md | 9655dd2 |

SKMB-2026-07-18-006 is implemented by `a0eaeff`, `19ab90f`, and `c6ca6bc`.
Its deterministic clean-build and packaged-driver evidence is recorded in the
Phase 4B durable-history plan. Final graphical restart review remains open.

SKMB-2026-07-18-007 is implemented through `5af5b4e`. Its deterministic
provider/catalog/navigation coverage and retained Fabric JEI/REI/Farmer's
Delight graphical evidence are recorded in the Phase 4C plan and
`docs/verification/phase-4c-all-known-recipes/`.

SKMB-2026-07-18-015 and the model-administration slice of
SKMB-2026-07-18-016 are implemented through `6498516`. Their deterministic
atomicity, redaction, generation-race, responsive UI, localization, and
both-loader evidence is recorded in the Phase 4G plan and
`docs/verification/phase-4g-native-model-settings/`. The opt-in live probe was
not run when no credential was exported to the verification process.

SKMB-2026-07-18-017 is implemented through `771cc94`. The common catalog,
deny-only local policy, dependency validation, future-request capability
capture, recipe Tool child settings, friendly normal-mode projection, and
shared Fabric/NeoForge runtime wiring have deterministic coverage. JEI and REI
are registered; EMI remains unimplemented and is not shown as an available
source.

SKMB-2026-07-18-014 and the remaining native settings/diagnostics scope of
SKMB-2026-07-18-016 are implemented through `a3ae197`. The service-owned,
one-use history confirmations, actor-scoped deletion gates, live shared display
runtime, privacy-separated diagnostics, and Fabric/NeoForge lifecycle parity
have deterministic coverage. Final retained graphical acceptance remains part
of the consolidated Phase 4 audit.

## Named States

| state | meaning | owner | notes | source |
| --- | --- | --- | --- | --- |
| idle | No Agent request is active for the player/client | AgentSessionStore | New requests may start | SKMB-2026-07-17-001 |
| queued | A local or server model request is waiting for its endpoint rate gate | ModelRequestScheduler | Cancellable; session order is retained | SKMB-2026-07-17-003 |
| preparing | Context, skills, and available tools are being snapshotted | AgentCoordinator | Runs on the owning game thread | SKMB-2026-07-17-001 |
| model_wait | An outbound model request is in progress | AgentCoordinator | Cancellable; no game objects cross this boundary | SKMB-2026-07-17-001 |
| tool_wait | A local or server-enhanced read tool is running | AgentCoordinator | Remote calls remain player-scoped | SKMB-2026-07-17-001 |
| completed | A grounded final answer has been delivered | AgentSessionStore | Terminal | SKMB-2026-07-17-001 |
| failed | The request ended with an explicit structured failure | AgentSessionStore | Terminal; no fabricated fallback | SKMB-2026-07-17-001 |
| cancelled | The player, disconnect handler, or shutdown cancelled the request | AgentSessionStore | Terminal; late events are ignored | SKMB-2026-07-17-001 |
| guide_unconfigured | The GUI is available but no usable client/server model is selected | GuideService | Configuration can be inspected; questions cannot submit | SKMB-2026-07-17-004 |
| interrupted | Durable request history exists but its execution lost the client process | GuideHistoryStore | Terminal until explicit retry with a new request ID | SKMB-2026-07-18-005 |
| compacting | History is being reduced or summarized before model dispatch | GuideService | Cancellable; original durable messages remain unchanged | SKMB-2026-07-18-005 |
| persistence_unavailable | The active service is usable but durable history cannot be read or written reliably | GuideHistoryStore | Never claim an unsaved record was persisted | SKMB-2026-07-18-005 |
| history_loading | The current durable partition is loading off the client thread | GuideHistoryRepository | New model requests fail explicitly until hydration completes | SKMB-2026-07-18-006 |
| integration_degraded | One optional recipe/viewer adapter failed while other sources remain available | RecipeCatalog | Publish diagnostics and preserve unaffected snapshots | SKMB-2026-07-18-005 |
| semantic_tail_literal | The mutable assistant tail contains incomplete, unsupported, or not-yet-validated syntax | SemanticMessageParser | Readable and non-interactive | SKMB-2026-07-18-018 |
| semantic_tail_validated | Completed assistant blocks have a strict immutable semantic projection | SemanticMessageParser | Cached by content hash with fallback text | SKMB-2026-07-18-018 |
| history_page_loading | One viewport neighborhood is loading for the current actor/session generation | GuideHistoryRepository | Does not block unrelated sessions or context reads | SKMB-2026-07-18-018 |
| context_loading | A model-budgeted durable context seed is loading before provider dispatch | GuideService | Cancellable; no provider request has started | SKMB-2026-07-18-018 |

## Transition Decisions

| id | from_state | event | to_state | actions | source |
| --- | --- | --- | --- | --- | --- |
| T1 | idle | guide request | preparing | Reserve one active request and capture immutable inputs | SKMB-2026-07-17-001 |
| T2 | preparing | context ready | model_wait | Dispatch model request off the game thread | SKMB-2026-07-17-001 |
| T3 | model_wait | tool use | tool_wait | Validate and invoke an exposed read tool | SKMB-2026-07-17-001 |
| T4 | tool_wait | tool result | model_wait | Append the complete structured result and continue | SKMB-2026-07-17-001 |
| T5 | model_wait | final text | completed | Deliver final text and close the trace | SKMB-2026-07-17-001 |
| T6 | any active | explicit cancel, disconnect, shutdown | cancelled | Signal cancellation and suppress late output | SKMB-2026-07-17-001 |
| T7 | any active | validation, model, transport, tool, or bridge error | failed | Deliver a structured error and close the trace | SKMB-2026-07-17-001 |
| T8 | model request received | endpoint rate gate is closed | queued | Append to that session's FIFO and publish queue state | SKMB-2026-07-17-003 |
| T9 | queued | endpoint cooldown expires and fair scheduler selects session | preparing | Remove queue head and capture current context/history | SKMB-2026-07-17-003 |
| T10 | queued | cancel or disconnect | cancelled | Remove item and publish cancellation if connected | SKMB-2026-07-17-002 |
| T11 | model_wait | provider returns HTTP 429 | queued | Read Retry-After or compute backoff, close endpoint gate, and requeue request | SKMB-2026-07-17-003 |
| T12 | any active | GUI closes | unchanged | Keep request and session alive; detach only the screen listener | SKMB-2026-07-17-004 |
| T13 | any connection state | disconnect | cancelled then idle | Cancel requests, suppress late events, clear scoped sessions and capabilities | SKMB-2026-07-17-004 |
| T14 | terminal failed/cancelled | explicit retry | preparing | Create a new request ID in the same session with the retained user message | SKMB-2026-07-17-004 |
| T15 | any | model mode changes | unchanged | Apply selected topology only to future requests | SKMB-2026-07-17-004 |
| T16 | preparing | context projection exceeds selected model budget | compacting | Reduce old tool results and, if required, create a structured summary checkpoint | SKMB-2026-07-18-005 |
| T17 | compacting | valid projection fits | model_wait | Dispatch using the request's selected topology | SKMB-2026-07-18-005 |
| T18 | compacting | reduction and summary cannot produce a valid projection | failed | Return `context_compaction_failed` without deleting durable history | SKMB-2026-07-18-005 |
| T19 | active durable record | process loss detected on next load | interrupted | Preserve visible history and wait for explicit retry | SKMB-2026-07-18-005 |
| T20 | interrupted | explicit retry | preparing | Create a new request ID; never automatically resend | SKMB-2026-07-18-005 |
| T21 | recipe source available | one optional adapter fails | integration_degraded | Retain other source snapshots and publish a capability diagnostic | SKMB-2026-07-18-005 |
| T22 | assistant segment streaming | tool invocation starts | tool_wait | Close the active visible segment and append the correlated tool entry at the next timeline ordinal | SKMB-2026-07-18-005 |
| T23 | tool_wait | correlated tool completes and later text arrives | model_wait | Update the existing tool entry in place and append/continue a later assistant segment | SKMB-2026-07-18-005 |
| T24 | history_loading | durable load succeeds | idle | Hydrate only the matching partition and mark orphaned active requests interrupted | SKMB-2026-07-18-006 |
| T25 | history_loading | durable load fails | persistence_unavailable | Keep a fresh in-memory service and expose an unsaved diagnostic | SKMB-2026-07-18-006 |
| T26 | any connection state | graceful disconnect | cancelled then idle | Persist terminal cancellation, detach durable writer completion, and clear only connection state | SKMB-2026-07-18-006 |
| T27 | recipe provider available | capture succeeds with omitted unsupported records | integration_degraded | Retain representable records, mark provider partial, and publish stable diagnostics | SKMB-2026-07-18-007 |
| T28 | preparing | deterministic context estimate exceeds configured input budget | compacting | Protect the current request and structural tool pairs, then reduce old tool results | SKMB-2026-07-18-008 |
| T29 | compacting | deterministic projection still exceeds budget | compacting | Use the same selected model topology to create a source-hashed structured summary checkpoint | SKMB-2026-07-18-008 |
| T30 | compacting | cancel arrives | cancelled | Cancel summary work, store no successful checkpoint, and suppress primary dispatch | SKMB-2026-07-18-008 |
| T31 | history_loading | unsupported pre-release schema opens | persistence_unavailable | Reject it without mutation; development state must be deleted and recreated explicitly | SKMB-2026-07-18-011 |
| T32 | any non-active session state | selected model/provider changes | unchanged | Keep the provider-neutral transcript/checkpoints; assemble the next request with the new model and its budget | SKMB-2026-07-18-008 |
| T33 | any session state | session model selection changes | unchanged | Store the preference for that session's future requests; an active request retains its captured runtime | SKMB-2026-07-18-009 |
| T34 | any UI state | debug mode changes | unchanged | Rebuild only the local normal/debug projection; do not rewrite history or change active work | SKMB-2026-07-18-010 |
| T35 | client startup | valid metadata cache loads | unchanged | Apply only missing profile limits asynchronously; do not block startup or override explicit values | SKMB-2026-07-18-012 |
| T36 | cache miss or manual refresh | trusted metadata succeeds | unchanged | Atomically store the validated credential-free entry and make it available to a later registry reload | SKMB-2026-07-18-012 |
| T37 | history idle with no pending write | player confirms current-partition/current-actor deletion | deletion_pending | Atomically reserve the idle repository, transactionally delete only the approved scope, reset matching in-memory sessions, then return idle; reject rather than queue behind a pending write | SKMB-2026-07-18-014 |
| T38 | settings idle | player confirms a valid profile candidate | settings_saving | Prepare a complete replacement, atomically replace `models.json`, then publish the prepared runtime for future requests | SKMB-2026-07-18-015 |
| T39 | settings idle | player starts connection test after cost notice | connection_testing | Send one isolated cancellable real model probe; discard content and retain only redacted transient status/latency | SKMB-2026-07-18-015 |
| T40 | settings idle | confirmed typed settings action starts | settings_mutating | Run one domain-owned async mutation; publish one immutable terminal snapshot and never enqueue a hidden second write | SKMB-2026-07-18-016 |
| T41 | capability policy current | player confirms valid Tool/Skill policy | capability policy saving | Atomically persist disabled identities and publish one prepared immutable capability snapshot for future client requests | SKMB-2026-07-18-017 |
| T42 | semantic_tail_literal | syntax closes and validates | semantic_tail_validated | Replace only the mutable tail with immutable safe Markdown/reference/component nodes | SKMB-2026-07-18-018 |
| T43 | history window idle | viewport requests another neighborhood | history_page_loading | Start or coalesce one generation-bound page read; retain the current anchor/window | SKMB-2026-07-18-018 |
| T44 | history_page_loading | page succeeds or fails | history window idle | Merge the matching page and preserve the anchor, or retain the prior window with a retryable diagnostic | SKMB-2026-07-18-018 |
| T45 | preparing | selected topology requires durable context | context_loading | Stream a provider-neutral seed under the actual selected model budget before dispatch | SKMB-2026-07-18-018 |
| T46 | context_loading | seed validates or fails | model_wait or failed | Dispatch exactly once with valid context, or fail before provider I/O and preserve history | SKMB-2026-07-18-018 |

## Invariants

| id | invariant | source |
| --- | --- | --- |
| I1 | Client-local mode works without TomeWisp installed on the server | SKMB-2026-07-17-001 |
| I2 | At most one Agent request is active per `(actorId, sessionId)`; other sessions may run concurrently | SKMB-2026-07-17-003 |
| I3 | API credentials never enter packets, prompts, traces, logs, or tool results | SKMB-2026-07-17-001 |
| I4 | Only registered read-only tools are callable in Phase 2 | SKMB-2026-07-17-001 |
| I5 | Live Minecraft objects never leave their owning game thread | SKMB-2026-07-17-001 |
| I6 | Missing integrations and failed tools never become fabricated facts | SKMB-2026-07-17-001 |
| I7 | Server-enhanced tools authorize and scope every request to the sending player | SKMB-2026-07-17-001 |
| I8 | Requests preserve per-session order and rate-limited queues rotate sessions fairly | SKMB-2026-07-17-003 |
| I9 | A failed or cancelled server request releases its slot and cannot stall the queue | SKMB-2026-07-17-002 |
| I10 | Different sessions owned by the same player may execute concurrently | SKMB-2026-07-17-003 |
| I11 | Rate-limit state is isolated by model endpoint/credential configuration | SKMB-2026-07-17-003 |
| I12 | Commands and GUI project the same GuideService request/session state | SKMB-2026-07-17-004 |
| I13 | Closing a Screen never implicitly cancels an Agent request | SKMB-2026-07-17-004 |
| I14 | Connection-scoped sessions, capabilities, and knowledge never cross a disconnect | SKMB-2026-07-17-004 |
| I15 | Every factual success exposes authority, completeness, capture time, source, and provenance | SKMB-2026-07-17-004 |
| I16 | Model-mode changes and failures never silently move an active request to another topology | SKMB-2026-07-17-004 |
| I17 | An unloaded or empty knowledge snapshot carries explicit completeness; it is never silently treated as proof that no knowledge exists | SKMB-2026-07-17-004 |
| I18 | Craftability allocates overlapping alternatives deterministically and never implies recursive intermediate crafting | SKMB-2026-07-17-004 |
| I19 | Vanilla recipe-book unlock state is evidence and an optional filter, never the default recipe knowledge boundary | SKMB-2026-07-18-005 |
| I20 | Durable history is partitioned by player and world/server scope; automatic model context never crosses partitions | SKMB-2026-07-18-005 |
| I21 | Context reduction and summarization never delete or rewrite original durable messages | SKMB-2026-07-18-005 |
| I22 | A summary is derived memory and never satisfies factual evidence requirements | SKMB-2026-07-18-005 |
| I23 | Process loss never automatically repeats a provider request | SKMB-2026-07-18-005 |
| I24 | Normal history and developer traces retain only their separately approved data classes | SKMB-2026-07-18-005 |
| I25 | Rich components bind only registered component types and validated domain references | SKMB-2026-07-18-005 |
| I26 | Failure of one optional recipe/viewer adapter never disables unrelated Agent capabilities | SKMB-2026-07-18-005 |
| I27 | Player-visible assistant segments, tool entries, and status entries preserve actual Agent event order across streaming and durable recovery | SKMB-2026-07-18-005 |
| I28 | Tool completion updates the exact invocation ID; repeated calls are never joined by tool name alone | SKMB-2026-07-18-005 |
| I29 | Final text reconciliation never overwrites earlier assistant segments or reorders tool activity | SKMB-2026-07-18-005 |
| I30 | SQLite access and durable decoding never run on a Minecraft-owned thread | SKMB-2026-07-18-006 |
| I31 | Durable scope IDs do not retain raw server addresses or local world paths | SKMB-2026-07-18-006 |
| I32 | Disconnect cleanup never persists an empty replacement for retained partition history | SKMB-2026-07-18-006 |
| I33 | Exact recipe references include their provider generation and never bind stale IDs to changed contents | SKMB-2026-07-18-007 |
| I34 | Viewer and Minecraft recipe objects are detached on the client thread and never enter model workers | SKMB-2026-07-18-007 |
| I35 | The current request boundary and its tool-use/result pairs are unchanged in every context projection | SKMB-2026-07-18-008 |
| I36 | Summary generation uses the active request endpoint, credentials, scheduling key, and cancellation signal | SKMB-2026-07-18-008 |
| I37 | Reasoning is excluded from summary prompts and durable compaction checkpoints | SKMB-2026-07-18-008 |
| I38 | Sessions and valid derived checkpoints are provider/model-neutral; every request is re-estimated against its selected model budget | SKMB-2026-07-18-008 |
| I39 | Model selection is per session and mutable; active requests retain their captured runtime and never reroute | SKMB-2026-07-18-009 |
| I40 | Explicit model limits outrank discovered metadata, and credentials are unrepresentable in persisted multi-profile configuration | SKMB-2026-07-18-009 |
| I41 | Normal UI exposes friendly cards and narration but cannot represent raw technical evidence/JSON; debug mode remains redacted | SKMB-2026-07-18-010 |
| I42 | Before the first formal release, durable storage has one current schema and no migration-only compatibility surface | SKMB-2026-07-18-011 |
| I43 | Metadata cache/load/refresh is asynchronous, credential-free, source/model keyed, and subordinate to explicit limits | SKMB-2026-07-18-012 |
| I44 | Shared HTTP transport grants no model tool, endpoint, credential, or evidence authority; each domain adapter must provide its own | SKMB-2026-07-18-013 |
| I45 | Normal history management is actor-scoped; whole-database reset is Debug Mode-only, separately confirmed, and never automatic | SKMB-2026-07-18-014 |
| I46 | Profile replacement is candidate-validated, atomically persisted, and published as one prepared runtime state; failure retains the prior file/runtime | SKMB-2026-07-18-015 |
| I47 | Connection testing is an explicit isolated real request with no Guide context/tools/history, no retry/fallback, and no retained secret/body/output | SKMB-2026-07-18-015 |
| I48 | Native settings use one common operation/snapshot service while model, capability, capability-owned, display, metadata, and history persistence remain independently versioned | SKMB-2026-07-18-016 |
| I49 | Settings file/provider/SQLite work never runs on a Minecraft-owned thread, and screen detach never owns or rolls back a confirmed durable mutation | SKMB-2026-07-18-016 |
| I50 | Knowledge/capability settings can only narrow registered local Tool/Skill access; every active request retains one captured immutable capability snapshot | SKMB-2026-07-18-017 |
| I51 | Tool-specific source settings use stable registered IDs under that tool's child page; adding JEI/REI/EMI/future adapters does not add top-level mod settings fields | SKMB-2026-07-18-017 |
| I52 | Semantic output is a versioned closed AST; HTML, URLs, embeds, arbitrary UI trees, code, callbacks, commands, and mutations are unrepresentable | SKMB-2026-07-18-018 |
| I53 | Raw resource existence is presentation-only; actionable stable handles must originate in the same authorized request context | SKMB-2026-07-18-018 |
| I54 | Every semantic component has readable fallback text and narration, and color/animation never owns state | SKMB-2026-07-18-018 |
| I55 | GUI viewport paging and model-context selection are independent and neither loads a complete durable partition into memory | SKMB-2026-07-18-018 |
| I56 | Incremental history writes and page/context reads remain ordered off Minecraft-owned threads and generation-check every completion | SKMB-2026-07-18-018 |
| I57 | Auto-scroll follows only while already at the bottom; earlier-page insertion preserves the player's anchor row and pixel offset | SKMB-2026-07-18-018 |

## Fail Semantics

| id | context | behavior | source |
| --- | --- | --- | --- |
| F1 | A second request arrives in the same session while one is active | Reject it as `agent_busy`; requests in other sessions remain independent | SKMB-2026-07-17-003 |
| F2 | Client-only mode asks for a server-only fact | Return `capability_unavailable`; do not guess | SKMB-2026-07-17-001 |
| F3 | Model transport or protocol fails | End as `model_failure`; preserve diagnostic trace without credentials | SKMB-2026-07-17-001 |
| F4 | Tool arguments or output are invalid | Return structured failure to the model; repeated identical calls fail explicitly | SKMB-2026-07-17-001 |
| F5 | Server bridge identity or correlation is invalid | Reject and log a redacted diagnostic | SKMB-2026-07-17-001 |
| F6 | Optional FTB Quests or Patchouli integration is absent | Keep the Agent operational and expose an unavailable capability diagnostic | SKMB-2026-07-17-001 |
| F7 | A model endpoint returns 429 | Queue/requeue against that endpoint until Retry-After or cancellable backoff expires | SKMB-2026-07-17-003 |
| F8 | Recipe or inventory evidence is incomplete | Return a non-conclusive result; never claim craftability conclusively | SKMB-2026-07-17-004 |
| F9 | A source-scoped recipe/document reference is stale | Return `stale_reference`; a new search requires an explicit new action | SKMB-2026-07-17-004 |
| F10 | A remote event is malformed or loses correlation | Fail the affected request closed and do not mutate another session | SKMB-2026-07-17-004 |
| F11 | A factual output type returns success without evidence | Reject normalization with `Grounded tool output has no evidence`; do not expose the success to the model | SKMB-2026-07-17-004 |
| F12 | A recovered request has no terminal event because the client process ended | Mark it `interrupted`; retain visible history and require explicit retry | SKMB-2026-07-18-005 |
| F13 | Context compaction fails or still cannot fit the selected model budget | Use deterministic reduction only if valid; otherwise return `context_compaction_failed` without deleting history | SKMB-2026-07-18-005 |
| F14 | A durable history transaction or load fails | Report persistence unavailable and never claim unsaved data was committed; keep the in-memory Agent usable where safe | SKMB-2026-07-18-005 |
| F15 | An optional viewer/recipe adapter is missing or fails | Return an explicit capability diagnostic and retain unaffected recipe sources | SKMB-2026-07-18-005 |
| F16 | A semantic reference or dynamic component is invalid or unsupported | Render safe fallback text with no action and no fabricated evidence | SKMB-2026-07-18-005 |
| F17 | Timeline sequence or tool invocation correlation is missing, duplicated, or inconsistent | Fail the affected request closed; do not guess order or mutate another activity | SKMB-2026-07-18-005 |
| F18 | A request is submitted before durable hydration finishes | Reject it as `history_loading`; do not queue or invoke a model with incomplete context | SKMB-2026-07-18-006 |
| F19 | A viewer recipe contains unsupported or malformed ingredients | Omit that record, mark only that provider partial, and retain other provider records | SKMB-2026-07-18-007 |
| F20 | A summary is malformed, fails, or produces an over-budget projection | Use deterministic reduction only if it fits; otherwise fail `context_compaction_failed` and preserve original history | SKMB-2026-07-18-008 |
| F21 | A durable checkpoint source hash does not match current same-partition messages | Treat it as stale derived memory and rebuild; never insert it into model context | SKMB-2026-07-18-008 |
| F22 | A durable checkpoint uses an unsupported summary prompt/schema version | Retain it for diagnosis and rebuild; never insert it into model context | SKMB-2026-07-18-008 |
| F23 | A remembered model profile is missing, disabled, or invalid | Fail the future request explicitly; retain the selection for repair and never silently fall back | SKMB-2026-07-18-009 |
| F24 | Native/advisory model metadata is unavailable or malformed | Keep explicit configuration usable, expose a redacted diagnostic, and do not invent a context limit | SKMB-2026-07-18-009 |
| F25 | Known card data is malformed or a tool type is unknown | Render a friendly textual fallback; expose only a redacted validation diagnostic in debug mode | SKMB-2026-07-18-010 |
| F26 | An unsupported pre-release history schema is opened | Fail `history_schema_unsupported` without mutation; require explicit deletion/recreation of development data | SKMB-2026-07-18-011 |
| F27 | Metadata cache is absent/invalid or refresh fails | Preserve explicit configuration and the last valid cache, publish a redacted diagnostic, and never block startup | SKMB-2026-07-18-012 |
| F28 | History deletion overlaps an active request/pending write or its transaction fails | Reject or roll back without deleting/resurrecting data; require an explicit later retry | SKMB-2026-07-18-014 |
| F29 | Profile validation/preparation/write fails | Report `invalid_model_config` or `settings_write_failed`; retain the previous file and runtime without partial publication | SKMB-2026-07-18-015 |
| F30 | Connection probe is busy, cancelled, rejected, rate-limited, times out, or returns malformed/empty output | Classify it into a stable redacted `connection_*` failure, send no retry, and leave settings/history unchanged | SKMB-2026-07-18-015 |
| F31 | A settings mutation conflicts, strict validation fails, or atomic persistence/reload fails | Reject as `settings_busy` or a stable domain/write failure; retain every unaffected prior file/runtime and never partially apply another domain | SKMB-2026-07-18-016 |
| F32 | Capability policy has a Skill/tool dependency conflict or a disabled/unavailable capability is invoked | Reject the save or invocation explicitly; never silently enable/fallback or widen authority | SKMB-2026-07-18-017 |
| F33 | Markdown, semantic reference, or controlled component is incomplete, malformed, unknown, or unauthorized | Keep readable fallback text, expose no action, and optionally report only a redacted debug diagnostic | SKMB-2026-07-18-018 |
| F34 | A viewport page read fails or completes for a stale generation | Retain the current window/anchor, suppress stale publication, and allow explicit retry | SKMB-2026-07-18-018 |
| F35 | A durable model-context seed cannot be read or structurally validated | Fail `history_context_failed` before provider dispatch and preserve every durable row | SKMB-2026-07-18-018 |
| F36 | Native semantic rendering fails for one node | Render its text/narration fallback and keep the screen usable; never fabricate a component success | SKMB-2026-07-18-018 |

## Statistical Defaults Allowed Temporarily

| id | pattern | context | default | reason_allowed | review_by | file |
| --- | --- | --- | --- | --- | --- | --- |
| SKMB-2026-07-18-006 | A, B, C, E, F | durable history execution details | hashed connection discriminator, async single writer, explicit loading rejection, ordered full-partition saves | designer delegated best implementation path; product invariants already accepted | final Phase 4 real-client acceptance | decisions/2026-07-18-006-durable-history-execution.md |
| SKMB-2026-07-18-007 | A, B, D, E, F | recipe provider execution details | client-thread capture, generation-bearing references, public optional viewer APIs, partial omission diagnostics | designer delegated best implementation path; recipe authority and visibility already accepted | final Phase 4 real-client acceptance | decisions/2026-07-18-007-recipe-provider-execution.md |
| SKMB-2026-07-18-008 | A, B, C, D, E, F | context compaction execution details | explicit per-model window, conservative UTF-8 estimator, structural prefix summaries, source-hash checkpoint reuse | designer delegated best implementation path; compaction authority and failure semantics already accepted | final Phase 4 real-client acceptance | decisions/2026-07-18-008-context-compaction-execution.md |

## Open Decisions

| id | pattern | context | needed_before | file |
| --- | --- | --- | --- | --- |
