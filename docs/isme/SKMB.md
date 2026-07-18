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

SKMB-2026-07-18-006 is implemented by `a0eaeff`, `19ab90f`, and `c6ca6bc`.
Its deterministic clean-build and packaged-driver evidence is recorded in the
Phase 4B durable-history plan. Final graphical restart review remains open.

SKMB-2026-07-18-007 is implemented through `5af5b4e`. Its deterministic
provider/catalog/navigation coverage and retained Fabric JEI/REI/Farmer's
Delight graphical evidence are recorded in the Phase 4C plan and
`docs/verification/phase-4c-all-known-recipes/`.

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
| T31 | history_loading | schema-v1 partition opens | history_loading | Transactionally add session-owned checkpoint storage, preserve messages/timeline, then hydrate schema v2 | SKMB-2026-07-18-008 |
| T32 | any non-active session state | selected model/provider changes | unchanged | Keep the provider-neutral transcript/checkpoints; assemble the next request with the new model and its budget | SKMB-2026-07-18-008 |

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

## Statistical Defaults Allowed Temporarily

| id | pattern | context | default | reason_allowed | review_by | file |
| --- | --- | --- | --- | --- | --- | --- |
| SKMB-2026-07-18-006 | A, B, C, E, F | durable history execution details | hashed connection discriminator, async single writer, explicit loading rejection, ordered full-partition saves | designer delegated best implementation path; product invariants already accepted | final Phase 4 real-client acceptance | decisions/2026-07-18-006-durable-history-execution.md |
| SKMB-2026-07-18-007 | A, B, D, E, F | recipe provider execution details | client-thread capture, generation-bearing references, public optional viewer APIs, partial omission diagnostics | designer delegated best implementation path; recipe authority and visibility already accepted | final Phase 4 real-client acceptance | decisions/2026-07-18-007-recipe-provider-execution.md |
| SKMB-2026-07-18-008 | A, B, C, D, E, F | context compaction execution details | explicit per-model window, conservative UTF-8 estimator, structural prefix summaries, source-hash checkpoint reuse | designer delegated best implementation path; compaction authority and failure semantics already accepted | final Phase 4 real-client acceptance | decisions/2026-07-18-008-context-compaction-execution.md |

## Open Decisions

| id | pattern | context | needed_before | file |
| --- | --- | --- | --- | --- |
