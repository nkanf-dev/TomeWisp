# Phase 4 Observability and Observable Game-State Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Finish the final Phase 4 manual-correction set by making Agent work observable and bounded, stabilizing the native UI and Tool workflows, and adding one evidence-bearing read-only Tool for all directly player-observable game state.

**Architecture:** GuideService continues to own request state; shared HTTP mechanics enforce full-response deadlines and publish redacted lifecycle progress. The native screen consumes an immutable fixed-height progress view and coalesced transcript updates. One common `inspect_game_state` Tool dispatches strict section queries over a client-thread-captured immutable snapshot, while Recipes/Guides and future spatial world inspection remain separate.

**Tech Stack:** Java 25, Minecraft 26.2, JDK `HttpClient`, Gson strict codecs, CommonMark semantic projection, SQLite history, native Minecraft Screen widgets, JUnit 5, Fabric and NeoForge.

---

### Task 1: Record the accepted state and establish progress contracts

**Files:**
- Create: `common/src/main/java/dev/tomewisp/guide/GuideRequestPhase.java`
- Create: `common/src/main/java/dev/tomewisp/guide/GuideRequestProgress.java`
- Modify: `common/src/main/java/dev/tomewisp/guide/GuideRequestSnapshot.java`
- Modify: `common/src/main/java/dev/tomewisp/agent/AgentEvent.java`
- Modify: `common/src/main/java/dev/tomewisp/guide/GuideStateReducer.java`
- Modify: strict Agent event/history/bridge codecs and adjacent tests
- Test: `common/src/test/java/dev/tomewisp/guide/GuideStateReducerTest.java`
- Test: `common/src/test/java/dev/tomewisp/agent/AgentEventTest.java`

- [ ] **Step 1: Write failing progress transition and codec tests**

Assert start creates `PREPARING` with identical request/phase/progress times;
context, model-attempt, response-start, stream-delta, Tool, retry and terminal
events update the closed phase and monotonic `lastProgressAt`; strict codecs
round-trip only phase/timestamps/attempt/deadline and reject unknown phases.

- [ ] **Step 2: Run the focused tests and confirm the contract is absent**

Run:

```bash
./gradlew :common:test --tests 'dev.tomewisp.guide.GuideStateReducerTest' --tests 'dev.tomewisp.agent.AgentEventTest' --tests 'dev.tomewisp.bridge.protocol.ServerAgentEventCodecTest'
```

Expected: FAIL because request progress/lifecycle event types do not exist.

- [ ] **Step 3: Implement immutable redacted progress state**

Use a closed record equivalent to:

```java
public record GuideRequestProgress(
        GuideRequestPhase phase,
        Instant requestStartedAt,
        Instant phaseStartedAt,
        Instant lastProgressAt,
        int attempt,
        Instant retryAt,
        Instant deadlineAt) {}
```

The constructor validates nonnegative attempts, phase/progress ordering and
deadline ordering. Add lifecycle-only Agent events; do not put prompts,
provider bodies, endpoints, arguments or exception text in them. Preserve
strict version/unknown-field behavior in every persisted/wire codec.

- [ ] **Step 4: Run the focused tests**

Run the command from Step 2. Expected: PASS.

- [ ] **Step 5: Commit the contract**

```bash
git add docs/isme docs/superpowers/specs docs/superpowers/plans common/src/main/java/dev/tomewisp/guide common/src/main/java/dev/tomewisp/agent common/src/main/java/dev/tomewisp/bridge common/src/test/java/dev/tomewisp/guide common/src/test/java/dev/tomewisp/agent common/src/test/java/dev/tomewisp/bridge
git commit -m "feat: define observable phase 4 request progress"
```

### Task 2: Enforce complete-response timeout and publish model progress

**Files:**
- Modify: `common/src/main/java/dev/tomewisp/net/JdkHttpTransport.java`
- Modify: `common/src/main/java/dev/tomewisp/model/http/HttpModelTransport.java`
- Modify: `common/src/main/java/dev/tomewisp/model/scheduling/ModelRequestScheduler.java`
- Modify: relevant model clients/coordinator event adapters
- Test: `common/src/test/java/dev/tomewisp/net/JdkHttpTransportTest.java`
- Test: `common/src/test/java/dev/tomewisp/model/scheduling/ModelRequestSchedulerTest.java`
- Test: adjacent OpenAI/Anthropic streaming tests

- [ ] **Step 1: Add failing stalled-body and lifecycle tests**

Serve headers immediately from a loopback HTTP server, then leave the body
open. Assert the returned future fails near the request deadline with
`model_timeout`, the body closes, cancellation wins when earlier, one terminal
event is emitted, and late bytes cannot reach the Agent. Also cover response
start, streaming progress and 429 attempt/retry publication.

- [ ] **Step 2: Run the focused transport tests**

```bash
./gradlew :common:test --tests 'dev.tomewisp.net.JdkHttpTransportTest' --tests 'dev.tomewisp.model.scheduling.ModelRequestSchedulerTest' --tests 'dev.tomewisp.model.openai.*' --tests 'dev.tomewisp.model.anthropic.*'
```

Expected: FAIL because `HttpRequest.timeout` does not terminate the open body.

- [ ] **Step 3: Add one cancellable total watchdog**

Schedule against the `HttpExchangeRequest.timeout()` deadline. Whichever of
decoder completion, explicit cancellation or watchdog wins must atomically
close the `InputStream`, cancel the HTTP future and cancel the watchdog. Map
the watchdog to `HttpTimeoutException`/`model_timeout`; preserve interruption,
429 scheduling and redacted adapter failures.

- [ ] **Step 4: Emit lifecycle progress from actual boundaries**

Publish attempt/deadline before dispatch, response-start after headers,
stream-progress only when validated events arrive, retry-at on rate limit, and
terminal state exactly once. Do not add a timer that writes history.

- [ ] **Step 5: Run focused tests and commit**

Run the Step 2 command. Expected: PASS.

```bash
git add common/src/main/java/dev/tomewisp/net common/src/main/java/dev/tomewisp/model common/src/main/java/dev/tomewisp/agent common/src/test/java/dev/tomewisp/net common/src/test/java/dev/tomewisp/model
git commit -m "fix: bound and expose complete model requests"
```

### Task 3: Stabilize the native screen and expose friendly progress

**Files:**
- Create: `common/src/main/java/dev/tomewisp/guide/ui/GuideUiProgress.java`
- Modify: `common/src/main/java/dev/tomewisp/guide/ui/GuideUiView.java`
- Modify: `common/src/main/java/dev/tomewisp/guide/ui/GuideUiLayout.java`
- Modify: `common/src/main/java/dev/tomewisp/client/gui/TomeWispScreen.java`
- Modify: `common/src/main/resources/assets/tomewisp/lang/en_us.json`
- Modify: `common/src/main/resources/assets/tomewisp/lang/zh_cn.json`
- Test: `common/src/test/java/dev/tomewisp/guide/ui/GuideUiViewTest.java`
- Test: `common/src/test/java/dev/tomewisp/guide/ui/GuideUiLayoutTest.java`
- Test: `common/src/test/java/dev/tomewisp/client/gui/TomeWispScreenProjectionTest.java`

- [ ] **Step 1: Add failing projection, coalescing and keyboard tests**

Assert every active phase produces a localized progress model with elapsed,
last-progress, attempt and optional retry/deadline; the layout reserves a fixed
strip; multiple subscription callbacks before one tick apply only the newest
view; render never changes scroll; Enter submits, Shift+Enter inserts a
newline, Ctrl+Enter submits, and content-focused Enter activates the content.

- [ ] **Step 2: Run focused UI tests**

```bash
./gradlew :common:test --tests 'dev.tomewisp.guide.ui.GuideUiViewTest' --tests 'dev.tomewisp.guide.ui.GuideUiLayoutTest' --tests 'dev.tomewisp.client.gui.TomeWispScreenProjectionTest'
```

Expected: FAIL under the existing transcript-row/no-progress and Ctrl+Enter
behavior.

- [ ] **Step 3: Implement fixed progress and tick-coalesced view application**

The subscription stores `pendingView`; `tick()` applies the newest pending
view once, captures/restores one anchor, and updates controls. `render()` reads
state only. The fixed strip formats the clock from current time without
publishing an event. Session replacement clears stale details and caches.

- [ ] **Step 4: Implement input/localization behavior**

Intercept confirmation only when the composer owns focus. Shift delegates to
the multiline widget, plain Enter and Ctrl+Enter call `submit`, and content
focus remains higher priority outside the composer. Update placeholders and
both language files.

- [ ] **Step 5: Run focused tests and commit**

Run Step 2. Expected: PASS.

```bash
git add common/src/main/java/dev/tomewisp/guide/ui common/src/main/java/dev/tomewisp/client/gui common/src/main/resources/assets/tomewisp/lang common/src/test/java/dev/tomewisp/guide/ui common/src/test/java/dev/tomewisp/client/gui
git commit -m "fix: stabilize guide interaction and show request progress"
```

### Task 4: Fix streaming-tail and list geometry

**Files:**
- Modify: `common/src/main/java/dev/tomewisp/guide/semantic/SemanticStreamingState.java`
- Modify: `common/src/main/java/dev/tomewisp/guide/ui/SemanticLayoutEngine.java`
- Modify: `common/src/main/java/dev/tomewisp/guide/ui/SemanticLayout.java`
- Modify: `common/src/main/java/dev/tomewisp/client/gui/MinecraftSemanticRenderer.java`
- Test: `common/src/test/java/dev/tomewisp/guide/semantic/SemanticStreamingStateTest.java`
- Test: `common/src/test/java/dev/tomewisp/guide/ui/SemanticLayoutTest.java`
- Test: `common/src/test/java/dev/tomewisp/client/gui/MinecraftSemanticRendererTest.java`

- [ ] **Step 1: Add character-stream and list geometry tests**

Feed Markdown one character at a time and assert the incomplete tail stays a
literal block until a validated boundary. Assert a flat/nested/multiline list
has marker plus first text on the same baseline, measured hanging indents, one
marker per item, monotonic stable height during streaming, and readable
terminal semantic layout.

- [ ] **Step 2: Run focused semantic tests**

```bash
./gradlew :common:test --tests 'dev.tomewisp.guide.semantic.SemanticStreamingStateTest' --tests 'dev.tomewisp.guide.ui.SemanticLayoutTest' --tests 'dev.tomewisp.client.gui.MinecraftSemanticRendererTest'
```

Expected: FAIL because markers are separate lines and incomplete nodes are
reclassified on every delta.

- [ ] **Step 3: Implement stable literal tail and hanging lists**

Cache validated complete blocks and represent only the mutable tail as literal
text. In `SemanticLayoutEngine`, measure `marker + space`, place the first item
line after it on the same baseline and wrap subsequent lines at the content
indent. Compose nested indentation and keep later paragraphs marker-free.

- [ ] **Step 4: Run focused tests and commit**

Run Step 2. Expected: PASS.

```bash
git add common/src/main/java/dev/tomewisp/guide/semantic common/src/main/java/dev/tomewisp/guide/ui common/src/main/java/dev/tomewisp/client/gui/MinecraftSemanticRenderer.java common/src/test/java/dev/tomewisp/guide/semantic common/src/test/java/dev/tomewisp/guide/ui common/src/test/java/dev/tomewisp/client/gui/MinecraftSemanticRendererTest.java
git commit -m "fix: keep streamed markdown layout stable"
```

### Task 5: Align Tool schemas, natural resource resolution and prompt recovery

**Files:**
- Create: `common/src/main/java/dev/tomewisp/agent/AgentSystemPrompt.java`
- Add narrow schema-description annotations/types under `common/src/main/java/dev/tomewisp/agent/tool/`
- Modify: `common/src/main/java/dev/tomewisp/agent/tool/ToolSchemaGenerator.java`
- Modify: `common/src/main/java/dev/tomewisp/tool/builtin/ResolveResourceTool.java`
- Modify: `common/src/main/java/dev/tomewisp/tool/builtin/SearchRecipesTool.java`
- Modify: `common/src/main/java/dev/tomewisp/client/ClientGuideRuntime.java`
- Modify: server model runtime prompt owner
- Modify: bundled recipe Skills under `common/src/main/resources/assets/tomewisp/tomewisp_skills/`
- Test: `common/src/test/java/dev/tomewisp/agent/tool/ToolSchemaGeneratorTest.java`
- Test: adjacent built-in Tool tests and Agent E2E tests

- [ ] **Step 1: Add failing schema and natural-language workflow tests**

Assert `resolve_resource` accepts localized/display/path/full-ID text and
returns deterministic exact matches; `search_recipes` rejects `{}` and
non-namespaced ID fields in both schema and runtime; client/server prompts are
identical; an apple-wine-style natural question resolves then searches once;
unknown/partial results terminate after at most one corrected call.

- [ ] **Step 2: Run focused Tool/Agent tests**

```bash
./gradlew :common:test --tests 'dev.tomewisp.agent.tool.ToolSchemaGeneratorTest' --tests 'dev.tomewisp.tool.builtin.*' --tests 'dev.tomewisp.agent.GameGuideAgentTest' --tests 'dev.tomewisp.e2e.*'
```

Expected: FAIL because the resolver is exact-ID-only and generated schemas
contradict runtime validation.

- [ ] **Step 3: Implement closed schema metadata and true resolution**

Generate descriptions, enum values, resource-ID patterns and explicit
at-least-one groups only from trusted Java definitions. Match resources by
exact ID, exact localized/path token and deterministic fuzzy token order;
return all stable exact IDs with evidence and no arbitrary selection/cap.

- [ ] **Step 4: Share the universal prompt and update Skills**

Use one prompt builder for client/server. Keep domain details in Skills and add
the resolve-before-exact-ID, handle preservation, one-correction, partial
evidence and stop rules. Do not grant new authority in prompt text.

- [ ] **Step 5: Run focused tests and commit**

Run Step 2. Expected: PASS.

```bash
git add common/src/main/java/dev/tomewisp/agent common/src/main/java/dev/tomewisp/tool common/src/main/java/dev/tomewisp/client common/src/main/java/dev/tomewisp/server common/src/main/resources/assets/tomewisp/tomewisp_skills common/src/test/java/dev/tomewisp/agent common/src/test/java/dev/tomewisp/tool common/src/test/java/dev/tomewisp/e2e
git commit -m "fix: align agent guidance with grounded tool contracts"
```

### Task 6: Persist and render richer friendly Tool cards

**Files:**
- Create: a closed Tool invocation presenter under `common/src/main/java/dev/tomewisp/guide/`
- Modify: `common/src/main/java/dev/tomewisp/agent/AgentEvent.java`
- Modify: `common/src/main/java/dev/tomewisp/agent/GameGuideAgent.java`
- Modify: `common/src/main/java/dev/tomewisp/guide/GuideToolActivity.java`
- Modify: `common/src/main/java/dev/tomewisp/guide/GuideToolPresentation.java`
- Modify: `common/src/main/java/dev/tomewisp/guide/ui/GuideToolDetailPresenter.java`
- Modify: history and bridge codecs for Tool activity
- Modify: `common/src/main/java/dev/tomewisp/client/gui/TomeWispScreen.java`
- Test: Agent event/reducer/history/UI card tests

- [ ] **Step 1: Add failing safe-projection persistence tests**

Assert a Tool start stores only allowlisted friendly query text; secrets and
unknown arguments never project; success/failure cards contain action, object,
state and up to three result lines; repeated invocations correlate by ID; a
history restart produces the same card.

- [ ] **Step 2: Run focused chronology/card/history tests**

```bash
./gradlew :common:test --tests 'dev.tomewisp.agent.AgentEventTest' --tests 'dev.tomewisp.guide.GuideStateReducerTest' --tests 'dev.tomewisp.guide.ui.GuideToolDetailPresenterTest' --tests 'dev.tomewisp.guide.history.*'
```

Expected: FAIL because ToolStarted has no safe input projection and collapsed
rows ignore presentation lines.

- [ ] **Step 3: Implement closed invocation/result projectors**

Project each registered Tool from parsed typed inputs, never raw JSON. Persist
the friendly action/summary lines in the current pre-release history schema and
strict bridge payload. Render two or three lines below the card header with
dynamic measured height; retain normalized JSON only for Debug Mode details.

- [ ] **Step 4: Run focused tests and commit**

Run Step 2. Expected: PASS.

```bash
git add common/src/main/java/dev/tomewisp/agent common/src/main/java/dev/tomewisp/guide common/src/main/java/dev/tomewisp/client/gui/TomeWispScreen.java common/src/main/java/dev/tomewisp/bridge common/src/test/java/dev/tomewisp/agent common/src/test/java/dev/tomewisp/guide common/src/test/java/dev/tomewisp/bridge
git commit -m "feat: make tool activity understandable and durable"
```

### Task 7: Add the unified observable game-state snapshot and Tool

**Files:**
- Create: focused immutable records under `common/src/main/java/dev/tomewisp/context/game/`
- Create: section registry/contracts under `common/src/main/java/dev/tomewisp/tool/gamestate/`
- Create: `common/src/main/java/dev/tomewisp/tool/builtin/InspectGameStateTool.java`
- Modify: `common/src/main/java/dev/tomewisp/context/ContextCapability.java`
- Modify: `common/src/main/java/dev/tomewisp/context/ToolInvocationContext.java`
- Modify: `common/src/main/java/dev/tomewisp/client/context/ClientContextCapture.java`
- Modify: Tool registration and family settings
- Test: `common/src/test/java/dev/tomewisp/context/ToolInvocationContextTest.java`
- Create: strict snapshot/section/Tool tests under corresponding test packages

- [ ] **Step 1: Add failing section, evidence and security tests**

Assert one Tool ID advertises a strict section enum; summary/list/detail queries
return typed evidence; unknown fields/sections, raw command strings, write
verbs, arbitrary paths/classes, spatial scans and external-container queries
are unrepresentable or rejected. Assert a missing section is partial without
disabling other sections and all incoming collections are defensively copied.

- [ ] **Step 2: Run focused context/Tool tests**

```bash
./gradlew :common:test --tests 'dev.tomewisp.context.*' --tests 'dev.tomewisp.tool.gamestate.*' --tests 'dev.tomewisp.tool.builtin.InspectGameStateToolTest'
```

Expected: FAIL because the observable-state capability and Tool do not exist.

- [ ] **Step 3: Implement typed immutable section registry**

Define one evidence-bearing `ObservableGameStateSnapshot` with registered
section records. The Tool input is `section` plus optional strict `query`; the
selected handler owns parsing and output. Provide overview, mods, options,
packs, shaders, diagnostics, player and closed `world_query` operations. Do
not expose one full-dump operation or add arbitrary limits.

- [ ] **Step 4: Capture on the owning thread and register one Tool**

Capture through `ClientContextCapture`, detach immediately, store the optional
snapshot in `ToolInvocationContext`, and make the async Tool read records only.
Replace/narrow old platform/player helper exposure as appropriate so the Agent
does not receive duplicate outer-state Tool IDs.

- [ ] **Step 5: Run focused tests and commit**

Run Step 2. Expected: PASS.

```bash
git add common/src/main/java/dev/tomewisp/context common/src/main/java/dev/tomewisp/tool common/src/main/java/dev/tomewisp/client/context common/src/test/java/dev/tomewisp/context common/src/test/java/dev/tomewisp/tool
git commit -m "feat: add unified player-observable game state"
```

### Task 8: Populate comprehensive Minecraft and loader state, then add the Skill

**Files:**
- Modify: `common/src/main/java/dev/tomewisp/platform/PlatformService.java`
- Modify: `fabric/src/main/java/dev/tomewisp/fabric/FabricPlatformService.java`
- Modify: `neoforge/src/main/java/dev/tomewisp/neoforge/NeoForgePlatformService.java`
- Create/modify common client capturers for Minecraft options, packs, shader integration, F3 diagnostics, player and closed read-only world queries
- Create: `common/src/main/resources/assets/tomewisp/tomewisp_skills/inspect-game-state/SKILL.md`
- Add any read-only Skill references required by the workflow
- Test: common contract tests and both loader architecture/compatibility tests

- [ ] **Step 1: Add failing comprehensive representative tests**

Use fake snapshots/adapters to cover complete installed-mod list and exact
metadata; at least one value from every registered Minecraft option group;
selected/available resource packs; shader selected/options or explicit
unavailable; F3 coordinates/direction/dimension/performance/target categories;
player UI state; authorized time/weather/difficulty/world-border query results;
and explicit partial authority. Test that external containers and surrounding
world scans have no operation.

- [ ] **Step 2: Run focused platform/context/Skill tests**

```bash
./gradlew :common:test --tests 'dev.tomewisp.platform.*' --tests 'dev.tomewisp.client.context.*' --tests 'dev.tomewisp.skill.*' --tests 'dev.tomewisp.integration.*'
```

Expected: FAIL until loader-neutral metadata and all registered section
capturers are populated.

- [ ] **Step 3: Implement Fabric/NeoForge parity and optional isolation**

Use verified public loader/Minecraft APIs, convert every value to common
records on the client thread, and keep optional shader/mod adapters isolated.
Never retain live options, renderer, pack, connection, level, player or loader
objects. Mark unavailable/non-authoritative fields explicitly.

- [ ] **Step 4: Add the progressively disclosed Skill**

Teach section selection, list-to-detail queries, authority/completeness and the
three-layer boundary. Include installed-mod, settings, pack/shader, F3 and
read-only query examples. Route recipes/guides to their Skills and explicitly
decline spatial/container/write requests.

- [ ] **Step 5: Run focused tests and commit**

Run Step 2. Expected: PASS.

```bash
git add common/src/main/java/dev/tomewisp/platform common/src/main/java/dev/tomewisp/client/context common/src/main/resources/assets/tomewisp/tomewisp_skills fabric/src/main/java/dev/tomewisp/fabric neoforge/src/main/java/dev/tomewisp/neoforge common/src/test/java/dev/tomewisp/platform common/src/test/java/dev/tomewisp/client/context common/src/test/java/dev/tomewisp/skill common/src/test/java/dev/tomewisp/integration
git commit -m "feat: expose observable game state across both loaders"
```

### Task 9: Integrate, document, and run final Phase 4 acceptance

**Files:**
- Modify: `README.md`
- Modify: `docs/development.md`
- Modify: accepted Phase 4 status/plan documents and SKMB implementation evidence
- Modify/create: deterministic and real-client E2E fixtures/scripts/report schema as required
- Create: redacted final evidence under `docs/verification/phase-4-final-corrections/`

- [ ] **Step 1: Add deterministic acceptance conversations**

Cover Chinese and English installed-mod enumeration/detail, representative
all-settings, packs/shader, F3 coordinates, read-only world query, natural-name
recipe resolution, unknown/partial stop, slow stream, timeout, cancellation,
multiline/list rendering, concurrent sessions and history restart. Assert no
secret, URL credential, raw command, world scan, container content or
reasoning appears in retained output.

- [ ] **Step 2: Run focused suites, script checks and package verification**

```bash
./gradlew :common:test --tests 'dev.tomewisp.e2e.*' --tests 'dev.tomewisp.guide.*' --tests 'dev.tomewisp.tool.*'
bash -n scripts/*.sh
python3 -m py_compile scripts/*.py
./scripts/verify-phase4-package.sh
git diff --check
```

Expected: all commands PASS.

- [ ] **Step 3: Run the clean production gate**

```bash
./gradlew clean :common:test :fabric:build :neoforge:build
```

Expected: `BUILD SUCCESSFUL`; both production loader artifacts exist.

- [ ] **Step 4: Run automated real-client acceptance under `caffeinate`**

Run the repository's Fabric and NeoForge real-client controllers with the
compatible recipe-rich/viewer profiles. Use deterministic loopback model
fixtures for repeatability and a retained redacted report. Exercise progress,
streaming/list/input behavior, context queries and history restart. If a
graphical API cannot run while the screen is locked, retain the deterministic
client report and state that limitation honestly rather than claiming manual
visual evidence.

The fixture must render headings, paragraphs, nested ordered/unordered lists,
emphasis, inline/fenced code, a table, unsupported link/image/HTML fallbacks,
validated item/block/recipe/source references, recipe and item grids,
ingredient/craftability/status/source components, and one unknown dynamic
component fallback. Capture workstation PNGs for wide layout, narrow layout,
mid-stream layout, terminal layout, expanded Tool/source detail, controlled
dynamic UI, manual-scroll anchor, and normal/debug mode on both loaders. Compute
and record SHA-256 for every PNG and inspect each retained image for marker
alignment, wrapping, scissor bounds, stable vertical placement, readable
fallback text, card density, and absence of raw technical state.

- [ ] **Step 5: Audit credentials, diff and documentation**

```bash
git status --short --branch
git diff --check
git diff --stat main...HEAD
git grep -nE 'sk-[A-Za-z0-9_-]{16,}|Authorization: Bearer' -- ':!docs/verification/.DS_Store'
```

Expected: only intentional tracked changes, no credentials, no generated run
directories and the unrelated untracked `.DS_Store` remains untouched.

- [ ] **Step 6: Commit final evidence and merge to main**

```bash
git add README.md docs common/src/test scripts
git commit -m "docs: close phase 4 acceptance"
git switch main
git merge --no-ff codex/phase-4-productization -m "merge: complete TomeWisp phase 4"
```

Run the production gate once more on `main`. Expected: PASS. Do not push unless
the designer separately requested publication.
