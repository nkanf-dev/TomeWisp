# SKMB-2026-07-17-004: Phase 3 Product State

- status: accepted
- decided_by: designer
- approval_source: designer explicitly instructed “接下来我不会在这里监管，直接用你最推荐的设计就可以。但是记得把所有的决策设计，就像你被要求的那样，你把它落盘，可以追溯就可以” after the recommended full-screen GUI and Phase 3 decomposition were presented
- date: 2026-07-17
- commit: e4a77ad
- patterns:
  - A_async_wait
  - B_state_persistence
  - C_concurrent_operations
  - D_external_dependency
  - E_security_boundary
  - F_fail_semantics
- scope: Phase 3 GuideService, grounded tools, client GUI, and real-game E2E

## Context

Phase 3 adds one persistent client-facing state source consumed by both commands
and a full-screen GUI. Requests may wait on models, rate gates, local tools, or
remote tools. Multiple sessions and model locations coexist, while disconnect
and capability changes can invalidate connection-scoped context.

The designer delegated the remaining design choices to the recommended design
on the condition that decisions remain persisted and traceable.

## Decision

Phase 3 uses one common GuideService and immutable GuideSnapshot event store.
Commands and GUI are projections over this service and never own independent
Agent, model-mode, context, or network state.

One request may run per `(actorId, sessionId)` and different sessions may run in
parallel. Closing the GUI does not cancel work. Explicit cancel, session close,
disconnect, or shutdown cancels affected work and suppresses late events.
Disconnect also clears connection-scoped in-memory sessions and capabilities to
prevent knowledge from one server appearing in another server's conversation.

Model-mode changes affect new requests only. Active requests retain their
original topology. Failure never silently switches from client to server model,
from server to client model, or to a different tool. Retry is explicit, uses a
new request ID in the same session, and reuses only the retained user message.

Tool facts carry authority, completeness, capture time, source, and provenance.
Incomplete evidence cannot produce a conclusive positive craftability result.
Context is captured on the owning Minecraft thread when queued work starts and
remains immutable for that request.

The GUI is an independent full-screen non-pausing in-world Screen opened by a
configurable default `K` mapping or `/guide`. Escape closes without cancelling.
Reasoning deltas are diagnostic-only. Visible state comes only from GuideService.

## Applies To

- grounded recipe, inventory, and craftability DTOs/tools
- `GuideService`, snapshots, events, sessions, retry, and mode selection
- loader command/key/lifecycle adapters
- server Agent event protocol and remote correlations
- GUI transcript, tool cards, sources, and configuration states
- disconnect/shutdown cleanup and all Phase 3 race/failure tests
- real-client E2E reports and final completion audit

## Invariants

1. Commands and GUI observe the same request/session truth.
2. Screen lifetime is independent from request lifetime.
3. Connection-scoped history never crosses a disconnect.
4. Same-session history cannot be concurrently mutated by two requests.
5. Different sessions are not globally serialized by player identity.
6. Credentials, reasoning, and unauthorized server data never enter visible
   messages, packets, traces, or source details.
7. Every factual success exposes evidence; unknown/incomplete remains explicit.
8. No automatic topology fallback changes who pays for or answers a request.

## Failure Semantics

- Same-session overlap: `agent_busy`.
- Missing target model/capability: `capability_unavailable`.
- Stale recipe/source reference: `stale_reference` with no fabricated fallback.
- Disconnect/shutdown: cancel, suppress late events, clear scoped state.
- Malformed remote event: fail the correlated request closed.
- Provider 429: retain cancellable endpoint-scoped fair waiting.
- Incomplete recipe/inventory: return non-conclusive craftability evidence.

## Alternatives

- GUI-owned orchestration: rejected because commands and GUI would diverge.
- Closing the screen cancels: rejected because ordinary UI navigation should not
  destroy a long model request.
- Persist sessions across servers: rejected because it risks context leakage and
  has no Phase 3 user requirement.
- Silent client/server fallback: rejected because it changes authority, cost,
  credentials, and semantics without player intent.
- Model arithmetic for inventory allocation: rejected in favor of deterministic
  capacity matching.

## Supersedes

- Clarifies the connection-lifetime portion of SKMB-2026-07-17-001.
- Preserves and applies SKMB-2026-07-17-003 concurrency and 429 semantics to
  GuideService and GUI.

## Superseded By

None.
