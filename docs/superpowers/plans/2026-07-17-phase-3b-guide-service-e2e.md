# Phase 3B GuideService and Command E2E Implementation Plan

> **Execution mode:** Follow this plan task-by-task with tests first. The designer delegated implementation choices provided every state/failure decision remains persisted in SKMB.

**Goal:** Replace loader-owned guide orchestration with one common, immutable-snapshot `GuideService`, normalize local and server-model events, and prove both loader command paths plus required topologies without introducing a second Agent loop.

**Architecture:** Keep `GameGuideAgent` and `ClientGuideRuntime` as the local execution engine. Add a client-thread-owned `GuideService` reducer above it. The service owns product sessions, model mode, transcript/request snapshots, retry, cancellation, topology selection, and lifecycle cleanup. Loader code supplies context/knowledge and network ports, then only registers commands and renders common command notices. Server events decode once in common code.

**Tech Stack:** Java 25, Minecraft 26.2, Fabric, NeoForge, JDK HTTP, Gson, JUnit 5, Gradle 9.5.

## Task 1: Complete observable Agent and bridge events

**Files:** `AgentEvent.java`, `GameGuideAgent.java`, bridge protocol records/codecs, `ServerAgentEventDecoder.java`, bridge/server tests.

1. Write failing tests for defensive normalized tool results, text/usage/rate-limit decoding, unknown event rejection, request correlation, and server-agent cancel payload validation.
2. Extend `ToolCompleted` with the normalized result so evidence/source cards can be derived without reading traces.
3. Decode every current server event type in common code; reject unknown type, malformed JSON, mismatched terminal flag, or request ID.
4. Add a dedicated server-agent cancel payload and route it to `ServerAgentService.cancel`; never reuse remote-tool correlation cancellation.
5. Verify common bridge/server tests and both loader compilers.

## Task 2: Immutable Guide state and reducer

**Files:** new `dev.tomewisp.guide` records/enums and `GuideStateReducer`; reducer tests.

1. Define immutable `GuideSnapshot`, `GuideSessionSnapshot`, `GuideRequestSnapshot`, `GuideMessage`, `GuideToolActivity`, `GuideSource`, `GuideFailure`, `GuideModelMode`, `GuideTopology`, and request/tool status enums.
2. Write transition tests for streaming text, tool start/result, evidence extraction, usage, rate wait, completion, failure, cancellation, and late-event suppression.
3. Keep reasoning deltas out of visible snapshots. Preserve complete normalized tool results only in source/detail state.
4. Stable ordering is session ID, request creation order, tool call order, then source identity.

## Task 3: Local topology GuideService

**Files:** `GuideService.java`, `GuideSubscription.java`, `GuideContextProvider.java`, `ClientGuideRuntime.java`, service tests.

1. Write failing tests for ask, snapshots/listeners, same-session busy, cross-session concurrency, cancel, explicit retry with new request ID, session select/create/close/clear, listener detach without cancellation, and unconfigured local model.
2. Make `ClientGuideRuntime` accept an explicit session ID while retaining compatibility overloads.
3. Marshal every mutation through `ClientEventDispatcher`; publish immutable snapshots through copy-on-write subscriptions.
4. Capture context only when the selected endpoint can start; retain the user message for retry but not mutable game objects.

## Task 4: Server topology and lifecycle

**Files:** `GuideRemoteEndpoint.java`, `GuideService.java`, both client bridges, service/bridge tests.

1. Test client/server mode selection, missing capability, active topology immutability, correlated remote events, explicit remote cancel, capability loss, disconnect, and shutdown.
2. Server mode remains usable when the client model is unconfigured.
3. Disconnect cancels local and remote work, unregisters correlations, clears sessions/transcript/capabilities, and resets mode to client.
4. No failure silently falls back between client and server topology.

## Task 5: Common context and knowledge gateway

**Files:** `MinecraftGuideContextProvider.java` or equivalent common client adapter, loader construction sites, tests.

1. Move Patchouli/optional FTB refresh and `ClientContextCapture` out of command classes.
2. Capture on the Minecraft client thread with one correlation/time boundary and only the required tool capabilities.
3. Return structured `player_required`, `capability_unavailable`, or refresh diagnostics; never throw into Brigadier callbacks.

## Task 6: Thin common command operations

**Files:** `GuideCommandFacade.java`, both guide command classes, command facade tests.

1. Put ask/cancel/retry/clear/status/skills/sources/session/model behavior and user-facing notices in common code.
2. Fabric and NeoForge retain only Brigadier syntax plus loader-specific message/open-screen sinks.
3. Bare `/guide` calls an injected screen opener; until Phase 3C supplies the screen, the opener reports `gui_unavailable` without submitting or mutating a request.
4. Command listeners observe GuideService snapshots/events and remain useful with the screen closed.

## Task 7: Deterministic product E2E fixtures

**Files:** common E2E fixture/model/transport tests, loader command-tree tests, canonical report writer.

1. Replay real `GameGuideAgent`, real tools, real GuideService, deterministic HTTP/model fixture semantics, and both local/server event paths.
2. Cover client-local, client-model plus remote tools, server-model plus server tools, busy/concurrency, cancel/rate wait, disconnect, malformed event, missing config/capability, knowledge refresh, and source extraction.
3. Emit canonical redacted reports with versions/topology/transitions/tool IDs/evidence/outcome/timings/hashes.
4. Add loader tests proving `/guide` subcommands map to the common facade. Do not claim a graphical-client run from unit coverage.

## Task 8: Opt-in real-client controller and automation

**Files:** development-only controller, launch scripts/config templates, CI workflow/docs.

1. Add an inert-by-default controller enabled only by explicit system properties; it uses the same GuideService and command path.
2. Add retry-aware local dedicated-server/client launch orchestration and deterministic local model fixture.
3. Never expose shell/filesystem execution to the in-game Agent. Reports are written by the development harness only and redact configured secrets.
4. Because the designer requested no GUI launch in this unattended run, build and test the controller but do not start a graphical Minecraft client. Record that real-client execution remains unclaimed until explicitly run.

## Task 9: Phase 3B verification and evidence

1. Run all common tests and both loader builds.
2. Inspect production JARs for GuideService, state DTOs, decoder, cancel protocol, and controller gating.
3. Scan tracked files, reports, resources, JARs, and Git objects for credentials.
4. Persist exact tests/artifacts/commit IDs and the no-graphical-run limitation in repository docs, SKMB if semantics changed, and `/Users/nkanf/docs/neolongsur/PRODUCT_DESIGN.md`.
5. Mark only Phase 3B complete; Phase 3C GUI remains incomplete.

## Execution record

- Tasks 1–7 implemented through commit `f3afd54`; deterministic product E2E
  drives the real Agent, GuideService, grounded recipe tools and report codec.
- Task 8 controller and loader hooks are default-off and are covered by common
  tests plus both loader compilers. The loopback HTTP/SSE fixture was exercised
  directly. Per designer instruction, no graphical client was launched, so a
  real-client report is not claimed by this execution.
- Task 9 closeout: 119 common tests, 0 failures, 0 errors, 1 opt-in live test
  skipped; both production loader builds passed; required classes were found in
  both JARs; tracked/JAR credential scans returned zero matches. No graphical
  client report is claimed.
