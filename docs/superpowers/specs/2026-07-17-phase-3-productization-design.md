# TomeWisp Phase 3 Productization Design

Status: approved through the designer's explicit instruction to use the
recommended design and persist every decision for traceability.

## 1. Goal

Phase 3 turns the Phase 2 Agent foundation into a player-usable product. It must
prove the real `/guide` path in Minecraft, replace underspecified knowledge
tools with grounded domain contracts, and provide one player GUI over the same
service and event stream used by commands.

Phase 3 is complete only when all required Fabric and NeoForge 26.2 topology,
tool, GUI, automation, documentation, and CI acceptance evidence exists.

Structure parsing and generated Ponder scenes are not a Phase 3 completion
dependency. The later accepted `SKMB-2026-07-18-005` roadmap defers them beyond
Phase 4; Phase 4 instead covers recipe sources, durable history/context, and
Minecraft-native rich UI.

## 2. Delivery decomposition

Phase 3 is split into three independently testable subprojects in dependency
order:

1. **Phase 3A — grounded tool contracts.** Stabilize recipe, inventory,
   craftability, authority, completeness, and provenance contracts.
2. **Phase 3B — GuideService and real-game E2E.** Move command and topology
   orchestration behind one common client service, then prove all required
   paths in actual game clients.
3. **Phase 3C — player GUI.** Build a full-screen chat experience over the same
   GuideService state and events. The GUI does not call the Agent, model, or
   network bridge directly.

Each subproject gets its own implementation plan and commits. A later phase may
consume their stable interfaces without reopening Phase 3 architecture.

## 3. Approaches considered

### 3.1 Recommended: staged shared core

Stabilize tools, establish one GuideService and E2E path, then add the GUI as a
consumer. Common code owns product semantics; loader modules only register
commands, key mappings, lifecycle hooks, screens, and packet transports.

This approach prevents UI code from freezing incomplete DTOs and prevents the
Fabric and NeoForge command implementations from continuing to drift.

### 3.2 Rejected: GUI first

A GUI could be produced quickly over `ClientGuideRuntime`, but it would need to
duplicate server-model routing, source resolution, retry behavior, and session
state currently embedded in loader commands. It would also expose the current
flat recipe DTO, which already caused a live model to infer missing workstation
and completeness facts.

### 3.3 Rejected: loader-specific vertical products

Keeping one complete implementation per loader reduces abstraction work at
first, but doubles every state and failure decision. Existing command files
already contain parallel copies of model-mode, context, knowledge reload, and
event rendering logic. Phase 3 removes rather than expands that duplication.

## 4. Cross-cutting architecture

```text
Fabric/NeoForge command + key/lifecycle adapters
                         |
                         v
                    GuideService
             /           |            \
       GuideStore    ContextSource    TopologyClient
          |               |          /              \
       GUI/command   client snapshot  local Agent   server Agent
          |                              |
          +--------- GuideEvent ---------+
```

The service exposes immutable snapshots and request-correlated events. Commands
and screens render those snapshots; neither owns sessions or transport policy.
The local and server-model topologies normalize into the same GuideEvent model.

All live Minecraft objects are detached on their owning thread. Every factual
tool output states authority, completeness, capture time, source, and
provenance. The Agent may explain incomplete data but must not silently promote
it to an authoritative complete result.

## 5. Product decisions

- Client-first operation remains the default; no server mod is required.
- Model location and tool location remain independent.
- One request may run in each `(actorId, sessionId)`; different sessions may run
  concurrently.
- Closing the GUI does not cancel work. Explicit cancel, disconnect, or client
  shutdown does.
- Disconnect cancels active work and clears that connection's in-memory
  conversation state to prevent cross-server context disclosure.
- Switching model mode affects new requests only. An active request finishes on
  its original topology unless explicitly cancelled.
- Retry is explicit. It creates a new request in the same session using the last
  failed user message; TomeWisp never silently changes model location or tools.
- Reasoning deltas remain diagnostic-only and are never rendered as an answer.
- No project-defined recipe, inventory, result, history, or queue limit is added
  in Phase 3. UI lists use virtualization rather than data truncation.
- Tools stay read-only. Phase 3 introduces no shell, arbitrary reflection,
  scripting, world mutation, or executable Skill behavior.

These decisions are indexed by `SKMB-2026-07-17-004`.

## 6. Failure behavior

- Missing client model configuration opens a useful setup diagnostic in the GUI
  and returns a structured command error.
- A client-only request for a server-authoritative fact returns an explicit
  incomplete/unavailable result; it does not fabricate server data.
- Invalid or stale recipe references fail explicitly and may be searched again
  only through a new model decision.
- Tool failures are returned to the model and displayed in the request's tool
  activity. A terminal model/transport/protocol failure ends the request.
- HTTP 429 retains the existing endpoint-scoped fair queue and emits visible
  waiting state. It is not a terminal failure.
- Disconnect and shutdown suppress all late events.
- Malformed server events or identity/correlation failures fail closed and do
  not mutate a different request or session.

## 7. Acceptance gates

Phase 3 cannot be declared complete until all of these are proven from current
artifacts and runtime evidence:

1. Tool contract tests cover client-visible, server-authoritative, partial,
   complete, stale, alternatives, counts, usage, and craftability cases.
2. A real Fabric client in a world completes `/guide` through a real Agent loop
   and real local Minecraft snapshots.
3. A real NeoForge client proves the same path.
4. Client model plus authenticated server tools passes on both loaders.
5. Server-hosted model events, cancellation, session isolation, disconnect, and
   429 queue behavior pass on both loaders.
6. The GUI opens from a configurable key and `/guide`, streams text, shows tool
   state, cancels/retries, switches sessions/model mode, and opens sources.
7. Commands and GUI demonstrate identical GuideService state transitions.
8. A real provider smoke test proves multi-tool continuation and secret
   redaction against the final contracts.
9. Unit, protocol, loader build, dedicated server, client E2E, secret scan, and
   CI evidence are green for the final commit.
10. README, development documentation, product design, and phase status describe
    the implementation that actually exists.

## 8. Traceability

- Tool design: `2026-07-17-phase-3a-grounded-tools-design.md`
- Service and E2E design: `2026-07-17-phase-3b-guide-service-e2e-design.md`
- GUI design: `2026-07-17-phase-3c-player-gui-design.md`
- State decisions: `docs/isme/decisions/2026-07-17-004-phase-3-product-state.md`
