# Phase 4G Native Model Settings Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the common native settings foundation, atomic model-profile CRUD/reload, generation-safe metadata reconciliation, an isolated real connection probe, and a usable Models screen on both loaders.

**Architecture:** `ClientSettingsService` owns immutable settings snapshots and one foreground operation slot. Strict domain codecs prepare complete candidates; `AtomicSettingsFile` only supplies same-directory atomic replacement. `ClientModelRuntimeRegistry` exposes prepared non-failing publication, while metadata refresh returns cache events that the settings service reconciles against the current profile generation.

**Tech Stack:** Java 25, Gson, JDK `HttpClient` through the existing shared transport, Minecraft 26.2 native Screen widgets, JUnit 5, Gradle multi-loader build

---

## Scope and file map

- `dev.tomewisp.settings`: common file mechanics, settings snapshots, operation state, and service.
- `dev.tomewisp.model.config`: canonical profile encoding and candidate preparation.
- `ClientModelRuntimeRegistry`: prepared registry state and current-generation publication.
- `ModelMetadataBootstrap`: cache/refresh events only; no stale direct registry replacement.
- `ModelConnectionProbe`: one cancellable real inference request with redacted result mapping.
- `TomeWispSettingsScreen`: native shell and Models page; later plans add capability/history pages.
- Fabric/NeoForge entrypoints: identical common wiring with loader paths/lifecycle only.

### Task 1: Atomic settings files and canonical model-profile encoding

**Files:**
- Create: `common/src/main/java/dev/tomewisp/settings/AtomicSettingsFile.java`
- Create: `common/src/main/java/dev/tomewisp/settings/SettingsWriteException.java`
- Create: `common/src/main/java/dev/tomewisp/model/config/ModelProfilesConfigWriter.java`
- Test: `common/src/test/java/dev/tomewisp/settings/AtomicSettingsFileTest.java`
- Test: `common/src/test/java/dev/tomewisp/model/config/ModelProfilesConfigWriterTest.java`

- [x] **Step 1: Write failing atomicity and canonical round-trip tests**

Cover successful sibling replacement, move failure preserving the original,
best-effort temporary cleanup, exact field order/shape, no inline secret field,
and writer output round-tripping through `ModelProfilesConfigLoader`.

```java
@Test
void moveFailurePreservesOriginalAndCleansTemporarySibling() throws Exception {
    Path target = temporary.resolve("models.json");
    Files.writeString(target, "old");
    AtomicSettingsFile file = new AtomicSettingsFile((source, destination) -> {
        throw new IOException("move failed");
    });

    SettingsWriteException failure = assertThrows(
            SettingsWriteException.class, () -> file.replace(target, "new"));
    assertEquals("settings_write_failed", failure.code());
    assertEquals("old", Files.readString(target));
    assertEquals(List.of("models.json"), Files.list(temporary)
            .map(path -> path.getFileName().toString()).sorted().toList());
}

@Test
void encodedProfilesRoundTripWithoutCredentialFields() {
    String encoded = new ModelProfilesConfigWriter().encode(config());
    assertFalse(encoded.contains("apiKey\""));
    assertFalse(encoded.contains("secret-value"));
    ToolResult<ModelProfilesConfigLoader.Load> decoded = new ModelProfilesConfigLoader()
            .load(new StringReader(encoded), Map.of("MODEL_KEY", "secret-value"));
    assertInstanceOf(ToolResult.Success.class, decoded);
    assertEquals(config(), ((ToolResult.Success<ModelProfilesConfigLoader.Load>) decoded)
            .value().config());
}
```

- [x] **Step 2: Run tests and verify they fail because the new types do not exist**

```bash
./gradlew :common:test --tests 'dev.tomewisp.settings.AtomicSettingsFileTest' \
  --tests 'dev.tomewisp.model.config.ModelProfilesConfigWriterTest'
```

Expected: compilation failure for `AtomicSettingsFile` and
`ModelProfilesConfigWriter`.

- [x] **Step 3: Implement narrow atomic mechanics and canonical encoding**

Use a package-testable move port and a stable exception code. The mechanics
must create the parent directory, write UTF-8 to a temporary sibling, and
require `ATOMIC_MOVE + REPLACE_EXISTING` in production.

```java
public final class AtomicSettingsFile {
    @FunctionalInterface
    interface AtomicMove {
        void move(Path source, Path target) throws IOException;
    }

    public void replace(Path target, String contents) {
        // Create the sibling, write UTF-8, invoke atomicMove, and clean it in finally.
        // Every failure becomes code settings_write_failed with a fixed redacted message.
    }
}

public final class ModelProfilesConfigWriter {
    public String encode(ModelProfilesConfig config) {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", config.schemaVersion());
        root.addProperty("defaultProfileId", config.defaultProfileId());
        root.add("profiles", encodeProfiles(config.profiles()));
        return new Gson().toJson(root) + System.lineSeparator();
    }
}
```

`encodeProfiles` must emit exactly the loader's required/optional fields and
metadata provenance. Never add `apiKey`, resolved availability, or diagnostics.

- [x] **Step 4: Run focused tests and commit**

```bash
./gradlew :common:test --tests 'dev.tomewisp.settings.AtomicSettingsFileTest' \
  --tests 'dev.tomewisp.model.config.ModelProfilesConfig*Test'
git add common/src/main/java/dev/tomewisp/settings \
  common/src/main/java/dev/tomewisp/model/config/ModelProfilesConfigWriter.java \
  common/src/test/java/dev/tomewisp/settings \
  common/src/test/java/dev/tomewisp/model/config/ModelProfilesConfigWriterTest.java
git commit -m "feat: atomically encode model settings"
```

Expected: focused tests pass; the commit contains no loader/UI changes.

### Task 2: Prepare model registry replacement before persistence

**Files:**
- Modify: `common/src/main/java/dev/tomewisp/client/ClientModelRuntimeRegistry.java`
- Create: `common/src/main/java/dev/tomewisp/model/config/ModelProfileSettingsStore.java`
- Test: `common/src/test/java/dev/tomewisp/client/ClientModelRuntimeRegistryTest.java`
- Create: `common/src/test/java/dev/tomewisp/model/config/ModelProfileSettingsStoreTest.java`

- [x] **Step 1: Add failing tests for prepare/publish and file/runtime rollback**

```java
@Test
void preparedReplacementDoesNotPublishUntilExplicitCommit() {
    ClientModelRuntimeRegistry registry = registry(load("a", "a"), clients("a"));
    ClientModelRuntimeRegistry.PreparedReplacement prepared =
            registry.prepare(load("b", "b"));

    assertEquals(List.of("a"), ids(registry.profiles()));
    prepared.publish();
    assertEquals(List.of("b"), ids(registry.profiles()));
    assertThrows(IllegalStateException.class, prepared::publish);
}

@Test
void persistenceFailureNeverPublishesPreparedRegistry() {
    // Inject a failing AtomicSettingsFile into ModelProfileSettingsStore.
    // Assert target bytes and registry profile IDs remain unchanged.
}
```

Also prove an in-flight request held by the old runtime finishes after publish,
matching the existing capture test.

- [x] **Step 2: Run tests and verify the missing API failure**

```bash
./gradlew :common:test --tests 'dev.tomewisp.client.ClientModelRuntimeRegistryTest' \
  --tests 'dev.tomewisp.model.config.ModelProfileSettingsStoreTest'
```

- [x] **Step 3: Implement prepared replacement and store transaction**

```java
public final class ClientModelRuntimeRegistry {
    public PreparedReplacement prepare(ModelProfilesConfigLoader.Load candidate) {
        return new PreparedReplacement(this, build(candidate, modelFactory));
    }

    public static final class PreparedReplacement {
        private final ClientModelRuntimeRegistry owner;
        private final State state;
        private final AtomicBoolean published = new AtomicBoolean();

        public void publish() {
            if (!published.compareAndSet(false, true)) {
                throw new IllegalStateException("Prepared replacement already published");
            }
            owner.state.set(state);
        }
    }
}

public final class ModelProfileSettingsStore {
    public Saved save(ModelProfilesConfig candidate,
                      Map<String, String> environment,
                      Map<ModelMetadata.Key, ModelMetadata> metadata,
                      ClientModelRuntimeRegistry registry) {
        String encoded = writer.encode(candidate);
        ModelProfilesConfigLoader.Load resolved = requireSuccess(
                loader.load(new StringReader(encoded), environment, metadata));
        var prepared = registry.prepare(resolved);
        files.replace(path, encoded);
        prepared.publish();
        return new Saved(candidate, resolved.profiles());
    }
}
```

Build all client runtimes during `prepare`; `publish` must perform only the one
atomic reference swap. Store validation may retain a profile whose environment
variable is absent because the loader returns a resolved failure entry rather
than rejecting the complete document.

- [x] **Step 4: Run tests and commit**

```bash
./gradlew :common:test --tests 'dev.tomewisp.client.ClientModelRuntimeRegistryTest' \
  --tests 'dev.tomewisp.model.config.ModelProfileSettingsStoreTest'
git add common/src/main/java/dev/tomewisp/client/ClientModelRuntimeRegistry.java \
  common/src/main/java/dev/tomewisp/model/config/ModelProfileSettingsStore.java \
  common/src/test/java/dev/tomewisp/client/ClientModelRuntimeRegistryTest.java \
  common/src/test/java/dev/tomewisp/model/config/ModelProfileSettingsStoreTest.java
git commit -m "feat: prepare atomic model profile replacement"
```

### Task 3: Remove stale direct metadata profile application

**Files:**
- Modify: `common/src/main/java/dev/tomewisp/model/metadata/ModelMetadataBootstrap.java`
- Create: `common/src/main/java/dev/tomewisp/model/metadata/ModelMetadataUpdate.java`
- Modify: `common/src/main/java/dev/tomewisp/client/ClientModelRuntimeRegistry.java`
- Modify: `common/src/test/java/dev/tomewisp/model/metadata/ModelMetadataBootstrapTest.java`
- Modify: `common/src/test/java/dev/tomewisp/client/ClientModelRuntimeRegistryTest.java`

- [x] **Step 1: Replace direct profile-apply expectations with immutable cache-event tests**

Create `ModelMetadataUpdate` containing immutable cache entries and a redacted
failure. Prove bootstrap emits validated cache state but never constructs or
applies a `ModelProfilesConfigLoader.Load` to the registry.

```java
@Test
void refreshPublishesCacheUpdateWithoutApplyingProfileSnapshot() {
    List<ModelMetadataUpdate> updates = new ArrayList<>();
    ModelMetadataBootstrap bootstrap = bootstrap(updates::add, resolvedMetadata());
    bootstrap.start().join();

    assertFalse(updates.isEmpty());
    assertTrue(updates.getLast().entries().containsKey(metadataKey));
    assertNull(updates.getLast().failure());
}
```

- [x] **Step 2: Run metadata/registry tests and observe the old direct apply race**

```bash
./gradlew :common:test --tests 'dev.tomewisp.model.metadata.*' \
  --tests 'dev.tomewisp.client.ClientModelRuntimeRegistryTest'
```

- [x] **Step 3: Emit cache updates and re-read current profiles at the caller boundary**

```java
public record ModelMetadataUpdate(
        Map<ModelMetadata.Key, ModelMetadata> entries,
        GuideFailure failure) {
    public ModelMetadataUpdate { entries = Map.copyOf(entries); }
}
```

`ModelMetadataBootstrap` may read current profiles only to choose metadata
targets, but must stop calling
`Consumer<ModelProfilesConfigLoader.Load>`. It loads/refreshes the cache and
calls `Consumer<ModelMetadataUpdate>`. No cache completion may call registry
replacement. `ClientSettingsService` in Task 5 owns current profiles and
generation-safe reconciliation.

- [x] **Step 4: Run tests and commit**

```bash
./gradlew :common:test --tests 'dev.tomewisp.model.metadata.*' \
  --tests 'dev.tomewisp.client.ClientModelRuntimeRegistryTest'
git add common/src/main/java/dev/tomewisp/model/metadata \
  common/src/main/java/dev/tomewisp/client/ClientModelRuntimeRegistry.java \
  common/src/test/java/dev/tomewisp/model/metadata \
  common/src/test/java/dev/tomewisp/client/ClientModelRuntimeRegistryTest.java
git commit -m "fix: reconcile metadata against current profiles"
```

### Task 4: Implement isolated redacted model connection probes

**Files:**
- Create: `common/src/main/java/dev/tomewisp/settings/model/ModelConnectionProbe.java`
- Create: `common/src/main/java/dev/tomewisp/settings/model/ModelConnectionResult.java`
- Test: `common/src/test/java/dev/tomewisp/settings/model/ModelConnectionProbeTest.java`
- Modify: `common/src/main/java/dev/tomewisp/model/http/ModelHttpErrors.java`
- Create: `common/src/test/java/dev/tomewisp/model/http/ModelHttpErrorsTest.java`

- [x] **Step 1: Write failing probe contract tests**

Prove the request is non-streaming, has one fixed user message, no Tools, an
isolated session key, output config capped at 64, and no provider content is
retained. Cover success latency, empty output, 401/403, 404, 429, timeout,
transport/protocol failure, cancel, and busy mapping.

```java
@Test
void sendsOneContextFreeRequestAndDiscardsAssistantText() {
    RecordingModel model = new RecordingModel(turn("SENSITIVE-OUTPUT"));
    ModelConnectionProbe probe = new ModelConnectionProbe(
            ignored -> model, Clock.systemUTC(), System::nanoTime);
    ModelConnectionResult result = probe.test(profile(), cancellation()).join();

    ModelRequest request = model.requests().getOnly();
    assertEquals(List.of(), request.tools());
    assertFalse(request.stream());
    assertEquals("Reply exactly OK.",
            ((ModelContent.Text) request.messages().getFirst().content().getFirst()).text());
    assertInstanceOf(ModelConnectionResult.Success.class, result);
    assertFalse(result.toString().contains("SENSITIVE-OUTPUT"));
}
```

- [x] **Step 2: Run the probe tests and verify failure**

```bash
./gradlew :common:test --tests 'dev.tomewisp.settings.model.ModelConnectionProbeTest' \
  --tests 'dev.tomewisp.model.http.ModelHttpErrorsTest'
```

- [x] **Step 3: Implement the probe and safe HTTP classification**

```java
public sealed interface ModelConnectionResult {
    record Success(String profileId, ModelProtocol protocol, String authority,
                   Instant completedAt, long latencyMillis)
            implements ModelConnectionResult {}
    record Failure(String code, String message) implements ModelConnectionResult {}
}

public final class ModelConnectionProbe {
    public ModelConnectionProbe(
            Function<ModelConfig, ModelClient> factory,
            Clock clock,
            LongSupplier nanoTime) { /* store validated dependencies */ }

    public CompletableFuture<ModelConnectionResult> test(
            ResolvedModelProfile profile,
            CancellationSignal cancellation) {
        ModelConfig source = profile.runtimeConfig();
        ModelConfig probeConfig = new ModelConfig(
                source.enabled(), source.protocol(), source.baseUri(), source.model(),
                source.apiKey(), source.contextWindowTokens(),
                Math.min(source.maxOutputTokens(), 64),
                source.connectTimeout(), source.requestTimeout());
        ModelClient client = factory.apply(probeConfig);
        ModelRequest request = new ModelRequest(
                "TomeWisp connectivity check. Do not provide any other content.",
                List.of(ModelMessage.userText("Reply exactly OK.")),
                List.of(), false, "tomewisp-settings-probe");
        // Measure monotonic elapsed time, classify completion/exception, discard turn text.
    }
}
```

Do not put raw `ModelClientException` messages in the result. Classify by safe
exception type/status. Refactor `ModelHttpErrors` so generic model calls may
retain the existing redacted status message but never append provider error
text; this closes the current raw upstream message leak for all callers.

- [x] **Step 4: Run model transport/redaction tests and commit**

```bash
./gradlew :common:test --tests 'dev.tomewisp.settings.model.*' \
  --tests 'dev.tomewisp.model.http.*' \
  --tests 'dev.tomewisp.model.openai.*' \
  --tests 'dev.tomewisp.model.anthropic.*'
git add common/src/main/java/dev/tomewisp/settings/model \
  common/src/main/java/dev/tomewisp/model/http/ModelHttpErrors.java \
  common/src/test/java/dev/tomewisp/settings/model \
  common/src/test/java/dev/tomewisp/model/http/ModelHttpErrorsTest.java
git commit -m "feat: probe model connections safely"
```

### Task 5: Add the common settings service and model actions

**Files:**
- Create: `common/src/main/java/dev/tomewisp/settings/ClientSettingsService.java`
- Create: `common/src/main/java/dev/tomewisp/settings/ClientSettingsSnapshot.java`
- Create: `common/src/main/java/dev/tomewisp/settings/SettingsOperation.java`
- Create: `common/src/main/java/dev/tomewisp/settings/SettingsNotice.java`
- Create: `common/src/main/java/dev/tomewisp/settings/model/ModelProfileSettingsView.java`
- Test: `common/src/test/java/dev/tomewisp/settings/ClientSettingsServiceTest.java`

- [x] **Step 1: Write failing service state/race tests**

Cover initial snapshot, deterministic profile order, create/edit/delete/default,
missing-env availability, one foreground slot, probe cancellation on owner
detach, durable save completion after ordinary listener detach, metadata
generation reconciliation, failed save retention, and close cancellation.

```java
@Test
void probeAndMutationShareOneForegroundOperationSlot() {
    CompletableFuture<ModelConnectionResult> pending = service.testConnection(candidate());
    ToolResult<Boolean> save = service.saveModels(changed()).join();
    assertFailure(save, "settings_busy");
    assertFailure(service.testConnection(other()), "connection_test_busy");
    service.cancelConnectionTest();
    assertFailure(pending.join(), "connection_cancelled");
}

@Test
void ordinaryListenerDetachDoesNotCancelConfirmedSave() {
    AutoCloseable listener = service.listen(seen::add);
    CompletableFuture<ToolResult<Boolean>> save = service.saveModels(candidate());
    listener.close();
    files.completeMove();
    assertSuccess(save.join());
    assertEquals(candidate(), service.snapshot().models().config());
}

@Test
void lateMetadataUpdateCannotPublishOlderProfileGeneration() {
    registry.blockNextPrepare();
    service.acceptMetadataUpdate(metadataFor("old-model"));
    registry.awaitPrepareCaptured();
    service.saveModels(profileConfig("new-model")).join();
    registry.releasePrepare();

    assertEquals("new-model",
            service.snapshot().models().config().profiles().getFirst().model());
    assertEquals("new-model", registry.profiles().getFirst().model());
}
```

- [x] **Step 2: Run the focused service test and verify missing types**

```bash
./gradlew :common:test --tests 'dev.tomewisp.settings.ClientSettingsServiceTest'
```

- [x] **Step 3: Implement immutable snapshots and serialized foreground actions**

```java
public record ClientSettingsSnapshot(
        long generation,
        GuideDisplayConfig display,
        ModelProfileSettingsView models,
        SettingsOperation operation,
        SettingsNotice notice) {}

public final class ClientSettingsService implements AutoCloseable {
    public ClientSettingsSnapshot snapshot();
    public AutoCloseable listen(Consumer<ClientSettingsSnapshot> listener);
    public CompletableFuture<ToolResult<Boolean>> saveModels(ModelProfilesConfig candidate);
    public CompletableFuture<ToolResult<Boolean>> reloadModels(boolean discardDirtyConfirmed);
    public CompletableFuture<ToolResult<Boolean>> refreshMetadata();
    public CompletableFuture<ModelConnectionResult> testConnection(ModelProfileDefinition candidate);
    public boolean cancelConnectionTest();
    public CompletableFuture<Void> closeAsync();
}
```

Use one lock/atomic state for operation reservation and snapshot generation.
Run file and probe work off-thread. Marshal every published snapshot through the
dispatcher. Never retain environment values: copy only a sorted set of present
environment-variable names into the service constructor.

- [x] **Step 4: Run service/model/metadata tests and commit**

```bash
./gradlew :common:test --tests 'dev.tomewisp.settings.*' \
  --tests 'dev.tomewisp.client.ClientModelRuntimeRegistryTest' \
  --tests 'dev.tomewisp.model.metadata.*'
git add common/src/main/java/dev/tomewisp/settings \
  common/src/test/java/dev/tomewisp/settings
git commit -m "feat: coordinate native model settings"
```

### Task 6: Build the native settings shell and Models page

**Files:**
- Create: `common/src/main/java/dev/tomewisp/client/gui/TomeWispSettingsScreen.java`
- Create: `common/src/main/java/dev/tomewisp/client/gui/settings/SettingsSection.java`
- Create: `common/src/main/java/dev/tomewisp/client/gui/settings/ModelProfileDraft.java`
- Create: `common/src/main/java/dev/tomewisp/client/gui/settings/SettingsLayout.java`
- Modify: `common/src/main/java/dev/tomewisp/client/gui/TomeWispScreen.java`
- Create: `common/src/test/java/dev/tomewisp/client/gui/TomeWispSettingsScreenProjectionTest.java`
- Create: `common/src/test/java/dev/tomewisp/client/gui/settings/SettingsLayoutTest.java`

- [x] **Step 1: Write projection/layout/interaction tests before rendering code**

Test wide/narrow layout thresholds, top-level section order, profile ordering,
dirty drafts, save/reload confirmation, delete confirmation, environment
presence without values, live-cost notice, probe busy/cancel/status, keyboard
focus/back navigation, and listener detach.

```java
@Test
void topLevelSectionsUseKnowledgeAndCapabilitiesNotRecipes() {
    assertEquals(List.of(GENERAL, MODELS, KNOWLEDGE_AND_CAPABILITIES, HISTORY, DIAGNOSTICS),
            SettingsSection.topLevel());
}

@Test
void modelProjectionNeverContainsEnvironmentValue() {
    var view = projection(snapshotWithPresentEnv("MIMO_KEY", "secret-value"));
    assertTrue(view.lines().stream().anyMatch(line -> line.contains("MIMO_KEY")));
    assertFalse(view.toString().contains("secret-value"));
}
```

- [x] **Step 2: Run UI projection tests and verify missing screen/layout types**

```bash
./gradlew :common:test --tests 'dev.tomewisp.client.gui.*Settings*'
```

- [x] **Step 3: Implement the screen shell and Models page**

Use native buttons, edit boxes, scroll lists, narration, and scissor regions.
Wide layout renders section rail + profile list + editor; narrow layout renders
one navigation level with a Back action. Keep rendering state separate from
`ModelProfileDraft` validation and `ClientSettingsSnapshot` projection.

The existing Guide screen settings button must call a supplied opener instead
of constructing the settings service. Returning from settings reopens the same
GuideService view without clearing selection or request state.

- [x] **Step 4: Run GUI tests and commit**

```bash
./gradlew :common:test --tests 'dev.tomewisp.client.gui.*' \
  --tests 'dev.tomewisp.settings.*'
git add common/src/main/java/dev/tomewisp/client/gui \
  common/src/test/java/dev/tomewisp/client/gui
git commit -m "feat: add native model settings screen"
```

### Task 7: Wire both loaders, localization, shutdown, and commands

**Files:**
- Modify: `fabric/src/main/java/dev/tomewisp/fabric/TomeWispFabricClient.java`
- Modify: `neoforge/src/main/java/dev/tomewisp/neoforge/TomeWispNeoForgeClient.java`
- Modify: `common/src/main/resources/assets/tomewisp/lang/en_us.json`
- Modify: `common/src/main/resources/assets/tomewisp/lang/zh_cn.json`
- Modify: `common/src/test/java/dev/tomewisp/client/ClientArchitectureTest.java`
- Modify: `common/src/test/java/dev/tomewisp/client/gui/TomeWispScreenProjectionTest.java`

- [ ] **Step 1: Add loader-parity and localization-key failures**

Assert both entrypoints create one `ClientSettingsService` with the same paths,
pass it to the same screen opener, close it before model/history repositories,
and contain no model CRUD behavior. Assert every new English key exists in
Simplified Chinese and normal UI has no raw failure/provider-body labels.

- [ ] **Step 2: Run parity tests and verify they fail on missing wiring**

```bash
./gradlew :common:test --tests 'dev.tomewisp.client.ClientArchitectureTest' \
  --tests 'dev.tomewisp.client.gui.*'
```

- [ ] **Step 3: Wire common service and localized player text**

Both loaders pass:

```java
Path tomewispConfig = loaderConfigDir.resolve("tomewisp");
ClientSettingsService settings = ClientSettingsService.create(
        tomewispConfig, modelRegistry, metadata, dispatcher, clock, System.getenv());
```

Shutdown calls `settings.closeAsync()` before closing metadata/model resources.
The Guide screen's settings action opens `TomeWispSettingsScreen`; no external
browser or arbitrary path action is added.

- [ ] **Step 4: Run focused tests and both loader builds, then commit**

```bash
./gradlew :common:test --tests 'dev.tomewisp.settings.*' \
  --tests 'dev.tomewisp.client.gui.*' \
  --tests 'dev.tomewisp.architecture.*' \
  :fabric:build :neoforge:build
git add fabric/src/main/java/dev/tomewisp/fabric/TomeWispFabricClient.java \
  neoforge/src/main/java/dev/tomewisp/neoforge/TomeWispNeoForgeClient.java \
  common/src/main/resources/assets/tomewisp/lang \
  common/src/test/java/dev/tomewisp/client/ClientArchitectureTest.java \
  common/src/test/java/dev/tomewisp/client/gui
git commit -m "feat: wire model settings on both loaders"
```

### Task 8: Verification, live opt-in probe, and truthful documentation

**Files:**
- Modify: `README.md`
- Modify: `docs/development.md`
- Modify: `docs/isme/decisions/2026-07-18-015-settings-model-administration.md`
- Modify: `docs/isme/decisions/2026-07-18-016-native-settings-coordination.md`
- Modify: `docs/isme/SKMB.md`
- Modify: `docs/superpowers/plans/2026-07-18-phase-4g-native-model-settings.md`
- Modify: `scripts/live-model-smoke.sh`

- [ ] **Step 1: Extend the existing live smoke harness without accepting secrets on argv**

Add an explicit settings-probe mode that reads endpoint/model/protocol/profile
from an ignored JSON path and the API key only from the environment variable
named by that file. It must print only a redacted authority, model label,
latency, and terminal code. It must refuse inline `apiKey`, URL userinfo/query,
or non-HTTPS non-loopback endpoints.

- [ ] **Step 2: Run deterministic full verification**

```bash
./gradlew clean :common:test :fabric:build :neoforge:build
git diff --check
bash -n scripts/*.sh
python3 -m py_compile scripts/*.py
```

Record actual test totals, loader JAR paths and SHA-256 hashes, JSON/shell/Python
syntax results, source-boundary checks, and production-JAR scans for credential
patterns and known test secrets.

- [ ] **Step 3: Run one authorized live probe only from a user-exported environment**

Do not copy a conversation key into a command, file, log, or tool argument. Run
only if the expected named environment variable is already present in the
launched shell/process. Otherwise record the live check as not run and continue
with deterministic completion.

Expected retained result: one success/failure category and latency with no
assistant output or raw body. Recommend rotating the conversation-exposed key
afterward.

- [ ] **Step 4: Update docs and commit verification truth**

Document native profile CRUD, env-only credentials, real-request warning,
metadata/listing separation, redacted failures, both-loader parity, and exactly
what graphical/live actions were or were not run. Fill the implementation
commit fields for SKMB 015/016 without altering approval text.

```bash
git add README.md docs/development.md docs/isme docs/superpowers/plans/2026-07-18-phase-4g-native-model-settings.md scripts/live-model-smoke.sh
git commit -m "docs: verify native model settings"
```

## Completion boundary

Phase 4G is complete when model profiles can be created, edited, disabled,
deleted, made default, reloaded, metadata-refreshed, and safely probed from the
native screen; saves are atomic and generation-safe; active requests retain
captured runtimes; both loaders are in parity; and the clean gate passes.
Knowledge/Tool/Skill policy, recipe child settings, history administration, and
the final consolidated settings smoke remain Phase 4H/4I rather than being
claimed here.
