# Phase 4F Multi-Model Profiles Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans
> and complete this plan task-by-task with red/green evidence and focused
> commits.

**Goal:** Let each TomeWisp session select a named client model profile or the
connected server model for its next request, switch providers/models without
losing common history, and resolve trusted OpenRouter model metadata without
making runtime startup depend on the network.

**Architecture:** A strict `models.json` describes credential-free named
profiles. A common client runtime registry atomically owns immutable profile
runtimes that share the provider-neutral `AgentSessionStore`. `GuideService`
owns one mutable selection per session and captures it into every request before
dispatch. Durable history schema v3 stores those selections. The native screen
projects enabled/available choices; provider metadata remains an explicit
settings/diagnostic operation.

**Authority:** SKMB-2026-07-18-009, SKMB-2026-07-18-011, and
`docs/superpowers/specs/2026-07-18-multi-model-profiles-design.md` are accepted
designer decisions. No silent fallback, automatic routing, or provider-side
cache portability is introduced.

**Tech stack:** Java 25 records/sealed types, Gson strict codecs, JDK
`HttpClient`, existing model adapters/scheduler/compactor, SQLite/JDBC, JUnit 5,
Fabric and NeoForge client adapters.

---

## Accepted boundaries

- A conversation is provider/model-neutral; a selection is only the runtime
  for the next request.
- Selection changes are per-session and are permitted while a request is
  active. The active request retains its captured runtime.
- Retry creates a new request using the session's current selection.
- A missing or invalid remembered profile stays visible and fails closed. It
  never selects another profile or the server model.
- New-format configuration cannot represent inline API-key values. Credentials
  are resolved only from named environment variables.
- Explicit token limits are authoritative. Metadata discovery never overrides
  them and is not part of runtime bootstrap.
- Generic OpenAI-compatible `/models` responses are not treated as context-
  window authority. The first trusted adapter is OpenRouter's native model
  catalog.
- The full profile editor/settings screen remains the next settings package;
  this package supplies the strict file format, diagnostics, model selector,
  and metadata service it will consume.

### Task 1: Strict named-profile configuration and legacy import

**Files:**
- Create: `common/src/main/java/dev/tomewisp/model/config/ModelProfileDefinition.java`
- Create: `common/src/main/java/dev/tomewisp/model/config/ModelProfilesConfig.java`
- Create: `common/src/main/java/dev/tomewisp/model/config/ModelProfilesConfigLoader.java`
- Create: `common/src/main/java/dev/tomewisp/model/config/ResolvedModelProfile.java`
- Test: `common/src/test/java/dev/tomewisp/model/config/ModelProfilesConfigLoaderTest.java`
- Modify: `common/src/test/java/dev/tomewisp/model/config/ModelConfigLoaderTest.java`

- [x] **Step 1: Write red strict-schema, order, duplicate, secret, and legacy tests**

Cover an ordered two-profile file, stable IDs/display names, disabled profiles,
missing explicit context windows, duplicate IDs, missing default, future schema,
unknown fields, inline `apiKey`, absent environment variables, and a legacy
`model.json` imported as synthetic profile `default` only when `models.json` is
absent.

- [x] **Step 2: Implement the credential-free profile document**

`models.json` schema 1 contains exactly `schemaVersion`, `defaultProfileId`, and
`profiles`. Each profile contains exactly its stable identity, display name,
enabled flag, protocol, base URL, upstream model ID, `apiKeyEnv`, optional
explicit context limit, output limit, and timeouts. Lists retain file order and
constructors copy all mutable values. New-format parsing rejects `apiKey` at the
schema boundary before environment resolution.

- [x] **Step 3: Resolve runtime profiles without network access**

Convert enabled definitions with an explicit context window and present named
environment secret into existing `ModelConfig`. Retain disabled/unresolved
definitions with structured redacted diagnostics so the UI can show and repair
them. A malformed whole document is `invalid_model_config`; an absent file with
no legacy file is `model_not_configured`.

- [x] **Step 4: Run focused tests and commit**

```bash
./gradlew :common:test --tests 'dev.tomewisp.model.config.*'
git commit -m "feat: load named model profiles"
```

The red run failed only on the intentionally absent profile types. The complete
model-config suite now passes. Schema-1 parsing preserves file order, retains
disabled/unresolved profiles with distinct redacted failures, rejects inline
keys and every unknown field, and resolves only explicit context limits plus a
present named environment secret. Legacy `model.json` imports as `default` only
when `models.json` is absent; missing both formats is `model_not_configured`.

### Task 2: Atomic client runtime registry with shared sessions

**Files:**
- Create: `common/src/main/java/dev/tomewisp/client/ClientModelRuntimeRegistry.java`
- Create: `common/src/main/java/dev/tomewisp/guide/GuideClientModelProfile.java`
- Modify: `common/src/main/java/dev/tomewisp/client/ClientGuideRuntime.java`
- Modify: `common/src/main/java/dev/tomewisp/guide/GuideLocalEndpoint.java`
- Test: `common/src/test/java/dev/tomewisp/client/ClientModelRuntimeRegistryTest.java`

- [x] **Step 1: Write red routing, shared-history, missing-profile, and replacement-race tests**

Use deterministic fake clients `model-a` and `model-b`. Prove calls route by
captured profile, the second model receives the first model's completed common
history, removing a profile fails future dispatch, and an in-flight call held by
an old immutable registry snapshot can finish after atomic reload.

- [x] **Step 2: Refactor runtime construction around one shared session store**

Each profile gets its own immutable `ModelClient`, endpoint scheduler, context
budget/compactor, model provenance, and redacted trace secret set. All profile
agents share one `AgentSessionStore` and common tool executor semantics.

- [x] **Step 3: Extend the local endpoint contract by profile ID**

Expose ordered profile summaries, default profile ID, profile availability,
profile-specific required context, and `ask(profileId, ...)`. Keep narrow
compatibility defaults for deterministic single-endpoint tests while production
loaders use the registry. Never select another runtime inside `ask`.

- [x] **Step 4: Run focused client/agent tests and commit**

```bash
./gradlew :common:test --tests 'dev.tomewisp.client.*' \
  --tests 'dev.tomewisp.agent.session.*'
git commit -m "feat: route client requests by model profile"
```

The red run failed only on the absent registry/profile contract. Focused client
and agent-session suites now pass. Profile routing captures one immutable
registry state, all generated runtimes share one `AgentSessionStore`, a later
model receives earlier completed semantic history, missing/disabled profiles
fail with their structured code, and atomic replacement does not cancel an
in-flight call holding the previous runtime reference.

### Task 3: Per-session selection and active-request capture

**Files:**
- Create: `common/src/main/java/dev/tomewisp/guide/GuideModelSelection.java`
- Modify: `common/src/main/java/dev/tomewisp/guide/GuideSessionSnapshot.java`
- Modify: `common/src/main/java/dev/tomewisp/guide/GuideRequestSnapshot.java`
- Modify: `common/src/main/java/dev/tomewisp/guide/GuideSnapshot.java`
- Modify: `common/src/main/java/dev/tomewisp/guide/GuideService.java`
- Modify: `common/src/main/java/dev/tomewisp/guide/GuideStateReducer.java`
- Test: `common/src/test/java/dev/tomewisp/guide/GuideServiceModelSelectionTest.java`
- Modify: `common/src/test/java/dev/tomewisp/guide/GuideServiceTest.java`

- [x] **Step 1: Write red isolation/capture/switch/retry/failure tests**

Prove two sessions concurrently select different profiles; switching during an
active request changes the session's next preference but not the request's
captured selection; retry uses the current preference; switching provider/model
does not clear messages/checkpoints; missing local profile, invalid profile, and
unavailable server model return distinct failures without dispatch.

- [x] **Step 2: Introduce the closed selection value**

`GuideModelSelection` represents exactly `CLIENT(profileId)` or `SERVER`, uses
the stable session/profile ID grammar, derives the legacy `GuideModelMode`, and
has a strict JSON projection for persistence. Session and request snapshots
carry preferred and captured selections respectively; compatibility
constructors use `client("default")` only for old tests/callers.

- [x] **Step 3: Make session state authoritative**

New sessions inherit the registry default client profile. `setModelSelection`
updates only the selected session and persists immediately, even while active.
`setModelMode` remains a compatibility adapter: server selects `SERVER`; client
restores that session's remembered last client profile. `submit` validates and
captures the selection before creating the request and passes the captured
profile to the registry. Snapshot-level `modelMode` becomes a derived
compatibility projection.

- [x] **Step 4: Preserve cancellation, disconnect, and capability semantics**

Cancellation follows the request's captured topology. Server capability loss
fails only active server requests and leaves future preferences visible.
Disconnect clears connection-scoped sessions and creates a fresh main session
with the configured default; it never mutates a captured in-flight runtime.

- [x] **Step 5: Run state/race tests and commit**

```bash
./gradlew :common:test --tests 'dev.tomewisp.guide.GuideService*' \
  --tests 'dev.tomewisp.guide.GuideStateReducerTest'
git commit -m "feat: select models per guide session"
```

The red run failed on the absent closed selection/snapshot API. The focused
service/reducer suites and full common suite now pass. Each session owns a
preferred selection and remembered client profile; every request captures its
actual choice before dispatch. Switching while active preserves that request,
messages, and checkpoints; retry uses the current choice. Removed remembered
profiles remain selected and fail future submission as `model_not_configured`,
while invalid profiles keep their own structured failure. The legacy mode API
is now only a compatibility adapter over the selected session.

### Task 4: Clean durable schema v3 selection storage

**Files:**
- Modify: `common/src/main/java/dev/tomewisp/guide/history/GuideHistoryPartition.java`
- Modify: `common/src/main/java/dev/tomewisp/guide/history/GuideHistoryCodec.java`
- Modify: `common/src/main/java/dev/tomewisp/guide/history/SqliteGuideHistoryStore.java`
- Modify: `common/src/test/java/dev/tomewisp/guide/history/GuideHistoryRepositoryTest.java`
- Modify: `common/src/test/java/dev/tomewisp/guide/history/SqliteGuideHistoryStoreTest.java`
- Modify: `common/src/test/java/dev/tomewisp/guide/history/GuideHistoryCodecTest.java`

- [x] **Step 1: Write strict v3 round-trip and rejection fixtures**

Cover different selections for multiple sessions, captured request selection,
unknown-but-valid profile retention, strict unknown kind/field rejection, and
explicit rejection of unsupported pre-release schemas without mutation.

- [x] **Step 2: Store selection per session/request**

Schema v3 adds strict selection kind/profile data at session and request
boundaries. Remove global mode as an authority; a compatibility mode may be
derived for old callers. Replacement writes remain transactional and partition
isolation remains unchanged.

- [x] **Step 3: Remove pre-release migration and compatibility debt**

Keep one current writable schema. Do not retain legacy columns, constructors,
or migration branches for layouts that never shipped. Earlier and future
schema values fail closed without partial writes; developers explicitly delete
the ignored test database when a reset is required.

- [x] **Step 4: Run history tests and commit**

```bash
./gradlew :common:test --tests 'dev.tomewisp.guide.history.*'
git commit -m "feat: persist per-session model selection"
```

Implemented as one clean pre-release schema under SKMB-2026-07-18-011. The
global `model_mode` column and all migration-only branches are absent. Strict
credential-free JSON stores each session preference and every request's
captured selection; interrupted recovery preserves both. Earlier/future schema
versions fail `history_schema_unsupported` without mutation. Focused history,
GuideService history, and model-selection suites pass, followed by the complete
common suite: 245 tests, zero failures/errors, one opt-in skip.

### Task 5: Trusted OpenRouter metadata discovery and cache

**Files:**
- Create: `common/src/main/java/dev/tomewisp/model/metadata/ModelMetadata.java`
- Create: `common/src/main/java/dev/tomewisp/model/metadata/ModelMetadataResolver.java`
- Create: `common/src/main/java/dev/tomewisp/model/metadata/ModelMetadataResolution.java`
- Create: `common/src/main/java/dev/tomewisp/model/metadata/OpenRouterMetadataResolver.java`
- Create: `common/src/main/java/dev/tomewisp/model/metadata/ModelMetadataCache.java`
- Test: `common/src/test/java/dev/tomewisp/model/metadata/OpenRouterMetadataResolverTest.java`
- Test: `common/src/test/java/dev/tomewisp/model/metadata/ModelMetadataResolutionTest.java`
- Test: `common/src/test/java/dev/tomewisp/model/metadata/ModelMetadataCacheTest.java`

- [x] **Step 1: Verify the current official OpenRouter schema**

Use the official OpenRouter API/documentation only. Record the endpoint and
fields used (`id`, `canonical_slug`, `context_length`, and output limit only when explicitly
published). Do not infer context from pricing, architecture names, or generic
OpenAI `/models` data.

Verified against OpenRouter's official
[`GET /api/v1/models`](https://openrouter.ai/docs/api/api-reference/models/get-models)
and [models schema guide](https://openrouter.ai/docs/guides/overview/models) on
2026-07-18. TomeWisp uses exact `id`, `canonical_slug`, `context_length`, and
only the explicitly published `top_provider.max_completion_tokens`.

- [x] **Step 2: Write red parser/precedence/failure/redaction tests**

Use deterministic JSON fixtures and an injected HTTP transport. Cover exact
model match, missing/duplicate IDs, numeric overflow, absent limits, malformed
payloads, HTTP failure, timeout/cancellation, explicit-value precedence,
capture-time/source provenance, strict credential-free cache round trips,
atomic replacement, and diagnostics that contain neither auth headers nor
response bodies.

- [x] **Step 3: Implement non-blocking cached discovery**

The resolver uses HTTPS, bounded existing timeout conventions, optional named
environment auth only when required, and immutable candidates. Startup cache
loading and cache-miss refresh run off the Minecraft thread and never block
runtime creation. Settings can refresh manually. Runtime configuration remains
usable with explicit limits when discovery fails, and a failed refresh never
replaces a prior valid cache entry.

- [x] **Step 4: Run metadata tests and commit**

```bash
./gradlew :common:test --tests 'dev.tomewisp.model.metadata.*'
git commit -m "feat: discover trusted model metadata"
```

The implementation uses JDK 25 `HttpClient.sendAsync` behind one shared,
engine-neutral TomeWisp contract. JDK request/header types do not leak into
model, metadata, or future tool adapters, so another engine can be added without
rewriting domain codecs. It does not recreate connection pooling or add a
second retry loop. Domain adapters retain endpoint, credential, decoding, and
authority policy; OpenRouter discovery remains configuration I/O and is not an
Agent tool. Strict async cache tests cover cache miss, cross-launch reuse,
manual refresh, atomic replacement, malformed-cache preservation, numeric and
schema failures, explicit-value precedence, canonical identity, and redaction.
Both loaders now start the same named-profile registry, load cache without
blocking, refresh OpenRouter cache misses, and close the cache worker at client
shutdown. The complete common suite passes with 260 tests, zero failures/errors,
and one opt-in skip. The clean product gate
`./gradlew clean :common:test :fabric:build :neoforge:build` passes. The
production artifacts are
`tomewisp-fabric-26.2-0.1.0-SNAPSHOT.jar`
(`3107d3939070b04e6b2b60ea8fbbf5f310626c2a62a9a9476c721fdd14bd2a87`)
and `tomewisp-neoforge-26.2-0.1.0-SNAPSHOT.jar`
(`33d9d8837a17a563ae3c800004b8d4d8e04b93fa28ad900bb3b2403adddf3564`).

### Task 6: Native selector, commands, and loader parity

**Files:**
- Modify: `common/src/main/java/dev/tomewisp/guide/ui/GuideUiView.java`
- Modify: `common/src/main/java/dev/tomewisp/client/gui/TomeWispScreen.java`
- Modify: `common/src/main/java/dev/tomewisp/guide/GuideCommandFacade.java`
- Modify: `fabric/src/main/java/dev/tomewisp/fabric/TomeWispFabricClient.java`
- Modify: `neoforge/src/main/java/dev/tomewisp/neoforge/TomeWispNeoForgeClient.java`
- Modify: Fabric/NeoForge command registrations
- Modify: English and Simplified Chinese language files
- Test: `common/src/test/java/dev/tomewisp/guide/ui/GuideUiViewTest.java`
- Test: `common/src/test/java/dev/tomewisp/client/ClientArchitectureTest.java`

- [x] **Step 1: Write red selector and compatibility-command tests**

Show ordered enabled profiles, unavailable remembered selection, optional
server choice, and “running with” versus “next request” when an active request
captured a different selection. Prove selecting a profile changes one session
only. Preserve `/guide model client|server` and add explicit profile selection
without ambiguous silent fallback.

- [x] **Step 2: Replace the global mode button with a session selector**

The compact top-bar control cycles or opens the ordered model choices and shows
the selected profile's display name. It remains usable during an active request
and indicates the captured runtime when it differs. All labels/failures are
localized and credentials/endpoints are never shown in the normal player view.

- [x] **Step 3: Load one registry on both loaders**

Both loaders prefer `config/tomewisp/models.json`, fall back to legacy
`model.json`, create the same common runtime registry, and pass identical
profile summaries into `GuideService`. Extend the source-boundary parity test;
common production code keeps no Fabric/NeoForge imports.

- [x] **Step 4: Compile both loaders and commit**

```bash
./gradlew :common:test --tests 'dev.tomewisp.guide.ui.*' \
  --tests 'dev.tomewisp.client.ClientArchitectureTest' \
  :fabric:compileJava :neoforge:compileJava
git commit -m "feat: switch models within guide sessions"
```

The view now preserves profile configuration order, omits disabled profiles
unless one is the remembered/running exceptional choice, keeps removed or
invalid remembered selections visible, and treats only the selected profile's
availability as permission to send. The compact native control cycles through
available choices and remains usable during active work; its localized status
distinguishes the captured running model from the next-request selection.
`/guide model client|server` remains compatible, while
`/guide model profile <id>` selects one named profile and
`/guide model list` exposes credential-free choices. Fabric and NeoForge
register identical command shapes. The clean product gate passes with 263
common tests, zero failures/errors, and one opt-in skip. Production JAR SHA-256
values are
`74cef8cdae265fe97e91bbb2102fbe0fdf7c82363b3910016bcfd1ea57c9d6ce`
(Fabric) and
`23b056fd5964834756e063608a62f21db9ecb614459633b527dc446ec95c47c2`
(NeoForge).

### Task 7: Full verification and documentation

**Files:**
- Modify: `README.md`
- Modify: `docs/development.md`
- Modify: `docs/isme/SKMB.md`
- Modify: `docs/isme/decisions/2026-07-18-009-session-model-selection.md`
- Modify: this plan

- [x] **Step 1: Run focused and full deterministic gates**

```bash
./gradlew :common:test --tests 'dev.tomewisp.model.config.*' \
  --tests 'dev.tomewisp.model.metadata.*' \
  --tests 'dev.tomewisp.guide.*' \
  --tests 'dev.tomewisp.guide.history.*'
./gradlew clean :common:test :fabric:build :neoforge:build
```

- [x] **Step 2: Run schema, privacy, syntax, and artifact checks**

Record common test totals, unsupported-schema rejection evidence, loader hashes,
tracked shell/Python/JSON syntax, `git diff --check`, source-boundary checks,
and credential-pattern scans over production JARs. Confirm no new-format file,
packet, history record, diagnostic, snapshot, or rendered label contains an API
key value.

- [x] **Step 3: Update truthful status and commit**

Document the strict file format, pre-release reset policy, session switch semantics,
metadata provenance/priority, explicit unavailable-profile failures, and what
was not live-tested. Mark 009's implementation commits and retain final
graphical/provider acceptance for the consolidated Phase 4 smoke.

```bash
git commit -m "docs: verify multi-model session switching"
```

Verification on 2026-07-18 passed the focused configuration, metadata, guide,
and history suites, followed by the clean product gate with 263 common tests,
zero failures/errors, and one opt-in skip. Both production loader builds passed.
The production JAR SHA-256 values were:

- Fabric: `74cef8cdae265fe97e91bbb2102fbe0fdf7c82363b3910016bcfd1ea57c9d6ce`
- NeoForge: `23b056fd5964834756e063608a62f21db9ecb614459633b527dc446ec95c47c2`

Three tracked shell files, one Python file, and nine JSON files passed syntax
validation. `git diff --check`, common/loader source-boundary assertions, and
both production-JAR credential scans passed. Deterministic tests prove that the
new profile format rejects inline secrets, metadata cache records cannot
represent credentials, history selection JSON rejects extra credential fields,
and older/future pre-release database schema versions fail
`history_schema_unsupported` without mutation. No model-selection packet was
added; snapshots and rendered choices expose only profile ID/display name,
selection kind, availability, and running/selected state.

No graphical client or live OpenRouter/model-provider request was run for this
isolated package. Profile CRUD, the settings page/manual-refresh control, and
consolidated Phase 4 graphical/live-provider acceptance remain open.

## Completion boundary

This package is complete when named client profiles and the server model can be
selected per session, active requests retain their captured runtime, common
history survives switching, the clean current schema persists selections, OpenRouter
metadata discovery is explicit/trusted/redacted, both loaders are in parity,
and the full gate passes. Profile CRUD UI, general settings/diagnostics pages,
model-authored rich components, history paging, and final consolidated
graphical/live-provider acceptance remain active Phase 4 work.
