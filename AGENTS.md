# AGENTS.md

This file is the repository-level operating contract for coding agents working
on OpenAllay. It applies to the entire repository unless a deeper `AGENTS.md`
explicitly narrows a rule for its subtree.

## 1. Product intent

OpenAllay is a lightweight, in-game knowledge companion for modded
Minecraft. It connects trusted game and mod knowledge to a small tool-using
Agent, gives players grounded answers through commands and a native screen, and
will later turn structures and documentation into guided visual tutorials.

The product is client-first:

- a pure client installation must remain useful without OpenAllay on the server;
- a server installation may add authoritative read tools or host a shared model;
- model location and tool location are independent choices;
- Fabric and NeoForge are equal, first-class loaders;
- the main line targets Minecraft 26.2 and Java 25;
- older versions, including 1.21.1, are future ports rather than reasons to
  weaken or duplicate the 26.2 architecture.

Phase 3 is complete: grounded tools, the shared GuideService, deterministic
Agent E2E coverage, and the native player GUI exist. Phase 4 is recipe-source
integration, durable history/context management, and Minecraft-native rich UI.
Structure parsing and runtime Ponder generation are deferred beyond Phase 4.
Do not quietly mix Phase 4 work into a Phase 3 maintenance change.

## 2. Read before changing code

Start with the smallest relevant set, in this order:

1. `README.md` for product status and supported operation modes.
2. `docs/development.md` for commands, runtime configuration, tools, and
   verification baselines.
3. `docs/isme/SKMB.md` for accepted state transitions, invariants, and failure
   semantics.
4. The relevant accepted decision under `docs/isme/decisions/`.
5. The relevant design under `docs/superpowers/specs/` and implementation plan
   under `docs/superpowers/plans/`.
6. Existing tests adjacent to the code being changed.

Do not treat old prose as stronger evidence than current code, tests, and an
accepted decision. If they disagree, identify the mismatch and update the
appropriate source of truth as part of the change.

## 3. Repository map

- `common/`: product semantics shared by both loaders. Agent loop, model
  transports, tool contracts, snapshots, GuideService, GUI, knowledge sources,
  bridge protocol, Skills, trace replay, and deterministic tests belong here.
- `fabric/`: Fabric entrypoints, lifecycle hooks, command/key registration,
  payload transport, and platform services. Keep this layer thin.
- `neoforge/`: NeoForge equivalents of the Fabric adapters. Keep behavior in
  parity with Fabric.
- `build-logic/`: shared Gradle conventions and multi-loader source/resource
  composition.
- `common/src/main/resources/assets/openallay/openallay_skills/`: bundled,
  non-executable Agent Skills.
- `common/src/main/resources/data/openallay/agent_traces/`: strict deterministic
  replay fixtures.
- `scripts/`: opt-in live-provider and real-client development harnesses. These
  are not runtime Agent tools.
- `docs/isme/`: accepted state-machine decisions and their index.
- `docs/superpowers/specs/`: approved designs.
- `docs/superpowers/plans/`: implementation plans and verification steps.
- `runs/`, `run/`, `.gradle/`, and all `build/` directories: ignored local
  runtime/cache/output state. Never use them as committed source of truth.

## 4. Non-negotiable architecture

### Shared core and loader boundaries

- Production code under `common/` must not import Fabric or NeoForge APIs.
- Put behavior in common code. Loader modules should translate platform events
  into common interfaces, not implement a second product path.
- A feature that touches registration, networking, lifecycle, commands, or key
  mappings must be assessed on both loaders. Implement both or document why one
  is genuinely inapplicable.
- Keep wire semantics in common protocol codecs. Loader bridges transport
  already-defined payloads and must not reinterpret state transitions.
- Preserve the architecture tests that enforce these boundaries.

### Agent and model runtime

- Keep the runtime inside the Minecraft JVM. Use Java/JDK facilities and narrow
  internal interfaces.
- Do not add LangChain-style frameworks, Node/Python sidecars, MCP bridges,
  shell tools, general sandboxes, or a second orchestration loop.
- The Agent is deliberately small: model turn, validated tool calls, complete
  tool results, continuation, and final answer.
- Anthropic Messages and OpenAI-compatible Chat Completions remain protocol
  adapters behind `ModelClient`; product logic must not depend on one provider.
- Skills provide progressively disclosed instructions and declared read-only
  references. They cannot execute scripts, fetch arbitrary URLs, register
  tools, grant permissions, or read arbitrary paths.

### Guide state and topology

- `GuideService` is the single connection-scoped source of request, session,
  topology, model-mode, cancellation, retry, source, and transcript state.
- Commands, GUI, and E2E probes consume the same immutable GuideService
  snapshots/events. They do not call the Agent or network bridge directly.
- At most one request is active per `(actorId, sessionId)`. Different sessions,
  including sessions owned by one player, may run concurrently.
- Provider HTTP 429 is an endpoint-scoped scheduling event, not a global busy
  rejection. Honor `Retry-After` or use cancellable backoff with fair session
  rotation.
- Closing a screen detaches its listener; it does not cancel the request.
- Explicit cancel, disconnect, and shutdown suppress late events and release
  session ownership.
- Disconnect clears connection-scoped conversations, capabilities, and source
  state. Never leak them into a later server connection.
- Model-mode changes affect future requests only. Never silently fall back
  between client and server models, because authority, credentials, cost, and
  behavior differ.

### Minecraft thread ownership

- Capture live Minecraft state only on its owning client/server thread.
- Detach it immediately into immutable records before asynchronous model or
  tool work.
- Never retain or use live player, level, registry, recipe-manager, UI, or
  resource-manager objects from a model HTTP thread or virtual worker thread.
- Route UI mutations and visible events back to the Minecraft client thread.

## 5. Grounding, authority, and tools

- Tools are read-only unless a future accepted decision explicitly authorizes a
  narrowly scoped write. There is no shell, arbitrary code execution, server
  command execution, world mutation, inventory mutation, or unrestricted
  reflection surface.
- Tool permissions live in code and server authorization checks, never in a
  prompt or Skill.
- Every factual success must expose immutable evidence containing authority,
  completeness, capture time, source, provenance, game version, and loader.
- The normalizer must fail closed when an `EvidenceBearing` success has no
  evidence. Never turn missing data into a successful empty fact.
- Distinguish `CLIENT_VISIBLE`, `SERVER_AUTHORITATIVE`, partial, complete, and
  unknown data. An unloaded/empty source does not prove that no documentation or
  recipe exists.
- Stable source-scoped IDs must round-trip from search to exact lookup. Stale
  references return `stale_reference`; do not silently repeat a broad search.
- Craftability is deterministic and non-recursive. Allocate overlapping
  alternatives globally, report observed allocation and missing requirements,
  and keep `conclusive=false` when recipe or inventory evidence is incomplete.
- Do not ask the model to perform arithmetic or allocation that deterministic
  Java code can perform.
- Model-facing retrieval uses the Resource VFS Tool family
  (`resource_list`/`read`/`glob`/`grep`/`query`). Keep
  `openallay:calculate_craftability` as the narrow deterministic allocation action.
  Legacy domain retrieval Tools are not registered or advertised.

## 6. Knowledge integrations

- Optional integrations must fail independently and leave the Agent usable.
- Patchouli content is read from active client resources without a hard binary
  dependency. Preserve locale precedence and visibility filtering.
- FTB Quests integration remains optional and allowlisted. Use public API
  method handles only; do not broaden it into private or arbitrary reflection.
- Do not add a hard dependency that has no compatible Minecraft 26.2 artifact.
- Missing integrations produce explicit diagnostics/capability state, not
  fabricated answers and not crashes during bootstrap.

## 7. State-machine decision discipline

Before implementing any new or changed behavior involving sessions,
concurrency, queues, cancellation, retries, persistence, connection lifetime,
permissions, external providers, failure recovery, or writes:

1. Read `docs/isme/SKMB.md` and the decision that owns the affected state.
2. Identify states, events, transitions, ownership, invariants, and terminal
   failure behavior.
3. Record a new decision in `docs/isme/decisions/` when existing accepted text
   does not determine the behavior.
4. Add the decision to the SKMB index and update named states/transitions/
   invariants/fail semantics as needed.
5. Add tests that demonstrate the selected path and important races/failures.

Do not hide a product decision inside a convenient implementation default. Do
not claim designer approval that is not recorded. Preserve traceability from
design to decision to tests to commits.

OpenAllay currently has no project-defined caps for document size, result size,
history length, queue length, recipe count, inventory count, trace steps, or
report length. Do not introduce arbitrary limits preemptively. Add a limit only
after observed operational evidence and an explicit decision define its
semantics.

## 8. Security and privacy

- Never commit API keys, access tokens, cookies, authorization headers, private
  endpoints containing credentials, or model configuration with an inline
  `apiKey`.
- Supply credentials through environment variables such as
  `OPENALLAY_API_KEY`. Configuration should name `apiKeyEnv`.
- Credentials must never enter packets, prompts, traces, logs, normalized tool
  results, GUI text, test fixtures, screenshots, or exception messages.
- Keep trace redaction tests current when adding protocols, headers, or config
  fields.
- Server tools authenticate the sending player, re-check authorization, and
  scope every result to that player. Never trust an actor identity supplied by
  model text or an uncorrelated packet.
- Remote model endpoints require HTTPS; plain HTTP is permitted only for
  loopback development fixtures.
- Inspect command output before sharing it: process listings and environment
  dumps may contain credentials belonging to unrelated applications.

## 9. Development workflow

### Before editing

- Run `git status --short --branch` and preserve unrelated user changes.
- Search with `rg`/`rg --files` before adding new abstractions.
- Read the implementation and its tests on both sides of the relevant boundary.
- Prefer extending an existing narrow interface over creating a parallel path.
- If requirements are ambiguous but a safe, reversible interpretation follows
  accepted decisions, proceed and record the assumption. Stop only when the
  choice changes product authority, security, persistent data, or scope.

### While editing

- Keep changes focused. Do not mix broad refactors into a feature/fix unless
  they are required for the accepted design.
- Use immutable records at asynchronous and protocol boundaries.
- Return structured, stable failure codes; do not make callers parse prose.
- Preserve unknown fields/version rejection rules in strict persisted and wire
  formats unless a versioned migration is designed.
- Add or update English and Simplified Chinese translations for player-facing
  text.
- Update Fabric and NeoForge adapters together when common behavior needs a
  platform hook.
- Do not modify generated files, downloaded assets, Gradle caches, or local run
  directories to implement production behavior.

### Git hygiene

- Never discard, reset, or rewrite user changes.
- Make intentional commits whose message describes one coherent outcome.
- Do not amend or force-push unless explicitly requested.
- Keep generated artifacts and credentials out of Git.
- When publishing, verify the exact diff, run the required gates, push the
  intended branch, and report CI truth rather than assuming it passed.

### Java implementation conventions

- Compile against the Java version declared in `gradle.properties` (currently
  Java 25). Do not introduce preview features without an explicit repository
  decision and build configuration.
- Prefer the JDK and existing libraries over new dependencies. A dependency
  must solve a repository-level need, work on both loaders, and justify its
  runtime size and compatibility cost.
- Use records and sealed types for immutable snapshots, events, and structured
  outcomes when they clarify the contract. Defensively copy incoming mutable
  collections and JSON before storing or publishing them.
- Keep constructors and public methods strict about nulls and malformed IDs.
  Validate once at the boundary instead of spreading defensive guesses through
  consumers.
- Never block the render thread, client game thread, or server tick thread on
  model HTTP, queue waits, or long parsing. Make asynchronous ownership visible
  in the API and marshal completion back to the correct game thread.
- Preserve interruption and cancellation semantics. Do not swallow
  `InterruptedException`, wrap cancellation as a generic transport failure, or
  allow a late completion to mutate a replacement request.
- Convert external/provider failures into the existing structured failure model
  at the adapter boundary. Do not leak raw response bodies or secrets.
- Reuse the repository's Gson configuration and strict codecs. Do not introduce
  a second JSON model for the same protocol merely for convenience.
- Comments should explain authority, threading, compatibility, or non-obvious
  decisions. Do not narrate straightforward Java syntax.

## 10. Build and verification

Use the checked-in wrapper. The primary local gate is:

```bash
./gradlew clean :common:test :fabric:build :neoforge:build
```

During iteration, run the smallest relevant tests first, for example:

```bash
./gradlew :common:test --tests 'dev.openallay.guide.*'
./gradlew :common:test --tests 'dev.openallay.tool.builtin.*'
```

If Maven networking is unreliable, use the retrying wrapper:

```bash
./gradlew-curl clean :common:test :fabric:build :neoforge:build
```

With FLClash, an explicit proxy is supported:

```bash
OPENALLAY_CURL_PROXY=socks5h://127.0.0.1:7890 ./gradlew-curl build
```

For every behavior change:

- add deterministic unit/contract coverage in `common/src/test`;
- cover both success and explicit failure/unknown paths;
- add race/cancellation/disconnect tests for stateful work;
- update protocol tests when payloads or event semantics change;
- build both production loader artifacts;
- verify loader parity rather than treating compilation as behavioral proof.

CI may validate code, scripts, fixture syntax, and builds. It must not claim a
graphical client run, manual gameplay acceptance, or live-provider acceptance
unless retained runtime evidence proves it.

## 11. Runtime and manual testing

Development entrypoints are:

```bash
./gradlew :fabric:runClient
./gradlew :fabric:runServer --args nogui
./gradlew :neoforge:runClient
./gradlew :neoforge:runServer
```

- Do not launch a graphical client during unattended work or ordinary build
  verification. Launch one only when the user explicitly requests manual or
  real-client testing.
- Dedicated server validation stays headless. Fabric accepts `--args nogui`;
  do not pass Gradle `--args` to the NeoForge development server because it
  replaces the launch main class.
- Do not enable the real-client E2E controller unless the run is explicitly
  intended to produce a retained report.
- `scripts/run-real-client-e2e.sh` opens a graphical client and is opt-in.
- `scripts/live-model-smoke.sh` makes real billable/provider requests and is
  opt-in. Normal tests must remain deterministic and offline.
- A development client uses its loader-specific ignored run directory. Keep
  client and server model JSON files there, with secrets supplied by environment.
- Press `K` or use bare `/guide` to open the player screen. `Ctrl+Enter` submits
  multiline input. Closing the screen must not be interpreted as cancellation.

When network instability interrupts asset or Maven downloads, retry and reuse
verified local caches. Do not disable TLS verification or commit downloaded
dependencies as a workaround.

## 12. UI expectations

- The native Minecraft screen is a projection of immutable `GuideUiView`; it
  does not own orchestration state.
- Reasoning content is diagnostic-only and must remain unrepresentable in the
  player UI view.
- Preserve responsive narrow/wide layouts, transcript virtualization, scissor
  boundaries, keyboard navigation, explicit model selection, cancellation,
  retry, sessions, and source/evidence details.
- Render the actual Agent chronology: assistant segment, tool invocation/result,
  later assistant continuation, further tools, and final segment. Never flatten
  a request into one answer followed by a trailing batch of tool cards.
- Tool entries keep stable invocation identities and update in place; repeated
  calls to the same tool are not correlated by tool name alone. Final text must
  not overwrite earlier visible assistant segments.
- Unknown tools use a deterministic normalized JSON fallback; recipe,
  inventory, and craftability tools retain concise first-class presentations.
- Session switches and disconnects clear stale selected details.
- Do not launch external browsers from source/evidence actions.

## 13. Phase 4 guardrails

- Phase 4 is one product phase covering all-known recipe discovery, optional
  JEI/REI/EMI adapters and navigation, durable partitioned sessions, context
  compaction, semantic rich messages, controlled dynamic UI components,
  settings, diagnostics, and long-history performance.
- Default recipe visibility is `ALL_KNOWN`. Vanilla recipe-book unlock state is
  evidence and an optional filter, not a default knowledge boundary.
- Optional recipe-viewer adapters must use verified public Minecraft 26.2 APIs,
  fail independently, preserve source provenance, and never become the sole
  recipe truth.
- `GuideService` remains the active connection-scoped state owner. Durable
  history is a versioned projection partitioned by player and world/server;
  capabilities and live snapshots never resume across connections.
- Process loss restores an unfinished request as interrupted and never
  automatically repeats a provider call. Normal history and developer traces
  have separate retention contracts.
- Context compaction preserves original history, system/tool message structure,
  and evidence boundaries. Summaries are never factual evidence.
- Rich UI components are registered, versioned, and bound only to validated
  references. Model output cannot add scripts, callbacks, commands, tools,
  permissions, arbitrary NBT/path access, URLs, or world mutation.
- Final smoke acceptance installs every actually compatible target viewer
  practical for the loader, Patchouli when compatible (otherwise an honestly
  labeled resource-based book), and Farmer's Delight or a documented compatible
  recipe-rich sample mod. Retain artifact versions, URLs, SHA-256 values, and a
  redacted report; do not claim unavailable integrations from compilation.
- Structure-to-Ponder, recursive technology production planning, old-version
  ports, and formal distribution remain deferred and require later approved
  designs.

## 14. Definition of done

A change is complete only when all applicable items are true:

- behavior is implemented in the correct common/loader layer;
- Fabric and NeoForge remain in parity;
- state, authority, permissions, and failure semantics are explicit;
- relevant deterministic tests pass;
- the full common suite and both loader builds pass for product changes;
- player-visible text is localized;
- accepted decisions, SKMB index, development guide, and status docs reflect
  the behavior that actually exists;
- no credential or unrelated user change appears in the diff;
- graphical/live-provider claims are backed by retained evidence;
- the final handoff states what was verified, what was not run, and any
  remaining risk without overstating completion.

## 15. Collaboration preference

- Use text-first discussion, specifications, plans, and status updates for this
  repository.
- Do not offer or require browser-based visual companions, visual writing,
  mockups, diagrams, or other visualization workflows as a prerequisite for
  planning or implementation.
- If a UI design needs clarification, describe the alternatives directly in
  text and continue without making visualization a blocker.
