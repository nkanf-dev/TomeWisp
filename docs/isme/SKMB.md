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

## Invariants

| id | invariant | source |
| --- | --- | --- |
| I1 | Client-local mode works without TomeWisp installed on the server | SKMB-2026-07-17-001 |
| I2 | At most one Agent request is active per player/client | SKMB-2026-07-17-001 |
| I3 | API credentials never enter packets, prompts, traces, logs, or tool results | SKMB-2026-07-17-001 |
| I4 | Only registered read-only tools are callable in Phase 2 | SKMB-2026-07-17-001 |
| I5 | Live Minecraft objects never leave their owning game thread | SKMB-2026-07-17-001 |
| I6 | Missing integrations and failed tools never become fabricated facts | SKMB-2026-07-17-001 |
| I7 | Server-enhanced tools authorize and scope every request to the sending player | SKMB-2026-07-17-001 |
| I8 | Requests preserve per-session order and rate-limited queues rotate sessions fairly | SKMB-2026-07-17-003 |
| I9 | A failed or cancelled server request releases its slot and cannot stall the queue | SKMB-2026-07-17-002 |
| I10 | Different sessions owned by the same player may execute concurrently | SKMB-2026-07-17-003 |
| I11 | Rate-limit state is isolated by model endpoint/credential configuration | SKMB-2026-07-17-003 |

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

## Statistical Defaults Allowed Temporarily

| id | pattern | context | default | reason_allowed | review_by | file |
| --- | --- | --- | --- | --- | --- | --- |

## Open Decisions

| id | pattern | context | needed_before | file |
| --- | --- | --- | --- | --- |
