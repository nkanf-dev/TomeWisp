# Phase 3B GuideService and Real-Game E2E Design

## 1. Problem

Phase 2 has a usable Agent core, but product orchestration is divided between
`ClientGuideRuntime` and two duplicated loader command classes. Model mode,
server routing, knowledge reload, context capture, command output, and server
event decoding are not one reusable state source. A GUI built directly on this
shape would create a third orchestration implementation.

Phase 3B creates one common GuideService and proves it in actual Fabric and
NeoForge clients before the GUI depends on it.

## 2. Public service model

`GuideService` is the only client product API used by commands and screens. It
owns:

- selected session and model mode;
- request submission, cancellation, and explicit retry;
- local versus server-model topology selection;
- context and knowledge refresh on the client thread;
- normalized request-correlated events;
- immutable transcript, tool activity, source, and status snapshots;
- disconnect and shutdown cleanup.

Conceptual operations are:

```text
snapshot()
subscribe(listener) -> closeable subscription
ask(question) -> requestId or structured failure
cancel(sessionId)
retry(requestId) -> new requestId or structured failure
create/select/close/clear session
setModelMode(CLIENT | SERVER)
refreshCapabilities()
openSource(sourceRef)
```

Every mutating operation is marshalled to the Minecraft client thread. The
service publishes immutable `GuideSnapshot` objects after each transition.
Listeners may subscribe or unsubscribe without owning request lifetime.

## 3. State model

Each request records request ID, actor, session, topology, user message,
assistant text accumulated from text deltas, Agent state, tool activities,
sources, usage, timestamps, and structured terminal failure.

Tool activities have `WAITING`, `RUNNING`, `SUCCEEDED`, or `FAILED` status and
retain redacted arguments, normalized result summaries, and evidence. Full tool
results remain available to trace/source detail but are not rendered inline by
default.

The transcript is session-scoped and in memory for Phase 3. Closing the screen
does not affect it. Disconnect cancels all work and clears connection-scoped
sessions. A world or server identity is never reused implicitly for a different
connection.

## 4. Topology normalization

Three required topologies produce the same GuideEvent vocabulary:

1. **Client model + local tools.** Works against a server without TomeWisp.
2. **Client model + local and authenticated server tools.** The model credential
   remains client-only.
3. **Server model + server tools.** The client receives normalized state,
   progress, tool, source, final, and failure events; credentials stay server
   side.

The server protocol is versioned forward from the current bridge. Events carry
request and session identity. Server event JSON is decoded once in common code,
not separately in each loader command. Unknown event types or versions fail the
affected request explicitly.

Model mode is selected per client actor. Switching mode affects future requests
only. No automatic fallback changes topology after failure.

## 5. Command behavior

Both loaders register thin adapters for the same command operations:

```text
/guide                         open GUI
/guide <question>              submit through GuideService
/guide ask <question>          submit through GuideService
/guide cancel                  cancel selected session request
/guide retry                   retry selected session's last failed request
/guide clear
/guide status
/guide skills
/guide sources
/guide session list|new|switch|close
/guide model client|server
```

Command output observes GuideService events. It remains useful when the GUI is
closed and is the automation surface for E2E. Commands never instantiate their
own Agent, context capture, model-mode map, or server-event parser.

## 6. Lifecycle and failure decisions

- Same-session concurrent submit returns `agent_busy`; other sessions continue.
- Closing the GUI or opening another screen does not cancel.
- Explicit cancel suppresses late model, tool, network, and UI events.
- Disconnect cancels all local and server requests for the actor, unregisters
  remote correlations, clears connection-scoped sessions, and resets server
  capabilities/model mode to client.
- Client shutdown performs the same cancellation before executors close.
- A server capability disappearing makes future server operations unavailable;
  an active remote request fails explicitly.
- Retry is allowed only for a terminal failed/cancelled request with a retained
  user message. It receives a new request ID in the same session.
- 429 queue state is visible and cancellable; it never captures fresh live game
  objects off-thread.

## 7. Real-game E2E harness

Unit and mock HTTP tests remain necessary but cannot prove Minecraft wiring.
Phase 3B adds a development-only client E2E controller enabled by explicit
system properties. Production behavior is inert when disabled.

The harness uses real loader entrypoints, a real Minecraft client connected to
a local dedicated test server, the real GuideService, real context capture,
real packet registration, and a deterministic local HTTP model fixture. It
submits through the same command/service path and writes one canonical redacted
JSON report before requesting clean client shutdown.

The local server and client are launched by scripts with retry-aware Gradle
wrappers. The client uses quick-connect arguments where supported. No shell or
filesystem capability is exposed to the in-game Agent; the harness itself is a
development test facility.

Required automated scenarios per loader:

- client-only capability mode with local model and local tools;
- client model plus server `search/get/craftability` tools;
- server model plus server tools;
- same-session busy and different-session concurrency;
- explicit cancel during model wait and rate-limit wait;
- disconnect during remote tool/model activity;
- malformed/unknown packet event rejection;
- missing model configuration and missing server capability;
- knowledge source refresh and source-reference opening.

A separate opt-in real-provider scenario runs the final multi-tool contract.
Provider credentials enter only silent environment/stdin paths and are scanned
out of artifacts.

## 8. Evidence and CI

E2E reports contain loader, game/mod version, topology, scenario, request and
session IDs, observed transitions, tool IDs, evidence metadata, final outcome,
timings, and hashes of large payloads. They contain no API keys or private model
headers.

CI runs deterministic client E2E where the runner supports a display. If the
public CI environment cannot launch a graphical client reliably, common and
loader integration tests remain mandatory in CI and signed local real-client
reports are retained as release evidence; this limitation must be explicit and
must not be described as automated CI coverage.

