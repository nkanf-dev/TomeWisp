# State Machine Knowledge Base

This index records externally visible state and failure decisions for OpenAllay.
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
| SKMB-2026-07-18-010 | accepted | normal/debug UI projection | B, E, F | decisions/2026-07-18-010-debug-ui-projection.md | 11a6ace |
| SKMB-2026-07-18-011 | accepted | pre-release durable schema policy | B, F, G | decisions/2026-07-18-011-pre-release-durable-schema.md | pending |
| SKMB-2026-07-18-012 | accepted | model metadata cache and refresh | A, B, D, F | decisions/2026-07-18-012-model-metadata-cache.md | 7e2a735 |
| SKMB-2026-07-18-013 | accepted | shared outbound HTTP authority boundaries | D, E, F | decisions/2026-07-18-013-outbound-http-boundaries.md | 7e2a735 |
| SKMB-2026-07-18-014 | accepted | player history administration | B, C, F, G | decisions/2026-07-18-014-history-administration.md | adaffaf; implemented through a3ae197 |
| SKMB-2026-07-18-015 | accepted | settings model administration and live connection testing | A, B, C, D, E, F, G | decisions/2026-07-18-015-settings-model-administration.md | f1ba74b; implemented through 6498516 |
| SKMB-2026-07-18-016 | accepted | native settings coordination and domain config writes | A, B, C, E, F | decisions/2026-07-18-016-native-settings-coordination.md | e7acf43, 507d628; implemented through a3ae197 |
| SKMB-2026-07-18-017 | accepted | knowledge/capability catalog and local Tool/Skill policy | B, C, D, E, F | decisions/2026-07-18-017-capability-settings-policy.md | e7acf43, 507d628; implemented through 771cc94 |
| SKMB-2026-07-18-018 | accepted | semantic messages, controlled components, and windowed history | A, B, C, D, E, F | decisions/2026-07-18-018-semantic-history-windowing.md | 9655dd2; implemented through 11a6ace |
| SKMB-2026-07-19-019 | accepted | manual-acceptance input, credential, Tool/source, Skill, and pre-release history corrections | B, C, D, E, F, G | decisions/2026-07-19-019-manual-acceptance-corrections.md | 78c2122 |
| SKMB-2026-07-19-020 | accepted | request observability, stable native interaction, Tool guidance, and unified player-observable game state | A, B, C, D, E, F | decisions/2026-07-19-020-observable-game-state-and-request-visibility.md | 78c2122 |
| SKMB-2026-07-19-021 | accepted | model ownership, authenticated model listing, Tool alias recovery, and player client Tool bridge | A, B, C, D, E, F | decisions/2026-07-19-021-model-ownership-and-player-tool-bridge.md | 48ed4c1 |
| SKMB-2026-07-19-022 | accepted | embedded native domain views, local retrieval, stable presentation, and future player-memory boundary | A, B, C, D, E, F | decisions/2026-07-19-022-native-domain-views-retrieval-memory.md | pending |
| SKMB-2026-07-19-023 | accepted | double-confirmed session deletion, managed conversation export, and visible chat copying | B, C, E, F, G | decisions/2026-07-19-023-session-actions-and-safe-export.md | pending |
| SKMB-2026-07-19-024 | accepted | ordered parallel Tool turns, typed batch/query surfaces, fixed online knowledge, provider recovery, and client-visible location routing | A, B, C, D, E, F | decisions/2026-07-19-024-batch-query-and-provider-recovery.md | pending |

SKMB-2026-07-18-006 is implemented by `a0eaeff`, `19ab90f`, and `c6ca6bc`.
Its deterministic clean-build and packaged-driver evidence is recorded in the
Phase 4B durable-history plan. Earlier 50-request seed and 51-request
windowed-restart reports remain under
`docs/verification/phase-4-final-acceptance/`; they predate SKMB-019 and do not
close its manual-acceptance corrections.

SKMB-2026-07-18-007 is implemented through `5af5b4e`. Its deterministic
provider/catalog/navigation coverage and retained Fabric JEI/REI/Farmer's
Delight graphical evidence are recorded in the Phase 4C plan and
`docs/verification/phase-4c-all-known-recipes/`. Final NeoForge acceptance used
JEI plus Cooking for Blockheads; REI is explicitly unavailable in that profile
after its upstream `@OnlyIn` loading warning.

SKMB-2026-07-18-015 and the model-administration slice of
SKMB-2026-07-18-016 are implemented through `6498516`. Their deterministic
atomicity, redaction, generation-race, responsive UI, localization, and
both-loader evidence is recorded in the Phase 4G plan and
`docs/verification/phase-4g-native-model-settings/`. The opt-in live probe was
not run when no credential was exported to the verification process.

SKMB-2026-07-18-017 was implemented through `771cc94`. Its earlier combined
catalog and generic Skill-toggle presentation are superseded by SKMB-019's
separate Tool/source and Skill-document contracts. Dependency validation,
future-request capture, friendly normal-mode projection, and shared
Fabric/NeoForge runtime wiring remain applicable. JEI and REI are registered;
EMI remains unimplemented and is not shown as an available source.

SKMB-2026-07-18-014 and the remaining native settings/diagnostics scope of
SKMB-2026-07-18-016 are implemented through `a3ae197`. The service-owned,
one-use history confirmations, actor-scoped deletion gates, live shared display
runtime, privacy-separated diagnostics, and Fabric/NeoForge lifecycle parity
have deterministic coverage. Retained graphical evidence for those earlier
slices is recorded under `docs/verification/phase-4-final-acceptance/`, but it
does not prove the SKMB-019 correction set.

SKMB-2026-07-18-010/016/018 now use display schema 3 with assistant name
`OpenAllay`, Debug Mode off, and presentation animation on by default. The
assistant name and animation are presentation-only, while
normal history diagnostics expose friendly on-demand/page state and Debug Mode
adds only redacted performance counts. The final presentation implementation
is recorded in `11a6ace`; deterministic scale/package evidence is retained in
the Phase 4J verification report.

SKMB-2026-07-19-019 is the accepted correction contract after the first normal
full-mod walkthrough. Its implementation and corrected graphical acceptance
must be verified separately; earlier Phase 4 reports are supporting evidence,
not completion evidence for this decision.

SKMB-2026-07-19-020 is the accepted contract for the second manual walkthrough:
visible request progress and full-stream timeout, stable streaming/list/input
behavior, aligned Tool guidance/cards, and one sectioned read-only Tool for all
player-observable outer game state. Deep Recipes/Guides remain independent and
interactive/spatial world inspection remains deferred.

Its implementation and focused deterministic suites are complete in the
current Phase 4 worktree. Fabric and NeoForge graphical controllers each
completed the native semantic/UI correction scenario with six screenshots,
all eight controlled component types, and 32 semantic blocks. Both loaders also
completed the eight-section real-client game-state scenario. The implementation
is recorded in `78c2122`. The latest clean production gate (525 tests), both-loader
package/SQLite checks, final credential/diff/report/hash/manifest audit, and
graphical evidence review all passed. Phase 4 is closed.

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
| credential_staged | A new immutable local secret exists but no persisted profile references it yet | LocalCredentialStore | Safe to ignore/collect until atomic profile replacement succeeds | SKMB-2026-07-19-019 |
| profile_referenced | A credential-free schema-2 model profile atomically references a resolvable local or external credential | ModelProfileSettingsStore | Raw secret remains outside model JSON and observable settings state | SKMB-2026-07-19-019 |
| tool_config_saving | One complete logical Tool/source candidate is being validated and persisted | Tool settings service | Prior Tool snapshot remains active until atomic replacement succeeds | SKMB-2026-07-19-019 |
| skill_reloading | A bundled/local Agent Skills package candidate is being validated | SkillRepository | Invalid override retains the previous valid or bundled package | SKMB-2026-07-19-019 |
| history_schema_rebuilding | A recognized pre-release schema 1, 2, 3, or 4 is being transactionally recreated as the current schema | GuideHistoryStore | Rollback preserves the older database if rebuild fails | SKMB-2026-07-19-019 |
| response_streaming | A model response body is actively producing validated deltas under its dispatch deadline | ModelClient | Cancellable; last-progress is observable and late bytes are generation-fenced | SKMB-2026-07-19-020 |
| observable_snapshot_ready | Player-observable game state has been detached into immutable registered sections | ClientContextCapture | Contains no live Minecraft objects, secrets, raw command strings, or spatial scans | SKMB-2026-07-19-020 |
| model_catalog_loading | One authenticated non-inference provider model-list request is active | ClientSettingsService | Cancellable; publishes only model IDs or a stable redacted failure | SKMB-2026-07-19-021 |
| client_tool_wait | A server-hosted Agent is waiting for one correlated Tool result from the requesting player's client | ServerAgentService | Actor/request/invocation scoped; cancel and disconnect suppress late results | SKMB-2026-07-19-021 |
| native_view_resolving | A visible validated component is resolving an exact optional or generic native presentation | NativeDomainViewRegistry | Client-thread-only; no live view enters semantic/history state | SKMB-2026-07-19-022 |
| native_view_ready | One exact optional provider owns the visible component view | NativeDomainViewRegistry | Released when the row leaves the visible lifecycle | SKMB-2026-07-19-022 |
| native_view_fallback | The component uses a detached OpenAllay generic canvas or readable fallback | NativeDomainViewRegistry | Never imitates an optional mod GUI | SKMB-2026-07-19-022 |
| knowledge_index_building | One detached knowledge generation is being indexed off the Minecraft thread | KnowledgeRegistry | Prior valid generation remains readable | SKMB-2026-07-19-022 |
| knowledge_index_degraded | The newest knowledge index failed and the prior valid/local lexical path remains active | KnowledgeRegistry | Source-scoped diagnostic; never fabricated empty knowledge | SKMB-2026-07-19-022 |
| tool_group_wait | Independent calls from one model turn are settling into indexed correlated slots | GameGuideAgent | Provider continuation waits for the complete ordered group | SKMB-2026-07-19-024 |
| model_transport_retry_wait | A no-progress transport failure is waiting for its bounded retry | ModelRequestScheduler | Cancellable; never entered after visible response progress | SKMB-2026-07-19-024 |
| online_knowledge_degraded | One fixed public-documentation adapter failed while local/other sources remain usable | SearchKnowledgeTool | Source-scoped and partial; no arbitrary URL fallback | SKMB-2026-07-19-024 |

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
| T31 | history_loading | recognized OpenAllay schema 1, 2, 3, or 4 opens | history_schema_rebuilding | Transactionally drop only OpenAllay application tables and recreate the single current schema without migration | SKMB-2026-07-19-019 |
| T32 | any non-active session state | selected model/provider changes | unchanged | Keep the provider-neutral transcript/checkpoints; assemble the next request with the new model and its budget | SKMB-2026-07-18-008 |
| T33 | any session state | session model selection changes | unchanged | Store the preference for that session's future requests; an active request retains its captured runtime | SKMB-2026-07-18-009 |
| T34 | any UI state | debug mode changes | unchanged | Rebuild only the local normal/debug projection; do not rewrite history or change active work | SKMB-2026-07-18-010 |
| T35 | client startup | valid metadata cache loads | unchanged | Apply only missing profile limits asynchronously; do not block startup or override explicit values | SKMB-2026-07-18-012 |
| T36 | cache miss or manual refresh | trusted metadata succeeds | unchanged | Atomically store the validated credential-free entry and make it available to a later registry reload | SKMB-2026-07-18-012 |
| T37 | history idle with no pending write | player confirms current-partition/current-actor deletion | deletion_pending | Atomically reserve the idle repository, transactionally delete only the approved scope, reset matching in-memory sessions, then return idle; reject rather than queue behind a pending write | SKMB-2026-07-18-014 |
| T38 | settings idle | player confirms a valid profile candidate | settings_saving | Prepare a complete replacement, atomically replace `models.json`, then publish the prepared runtime for future requests | SKMB-2026-07-18-015 |
| T39 | settings idle | player starts connection test after cost notice | connection_testing | Send one isolated cancellable real model probe; discard content and retain only redacted transient status/latency | SKMB-2026-07-18-015 |
| T40 | settings idle | confirmed typed settings action starts | settings_mutating | Run one domain-owned async mutation; publish one immutable terminal snapshot and never enqueue a hidden second write | SKMB-2026-07-18-016 |
| T41 | Tool configuration current | player confirms valid Tool enablement/source candidate | tool_config_saving | Atomically replace only the owning Tool file and publish one prepared immutable Tool/source snapshot for future client requests | SKMB-2026-07-19-019 |
| T42 | semantic_tail_literal | syntax closes and validates | semantic_tail_validated | Replace only the mutable tail with immutable safe Markdown/reference/component nodes | SKMB-2026-07-18-018 |
| T43 | history window idle | viewport requests another neighborhood | history_page_loading | Start or coalesce one generation-bound page read; retain the current anchor/window | SKMB-2026-07-18-018 |
| T44 | history_page_loading | page succeeds or fails | history window idle | Merge the matching page and preserve the anchor, or retain the prior window with a retryable diagnostic | SKMB-2026-07-18-018 |
| T45 | preparing | selected topology requires durable context | context_loading | Stream a provider-neutral seed under the actual selected model budget before dispatch | SKMB-2026-07-18-018 |
| T46 | context_loading | seed validates or fails | model_wait or failed | Dispatch exactly once with valid context, or fail before provider I/O and preserve history | SKMB-2026-07-18-018 |
| T47 | history_schema_rebuilding | rebuild succeeds or fails | idle or persistence_unavailable | Publish the fresh current schema, or roll back and report `history_schema_rebuild_failed` | SKMB-2026-07-19-019 |
| T48 | history_loading | future, corrupt, foreign, missing-metadata, or unrecognized database opens | persistence_unavailable | Fail closed without deleting or rewriting the file | SKMB-2026-07-19-019 |
| T49 | settings idle | player saves a model candidate with a replacement API key | credential_staged | Validate the complete candidate and insert a new immutable local secret without changing the active profile/runtime | SKMB-2026-07-19-019 |
| T51 | bundled or local Skill selected | player creates/saves an override | skill_reloading | Validate uppercase `SKILL.md` package confinement and atomically publish the valid local override | SKMB-2026-07-19-019 |
| T52 | credential_staged | profile replacement succeeds or fails | profile_referenced or unchanged | Publish only a fully resolvable profile/runtime; otherwise retain the prior reference/runtime and leave the staged row unreachable for later collection | SKMB-2026-07-19-019 |
| T53 | model_wait | response body begins | response_streaming | Record redacted attempt/progress/deadline state and decode under the same cancellable request budget | SKMB-2026-07-19-020 |
| T54 | response_streaming | tool call, final response, cancellation, protocol failure, or total timeout | tool_wait, completing, cancelled, or failed | Close the stream/watchdog, publish the correlated terminal/next phase, and suppress every late delta | SKMB-2026-07-19-020 |
| T55 | client context capture | registered observable sections detach successfully | observable_snapshot_ready | Release live game objects and publish the immutable evidence-bearing snapshot to the request context | SKMB-2026-07-19-020 |
| T56 | observable_snapshot_ready | one valid section query executes | tool_wait then model_wait | Return only the requested typed player-observable data with explicit authority/completeness | SKMB-2026-07-19-020 |
| T57 | settings idle | player fetches provider models for a valid draft | model_catalog_loading | Use the transient key or resolve the saved reference and send one cancellable configuration-layer GET | SKMB-2026-07-19-021 |
| T58 | model_catalog_loading | valid catalog, redacted failure, cancel, or stale generation | settings idle | Publish only current validated IDs/failure; ignore stale completion and retain typed model ID | SKMB-2026-07-19-021 |
| T59 | server model_wait | trusted placement selects an enabled player-client Tool | client_tool_wait | Bind actor/request/invocation and send one strict reverse Tool call to that same client | SKMB-2026-07-19-021 |
| T60 | client_tool_wait | normalized success or Tool failure returns | server model_wait | Append the complete Tool result and continue; do not terminate the Agent for ordinary Tool failure | SKMB-2026-07-19-021 |
| T61 | client_tool_wait | request cancel, disconnect, or shutdown | cancelled | Cancel the correlation and suppress late client result chunks | SKMB-2026-07-19-021 |
| T62 | remote Tool/chunk wait | normalized result, ordinary bridge failure, deadline, or enclosing request terminal | model_wait, released, or cancelled | Continue with a structured Tool failure for ordinary failure/deadline; release incomplete assemblies and suppress every late chunk on terminal state | SKMB-2026-07-19-021 |
| T63 | visible validated component | enter viewport | native_view_resolving | Resolve its exact reference through registered client-thread providers | SKMB-2026-07-19-022 |
| T64 | native_view_resolving | exact provider succeeds or providers exhaust/fail | native_view_ready or native_view_fallback | Publish the provider view, generic detached canvas, or readable fallback without changing Agent state | SKMB-2026-07-19-022 |
| T65 | native_view_ready or native_view_fallback | row leaves viewport, screen closes, or generation changes | released | Release all live provider objects and hit regions on the client thread | SKMB-2026-07-19-022 |
| T66 | knowledge_index_building | detached generation succeeds or fails | knowledge_index_ready or knowledge_index_degraded | Atomically publish the new index or retain the prior valid/local lexical path | SKMB-2026-07-19-022 |
| T67 | Guide session visible | player requests deletion | deletion_confirming_first | Capture the selected session ID and show the first native confirmation without mutating state | SKMB-2026-07-19-023 |
| T68 | deletion_confirming_first | player confirms | deletion_confirming_final | Show the irreversible/cancellation warning bound to the same captured session ID | SKMB-2026-07-19-023 |
| T69 | deletion_confirming_final | player confirms or dismisses | session deleted or unchanged | Invoke the existing fenced close only after confirmation; either dismissal performs no action | SKMB-2026-07-19-023 |
| T70 | export idle | player exports selected session | export_collecting then export_writing | Capture immutable live sequences, read every durable page, redact, and atomically publish under the managed export directory | SKMB-2026-07-19-023 |

## Invariants

| id | invariant | source |
| --- | --- | --- |
| I1 | Client-local mode works without OpenAllay installed on the server | SKMB-2026-07-17-001 |
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
| I45 | Player-initiated normal history management is actor-scoped and whole-database reset is Debug Mode-only and separately confirmed; the only automatic destructive policy is the transactionally scoped rebuild of recognized unshipped schemas 1 through 4 | SKMB-2026-07-18-014, SKMB-2026-07-19-019, SKMB-2026-07-19-020 |
| I46 | Profile replacement is candidate-validated, atomically persisted, and published as one prepared runtime state; failure retains the prior file/runtime | SKMB-2026-07-18-015 |
| I47 | Connection testing is an explicit isolated real request with no Guide context/tools/history, no retry/fallback, and no retained secret/body/output | SKMB-2026-07-18-015 |
| I48 | Native settings use one common operation/snapshot service while model/credential, Tool/source, Skill, display, metadata, and history persistence remain independently versioned | SKMB-2026-07-18-016, SKMB-2026-07-19-019 |
| I49 | Settings file/provider/SQLite work never runs on a Minecraft-owned thread, and screen detach never owns or rolls back a confirmed durable mutation | SKMB-2026-07-18-016 |
| I49a | The schema-3 local assistant name is validated presentation identity; rename and display-toggle writes preserve every unaffected display field and never rewrite history, evidence, sessions, tools, or model authority | SKMB-2026-07-18-016 |
| I50 | Tool settings can only narrow registered local Tool access, while Skill documents and `allowed-tools` dependencies grant no authority; every active request retains one captured immutable Tool/source/Skill snapshot | SKMB-2026-07-19-019 |
| I51 | Tool-specific source settings use stable registered IDs under that tool's child page; adding JEI/REI/EMI/future adapters does not add top-level mod settings fields | SKMB-2026-07-18-017 |
| I52 | Semantic output is a versioned closed AST; HTML, URLs, embeds, arbitrary UI trees, code, callbacks, commands, and mutations are unrepresentable | SKMB-2026-07-18-018 |
| I53 | Raw resource existence is presentation-only; actionable stable handles must originate in the same authorized request context | SKMB-2026-07-18-018 |
| I54 | Every semantic component has readable fallback text and narration, and color/animation never owns state | SKMB-2026-07-18-018 |
| I55 | GUI viewport paging and model-context selection are independent and neither loads a complete durable partition into memory | SKMB-2026-07-18-018 |
| I56 | Incremental history writes and page/context reads remain ordered off Minecraft-owned threads and generation-check every completion | SKMB-2026-07-18-018 |
| I57 | Auto-scroll follows only while already at the bottom; earlier-page insertion preserves the player's anchor row and pixel offset | SKMB-2026-07-18-018 |
| I58 | Client model schema 2 and all observable settings state retain only qualified credential references/presence; raw API keys exist only in the transient masked input, SecretValue, provider header boundary, and local `credentials.sqlite3`, while `env:<name>` remains external/headless-only | SKMB-2026-07-19-019 |
| I59 | Every source is owned and strictly validated by one logical Tool; built-in sources cannot be deleted, while registered user source kinds may support full CRUD | SKMB-2026-07-19-019 |
| I60 | Bundled Skills are read-only Agent Skills packages with uppercase `SKILL.md`; local edits are external overrides and never grant scripts, paths, tools, or Agent write authority | SKMB-2026-07-19-019 |
| I61 | Only recognized unshipped OpenAllay history schemas 1 through 4 rebuild automatically; future, corrupt, foreign, missing/inconsistent-metadata, or otherwise unrecognized databases remain untouched | SKMB-2026-07-19-019, SKMB-2026-07-19-020 |
| I62 | Every active request has a redacted observable phase, elapsed basis, last-progress time and optional retry/deadline; clocks never create transcript or persistence writes | SKMB-2026-07-19-020 |
| I63 | The configured model request timeout covers complete response-body consumption, and cancel/timeout/disconnect suppress every late stream event | SKMB-2026-07-19-020 |
| I64 | Rendering never owns scroll mutation; streaming keeps a stable literal tail and preserves manual viewport anchors | SKMB-2026-07-19-020 |
| I65 | `inspect_game_state` is one strict sectioned read-only Tool for directly player-observable UI/HUD/F3/player-owned/query state; it cannot execute command strings, reflect arbitrary fields, scan spatial world content, inspect external containers, or write | SKMB-2026-07-19-020 |
| I66 | Recipes and Guides remain independent narrow high-volume deep-content Tool families, while future map/block/container interaction requires a separate decision and authority boundary | SKMB-2026-07-19-020 |
| I67 | Player-observable state is captured on the owning Minecraft thread into immutable evidence-bearing records; missing sections degrade independently and never become fabricated empty facts | SKMB-2026-07-19-020 |
| I68 | Remote Tool failures in either direction are complete model-visible Tool results unless the enclosing request is cancelled/disconnected; partial bridge assemblies are sparse, active-request scoped, and bounded by the five-minute bridge deadline | SKMB-2026-07-19-021 |
| I69 | Model-authored semantic data cannot name native widget classes, textures, slots, coordinates, callbacks, commands, URLs, or arbitrary view trees | SKMB-2026-07-19-022 |
| I70 | Exact JEI/REI/mod views are embedded only through verified public APIs; the generic fallback is neutral OpenAllay UI and never imitates a mod screen | SKMB-2026-07-19-022 |
| I71 | Live native-view objects exist only for visible rows on the Minecraft client thread and never enter history, bridge payloads, model context, or workers | SKMB-2026-07-19-022 |
| I72 | Table structure, focus identity, viewport ownership, and streaming row identity survive incremental rendering; a missing focus ID is never selected | SKMB-2026-07-19-022 |
| I73 | Knowledge retrieval remains useful offline, preserves stable provenance/evidence, and never requires an embedding provider | SKMB-2026-07-19-022 |
| I74 | Conversation history, derived summaries, player memory, and live game facts remain distinct; neither summary nor player memory satisfies factual evidence requirements | SKMB-2026-07-19-022 |
| I75 | Durable player-memory writes require explicit player confirmation and remain disabled until their management UI and persistence contract are implemented | SKMB-2026-07-19-022 |
| I76 | Session deletion requires two affirmative native confirmations bound to one captured session ID; dismissing either confirmation mutates nothing | SKMB-2026-07-19-023 |
| I77 | Player conversation export has no arbitrary-path input, publishes only complete atomic files under `gameDir/openallay/exports`, and excludes credentials, normalized Tool data, checkpoints, model settings, and raw diagnostics | SKMB-2026-07-19-023 |
| I78 | Clipboard writes are explicit local player actions over already-visible user or assistant text and are never exposed as an Agent Tool | SKMB-2026-07-19-023 |
| I79 | Same-turn Tool calls may execute concurrently, but the complete provider continuation preserves original ToolUse order and exact invocation identity | SKMB-2026-07-19-024 |
| I80 | The analytical game-content surface is a closed typed virtual dataset over detached snapshots; shell text, scripts, arbitrary paths/URLs, reflection, and mutation are unrepresentable | SKMB-2026-07-19-024 |
| I81 | Automatic model transport retry is allowed only before response progress and is bounded to two retries; HTTP 4xx, timeout, partial stream, cancel, and Tool execution are never replayed | SKMB-2026-07-19-024 |
| I82 | Fixed online documentation sources fail independently, remain partial public evidence, and never replace current-game authoritative snapshots | SKMB-2026-07-19-024 |
| I83 | Current biome, coordinates, dimension, and direction are client-visible diagnostics and never require server command permission | SKMB-2026-07-19-024 |

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
| F26 | A future, corrupt, foreign, missing-metadata, or otherwise unrecognized history database is opened | Fail `history_schema_unsupported` or `history_corrupt` without mutation; never guess that destructive rebuild is safe | SKMB-2026-07-19-019 |
| F27 | Metadata cache is absent/invalid or refresh fails | Preserve explicit configuration and the last valid cache, publish a redacted diagnostic, and never block startup | SKMB-2026-07-18-012 |
| F28 | History deletion overlaps an active request/pending write or its transaction fails | Reject or roll back without deleting/resurrecting data; require an explicit later retry | SKMB-2026-07-18-014 |
| F29 | Profile validation/preparation/write fails | Report `invalid_model_config` or `settings_write_failed`; retain the previous file and runtime without partial publication | SKMB-2026-07-18-015 |
| F30 | Connection probe is busy, cancelled, rejected, rate-limited, times out, or returns malformed/empty output | Classify it into a stable redacted `connection_*` failure, send no retry, and leave settings/history unchanged | SKMB-2026-07-18-015 |
| F31 | A settings mutation conflicts, strict validation fails, or atomic persistence/reload fails | Reject as `settings_busy` or a stable domain/write failure; retain every unaffected prior file/runtime and never partially apply another domain | SKMB-2026-07-18-016 |
| F32 | A Tool policy or Skill dependency conflicts, or a disabled/unavailable capability is invoked | Reject the save or invocation explicitly; never silently enable/fallback or widen authority | SKMB-2026-07-18-017, SKMB-2026-07-19-019 |
| F33 | Markdown, semantic reference, or controlled component is incomplete, malformed, unknown, or unauthorized | Keep readable fallback text, expose no action, and optionally report only a redacted debug diagnostic | SKMB-2026-07-18-018 |
| F34 | A viewport page read fails or completes for a stale generation | Retain the current window/anchor, suppress stale publication, and allow explicit retry | SKMB-2026-07-18-018 |
| F35 | A durable model-context seed cannot be read or structurally validated | Fail `history_context_failed` before provider dispatch and preserve every durable row | SKMB-2026-07-18-018 |
| F36 | Native semantic rendering fails for one node | Render its text/narration fallback and keep the screen usable; never fabricate a component success | SKMB-2026-07-18-018 |
| F37 | Stored credential resolution or persistence fails | Keep prior profile/runtime where possible, expose a stable redacted failure, and send no provider request with a missing or guessed credential | SKMB-2026-07-19-019 |
| F38 | A Tool source candidate is malformed, unavailable, or unauthorized | Reject/retain it with a source-scoped diagnostic and leave unrelated Tools/sources unchanged | SKMB-2026-07-19-019 |
| F39 | A local Skill override is malformed or escapes the supported Agent Skills subset | Retain the prior valid/bundled Skill, expose a source-scoped validation diagnostic, and keep unrelated Skills available | SKMB-2026-07-19-019 |
| F40 | A recognized older history schema cannot be rebuilt transactionally | Roll back, preserve the prior database, report `history_schema_rebuild_failed`, and make persistence unavailability visible | SKMB-2026-07-19-019 |
| F41 | A dispatched model response does not complete within its configured total deadline | Close the body, fail `model_timeout`, retain completed chronology, suppress late deltas, and require explicit retry | SKMB-2026-07-19-020 |
| F42 | A player-observable section/query is unknown, malformed, unsupported, partial, or not authoritative in the current topology | Return strict invalid/unavailable/partial evidence for that section and keep every unrelated section usable; never guess or broaden access | SKMB-2026-07-19-020 |
| F43 | Resource resolution is ambiguous or a corrected Tool search remains unchanged and empty/partial | Return every deterministic exact match for disambiguation, or stop after one corrected call and explain the missing evidence; never loop or choose arbitrarily | SKMB-2026-07-19-020 |
| F44 | A remote Tool bridge is unavailable, times out, returns malformed data, or leaves an incomplete chunk assembly | Return the matching structured Tool failure and continue the Agent; expire/release partial state and never publish truncated data | SKMB-2026-07-19-021 |
| F45 | An exact native-view provider is absent, stale, unsupported, or throws | Release/isolate it and fall through to the next provider, neutral generic canvas, or readable fallback; never fail the Agent request | SKMB-2026-07-19-022 |
| F46 | Table geometry is invalid or cannot preserve readable columns | Use the structural narrow key/value projection, then narration fallback if validation itself failed | SKMB-2026-07-19-022 |
| F47 | A streaming semantic replacement measures shorter than its mutable row reservation | Preserve the reservation and viewport anchor until terminal reflow can occur without moving player-owned scroll | SKMB-2026-07-19-022 |
| F48 | Knowledge indexing/reranking fails or embeddings are unavailable | Retain the last valid index or deterministic local lexical path and expose a source-scoped diagnostic; never fabricate empty results | SKMB-2026-07-19-022 |
| F49 | Session export paging, confinement, redaction, or atomic publication fails | Publish no final file, remove temporary output where possible, retain conversation state, and show a localized failure | SKMB-2026-07-19-023 |
| F50 | Clipboard access fails | Preserve the transcript and show a localized copy failure without exposing exception details | SKMB-2026-07-19-023 |
| F51 | One parallel Tool call fails while sibling calls settle | Publish the correlated structured failure and every sibling result in original order; never send a partial or reordered group | SKMB-2026-07-19-024 |
| F52 | A virtual query is malformed or its unprojected result is too large | Fail `invalid_tool_arguments` or `result_too_large` with exact schema/cardinality guidance; never silently truncate or execute arbitrary expressions | SKMB-2026-07-19-024 |
| F53 | A provider returns HTTP 400 or another non-retryable 4xx | Classify a bounded allowlisted error as request/context/protocol rejection, retain redacted diagnostics, and require a corrected request; never expose or automatically replay the body | SKMB-2026-07-19-024 |
| F54 | A pre-progress model transport attempt fails | Retry at most twice with cancellable short backoff; after progress or exhaustion, retain chronology and end with a friendly retryable transport failure | SKMB-2026-07-19-024 |
| F55 | One fixed online knowledge source times out, rejects, or changes format | Retain local and other source results, mark only that adapter degraded, and return partial evidence rather than fabricated absence | SKMB-2026-07-19-024 |

## Reviewed Statistical Defaults

| id | pattern | context | default | reason_allowed | review_by | file |
| --- | --- | --- | --- | --- | --- | --- |
| SKMB-2026-07-18-006 | A, B, C, E, F | durable history execution details | hashed connection discriminator, async single writer, explicit loading rejection; full-partition persistence superseded by SKMB-018 incremental/windowed execution | designer delegated best implementation path; product invariants already accepted | reviewed in retained pre-SKMB-019 evidence; correction acceptance remains open | decisions/2026-07-18-006-durable-history-execution.md |
| SKMB-2026-07-18-007 | A, B, D, E, F | recipe provider execution details | client-thread capture, generation-bearing references, public optional viewer APIs, partial omission diagnostics | designer delegated best implementation path; recipe authority and visibility already accepted | reviewed in retained pre-SKMB-019 evidence; correction acceptance remains open | decisions/2026-07-18-007-recipe-provider-execution.md |
| SKMB-2026-07-18-008 | A, B, C, D, E, F | context compaction execution details | explicit per-model window, conservative UTF-8 estimator, structural prefix summaries, source-hash checkpoint reuse | designer delegated best implementation path; compaction authority and failure semantics already accepted | reviewed in retained pre-SKMB-019 evidence; correction acceptance remains open | decisions/2026-07-18-008-context-compaction-execution.md |

## Open Decisions

| id | pattern | context | needed_before | file |
| --- | --- | --- | --- | --- |
