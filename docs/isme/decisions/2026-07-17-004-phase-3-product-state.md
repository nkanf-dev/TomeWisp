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

Cancellation releases the `(actorId, sessionId)` admission slot immediately,
while the canceled execution may still be unwinding. Its lease is fenced by
request ID, so a late completion cannot overwrite a newer request in the same
session. This keeps cancellation responsive without permitting concurrent
history mutation.

The server Agent event protocol is version 2 and has one strict common codec.
It validates request correlation, terminal flags, and concrete event fields;
unknown or malformed events fail only the correlated request closed. Model-safe
encoded tool names are normalized back to registered tool IDs when start and
completion events are joined, preventing duplicate tool activities in command,
GUI, trace, and E2E projections.

Tool facts carry authority, completeness, capture time, source, and provenance.
Incomplete evidence cannot produce a conclusive positive craftability result.
Context is captured on the owning Minecraft thread when queued work starts and
remains immutable for that request.

Phase 3A implementation fixes three additional projections of that decision:

- an unloaded or source-empty knowledge snapshot carries explicit `UNKNOWN` or
  source evidence and never means “the pack has no documentation” by omission;
- recipe alternatives are allocated by deterministic global capacity matching,
  while recursive crafting of intermediates remains outside this tool contract;
- `find_recipes` is a deprecated compatibility projection over the same catalog;
  new Skills use search, exact detail, inventory inspection, then craftability.

The serialization boundary fails closed when an `EvidenceBearing` factual
output returns no evidence, with stable failure text
`Grounded tool output has no evidence`.

The GUI is an independent full-screen non-pausing in-world Screen opened by a
configurable default `K` mapping or `/guide`. Escape closes without cancelling.
Reasoning deltas are diagnostic-only. Visible state comes only from GuideService.

Real-client automation is a development harness, not an Agent capability. It is
inert unless an explicit JVM property enables it, starts only after a player
exists, uses GuideService asynchronously on the client thread, writes a
canonical redacted report outside the tool registry, and may request clean
shutdown. CI may claim controller/build coverage without claiming a graphical
run; real-client coverage requires a retained report from an explicit run.

The Phase 3C implementation uses only Minecraft native Screen/widgets and a
pure immutable `GuideUiView`; it introduces no second UI framework. Normal
widths show a session rail and inline detail drawer, while narrow widths use
overlays without truncating the underlying transcript data. Transcript drawing
is scissored and virtualized by visible wrapped lines. The multiline composer
uses `Ctrl+Enter` for submission so ordinary Enter remains available for text.

Tool/source detail is a projection of normalized authorized results and
evidence. Recipe search, recipe detail, inventory and craftability have
first-class concise presenters; unknown tools retain a deterministic JSON
fallback. Switching sessions or receiving disconnect-cleared state also clears
stale detail selections. No source action launches an external browser.

Fabric-owned coordinates resolve from the repository-local curl mirror first
and Fabric's official Maven second. Generic public mirrors explicitly exclude
`net.fabricmc` and `net.fabricmc.*`: a transient 5xx from a convenience mirror
must not make an authoritative Fabric dependency unavailable in CI. Other
dependency groups retain the existing mirror order and offline bootstrap path.

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
9. Empty evidence cannot cross the factual tool-result boundary as success.
10. An observed craftability result is distinct from a conclusive result.
11. A canceled request lease cannot mutate history or state after its session
    slot has been reused.
12. One server event is decoded and correlated exactly once before it reaches
    GuideService; loader adapters do not reinterpret event semantics.
13. Disabled E2E instrumentation has no session, model, file, or shutdown side
    effects, and its filesystem writer is never exposed to the model.
14. Closing or replacing OpenAllayScreen detaches only its subscription; request
    cancellation remains an explicit GuideService intent.
15. A detail drawer cannot retain evidence after its session/source disappears
    from the current GuideSnapshot.
16. Generic Maven mirrors never claim Fabric-owned dependency coordinates.

## Failure Semantics

- Same-session overlap: `agent_busy`.
- Missing target model/capability: `capability_unavailable`.
- Stale recipe/source reference: `stale_reference` with no fabricated fallback.
- Disconnect/shutdown: cancel, suppress late events, clear scoped state.
- Malformed remote event: fail the correlated request closed.
- Late event from canceled/replaced lease: ignore without affecting its successor.
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
