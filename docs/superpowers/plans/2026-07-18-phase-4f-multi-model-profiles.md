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

**Authority:** SKMB-2026-07-18-009 and
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

- [ ] **Step 1: Write red routing, shared-history, missing-profile, and replacement-race tests**

Use deterministic fake clients `model-a` and `model-b`. Prove calls route by
captured profile, the second model receives the first model's completed common
history, removing a profile fails future dispatch, and an in-flight call held by
an old immutable registry snapshot can finish after atomic reload.

- [ ] **Step 2: Refactor runtime construction around one shared session store**

Each profile gets its own immutable `ModelClient`, endpoint scheduler, context
budget/compactor, model provenance, and redacted trace secret set. All profile
agents share one `AgentSessionStore` and common tool executor semantics.

- [ ] **Step 3: Extend the local endpoint contract by profile ID**

Expose ordered profile summaries, default profile ID, profile availability,
profile-specific required context, and `ask(profileId, ...)`. Keep narrow
compatibility defaults for deterministic single-endpoint tests while production
loaders use the registry. Never select another runtime inside `ask`.

- [ ] **Step 4: Run focused client/agent tests and commit**

```bash
./gradlew :common:test --tests 'dev.tomewisp.client.*' \
  --tests 'dev.tomewisp.agent.session.*'
git commit -m "feat: route client requests by model profile"
```

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

- [ ] **Step 1: Write red isolation/capture/switch/retry/failure tests**

Prove two sessions concurrently select different profiles; switching during an
active request changes the session's next preference but not the request's
captured selection; retry uses the current preference; switching provider/model
does not clear messages/checkpoints; missing local profile, invalid profile, and
unavailable server model return distinct failures without dispatch.

- [ ] **Step 2: Introduce the closed selection value**

`GuideModelSelection` represents exactly `CLIENT(profileId)` or `SERVER`, uses
the stable session/profile ID grammar, derives the legacy `GuideModelMode`, and
has a strict JSON projection for persistence. Session and request snapshots
carry preferred and captured selections respectively; compatibility
constructors use `client("default")` only for old tests/callers.

- [ ] **Step 3: Make session state authoritative**

New sessions inherit the registry default client profile. `setModelSelection`
updates only the selected session and persists immediately, even while active.
`setModelMode` remains a compatibility adapter: server selects `SERVER`; client
restores that session's remembered last client profile. `submit` validates and
captures the selection before creating the request and passes the captured
profile to the registry. Snapshot-level `modelMode` becomes a derived
compatibility projection.

- [ ] **Step 4: Preserve cancellation, disconnect, and capability semantics**

Cancellation follows the request's captured topology. Server capability loss
fails only active server requests and leaves future preferences visible.
Disconnect clears connection-scoped sessions and creates a fresh main session
with the configured default; it never mutates a captured in-flight runtime.

- [ ] **Step 5: Run state/race tests and commit**

```bash
./gradlew :common:test --tests 'dev.tomewisp.guide.GuideService*' \
  --tests 'dev.tomewisp.guide.GuideStateReducerTest'
git commit -m "feat: select models per guide session"
```

### Task 4: Durable schema v3 selection migration

**Files:**
- Modify: `common/src/main/java/dev/tomewisp/guide/history/GuideHistoryPartition.java`
- Modify: `common/src/main/java/dev/tomewisp/guide/history/GuideHistoryCodec.java`
- Modify: `common/src/main/java/dev/tomewisp/guide/history/SqliteGuideHistoryStore.java`
- Modify: `common/src/test/java/dev/tomewisp/guide/history/GuideHistoryRepositoryTest.java`
- Modify: `common/src/test/java/dev/tomewisp/guide/history/SqliteGuideHistoryStoreTest.java`
- Modify: `common/src/test/java/dev/tomewisp/guide/history/GuideHistoryCodecTest.java`

- [ ] **Step 1: Write red v3 round-trip and v2 migration fixtures**

Cover different selections for multiple sessions, captured request selection,
unknown-but-valid profile retention, strict unknown kind/field rejection, and a
real schema-v2 SQLite fixture whose global client/server mode maps
transactionally to the configured migration default/server selection without
changing messages, timeline, evidence, or checkpoints.

- [ ] **Step 2: Store selection per session/request**

Schema v3 adds strict selection kind/profile data at session and request
boundaries. Remove global mode as an authority; a compatibility mode may be
derived for old callers. Replacement writes remain transactional and partition
isolation remains unchanged.

- [ ] **Step 3: Implement v1→v2→v3 migration**

Migrate in one transaction. V2 `CLIENT` maps every session to the configured
default profile; `SERVER` maps to server. Existing requests receive the same
captured selection implied by topology. Unknown future database/schema values
fail closed without partial writes.

- [ ] **Step 4: Run history tests and commit**

```bash
./gradlew :common:test --tests 'dev.tomewisp.guide.history.*'
git commit -m "feat: persist per-session model selection"
```

### Task 5: Trusted OpenRouter metadata discovery

**Files:**
- Create: `common/src/main/java/dev/tomewisp/model/metadata/ModelMetadata.java`
- Create: `common/src/main/java/dev/tomewisp/model/metadata/ModelMetadataResolver.java`
- Create: `common/src/main/java/dev/tomewisp/model/metadata/ModelMetadataResolution.java`
- Create: `common/src/main/java/dev/tomewisp/model/metadata/OpenRouterMetadataResolver.java`
- Test: `common/src/test/java/dev/tomewisp/model/metadata/OpenRouterMetadataResolverTest.java`
- Test: `common/src/test/java/dev/tomewisp/model/metadata/ModelMetadataResolutionTest.java`

- [ ] **Step 1: Verify the current official OpenRouter schema**

Use the official OpenRouter API/documentation only. Record the endpoint and
fields used (`id`, `context_length`, and output limit only when explicitly
published). Do not infer context from pricing, architecture names, or generic
OpenAI `/models` data.

- [ ] **Step 2: Write red parser/precedence/failure/redaction tests**

Use deterministic JSON fixtures and an injected HTTP transport. Cover exact
model match, missing/duplicate IDs, numeric overflow, absent limits, malformed
payloads, HTTP failure, timeout/cancellation, explicit-value precedence,
capture-time/source provenance, and diagnostics that contain neither auth
headers nor response bodies.

- [ ] **Step 3: Implement explicit diagnostic discovery**

The resolver uses HTTPS, bounded existing timeout conventions, optional named
environment auth only when required, and immutable candidates. Discovery is
called only by settings/diagnostics. Runtime configuration remains usable with
explicit limits when discovery fails and never performs discovery at startup.

- [ ] **Step 4: Run metadata tests and commit**

```bash
./gradlew :common:test --tests 'dev.tomewisp.model.metadata.*'
git commit -m "feat: discover trusted model metadata"
```

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

- [ ] **Step 1: Write red selector and compatibility-command tests**

Show ordered enabled profiles, unavailable remembered selection, optional
server choice, and “running with” versus “next request” when an active request
captured a different selection. Prove selecting a profile changes one session
only. Preserve `/guide model client|server` and add explicit profile selection
without ambiguous silent fallback.

- [ ] **Step 2: Replace the global mode button with a session selector**

The compact top-bar control cycles or opens the ordered model choices and shows
the selected profile's display name. It remains usable during an active request
and indicates the captured runtime when it differs. All labels/failures are
localized and credentials/endpoints are never shown in the normal player view.

- [ ] **Step 3: Load one registry on both loaders**

Both loaders prefer `config/tomewisp/models.json`, fall back to legacy
`model.json`, create the same common runtime registry, and pass identical
profile summaries into `GuideService`. Extend the source-boundary parity test;
common production code keeps no Fabric/NeoForge imports.

- [ ] **Step 4: Compile both loaders and commit**

```bash
./gradlew :common:test --tests 'dev.tomewisp.guide.ui.*' \
  --tests 'dev.tomewisp.client.ClientArchitectureTest' \
  :fabric:compileJava :neoforge:compileJava
git commit -m "feat: switch models within guide sessions"
```

### Task 7: Full verification and documentation

**Files:**
- Modify: `README.md`
- Modify: `docs/development.md`
- Modify: `docs/isme/SKMB.md`
- Modify: `docs/isme/decisions/2026-07-18-009-session-model-selection.md`
- Modify: this plan

- [ ] **Step 1: Run focused and full deterministic gates**

```bash
./gradlew :common:test --tests 'dev.tomewisp.model.config.*' \
  --tests 'dev.tomewisp.model.metadata.*' \
  --tests 'dev.tomewisp.guide.*' \
  --tests 'dev.tomewisp.guide.history.*'
./gradlew clean :common:test :fabric:build :neoforge:build
```

- [ ] **Step 2: Run migration, privacy, syntax, and artifact checks**

Record common test totals, real schema-v2 migration evidence, loader hashes,
tracked shell/Python/JSON syntax, `git diff --check`, source-boundary checks,
and credential-pattern scans over production JARs. Confirm no new-format file,
packet, history record, diagnostic, snapshot, or rendered label contains an API
key value.

- [ ] **Step 3: Update truthful status and commit**

Document the strict file format, legacy behavior, session switch semantics,
metadata provenance/priority, explicit unavailable-profile failures, and what
was not live-tested. Mark 009's implementation commits and retain final
graphical/provider acceptance for the consolidated Phase 4 smoke.

```bash
git commit -m "docs: verify multi-model session switching"
```

## Completion boundary

This package is complete when named client profiles and the server model can be
selected per session, active requests retain their captured runtime, common
history survives switching, schema-v2 history migrates to v3, OpenRouter
metadata discovery is explicit/trusted/redacted, both loaders are in parity,
and the full gate passes. Profile CRUD UI, general settings/diagnostics pages,
model-authored rich components, history paging, and final consolidated
graphical/live-provider acceptance remain active Phase 4 work.
