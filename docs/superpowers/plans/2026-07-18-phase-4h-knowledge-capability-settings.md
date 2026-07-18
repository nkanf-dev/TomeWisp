# Phase 4H Knowledge and Capability Settings Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the top-level recipe-settings concept with a registered Knowledge & Capabilities catalog, add deny-only local Tool/Skill policy captured per future request, and move generic stable-ID recipe-source/viewer controls into the recipe Tool child page.

**Architecture:** Bootstrap registries remain the immutable authority for available Tools, Skills, and sources. `CapabilityPolicy` filters them into an immutable `ClientCapabilitySnapshot`; client runtimes published for future requests share the existing endpoint scheduler/session store while capturing that snapshot. A code-owned catalog projects trusted descriptors and typed child routes; it cannot register or authorize Agent capabilities.

**Tech Stack:** Java 25 records/sealed interfaces, strict Gson codecs, existing Agent Tool/Skill contracts, Minecraft native Screen widgets, JUnit 5, Fabric/NeoForge integration adapters

---

## Scope and file map

- `dev.tomewisp.capability`: deny policy, immutable request snapshot, trusted catalog descriptors, and failure diagnostics.
- Tool/Skill registries: safe immutable registration views and filtered runtime catalogs.
- Client model runtime: capability reconfiguration that preserves endpoint scheduling, sessions, and active-request capture.
- Recipe config/runtime: generic `disabledSources` and stable `preferredViewer` ID.
- Settings service/screen: Knowledge & Capabilities parent page plus recipe Tool child page.

### Task 1: Replace hard-coded recipe settings with stable source IDs

**Files:**
- Modify: `common/src/main/java/dev/tomewisp/recipe/config/RecipeClientConfig.java`
- Modify: `common/src/main/java/dev/tomewisp/recipe/config/RecipeClientConfigLoader.java`
- Create: `common/src/main/java/dev/tomewisp/recipe/config/RecipeClientConfigWriter.java`
- Delete: `common/src/main/java/dev/tomewisp/recipe/config/RecipeViewerPreference.java`
- Modify: `common/src/main/java/dev/tomewisp/recipe/config/RecipeClientRuntime.java`
- Modify: `common/src/test/java/dev/tomewisp/recipe/config/RecipeClientConfigLoaderTest.java`
- Modify: `common/src/test/java/dev/tomewisp/recipe/config/RecipeClientRuntimeTest.java`
- Create: `common/src/test/java/dev/tomewisp/recipe/config/RecipeClientConfigWriterTest.java`

- [ ] **Step 1: Rewrite tests for the one current pre-release schema**

```java
@Test
void loadsGenericStableSourceIds() {
    RecipeClientConfig config = success(loader.load(new StringReader("""
        {"schemaVersion":2,"visibility":"ALL_KNOWN",
         "preferredViewer":"viewer:emi",
         "disabledSources":["viewer:jei","future:viewer"]}
        """))).value();

    assertEquals("viewer:emi", config.preferredViewer());
    assertEquals(Set.of("viewer:jei", "future:viewer"), config.disabledSources());
}

@Test
void oldAdapterSpecificSchemaFailsWithoutMigration() {
    assertFailure(loader.load(new StringReader(oldV1Json())), "invalid_recipe_config");
}

@Test
void unavailableExplicitViewerDoesNotFallBack() {
    RecipeClientRuntime runtime = runtime(config("viewer:emi"), jei(), rei());
    assertEquals("viewer_unavailable", runtime.openRecipes("minecraft:stick").code());
}
```

Also cover duplicate/blank/malformed IDs, canonical sorted output, retained
unknown disabled IDs, `auto` deterministic order, source disablement, and
writer-loader round trip.

- [ ] **Step 2: Run recipe config/runtime tests and verify the old shape fails expectations**

```bash
./gradlew :common:test --tests 'dev.tomewisp.recipe.config.*'
```

- [ ] **Step 3: Implement schema 2 and generic runtime selection**

```java
public record RecipeClientConfig(
        int schemaVersion,
        RecipeVisibilityPolicy visibility,
        String preferredViewer,
        Set<String> disabledSources) {
    public static final int SCHEMA_VERSION = 2;
    public static final String AUTO = "auto";
}
```

Validate `preferredViewer` as `auto` or a stable source ID and every disabled
entry through the existing source-ID contract. Encode sorted IDs. Runtime
source enablement becomes `!config.disabledSources().contains(sourceId)`.
Explicit preferred viewer selects only that enabled/available navigator;
`auto` applies the deterministic known-viewer rank and then stable ID as a
tie-breaker. Keep known rank in one helper, not the persisted schema.

- [ ] **Step 4: Run tests, verify no v1 compatibility code, and commit**

```bash
./gradlew :common:test --tests 'dev.tomewisp.recipe.config.*' \
  --tests 'dev.tomewisp.recipe.*'
rg -n 'vanillaEnabled|jeiEnabled|reiEnabled|RecipeViewerPreference' common/src || true
git add common/src/main/java/dev/tomewisp/recipe/config \
  common/src/test/java/dev/tomewisp/recipe/config
git commit -m "refactor: generalize recipe source settings"
```

Expected: search returns no production references to the removed adapter-
specific config fields/type.

### Task 2: Add strict local Tool and Skill deny policy

**Files:**
- Create: `common/src/main/java/dev/tomewisp/capability/CapabilityPolicy.java`
- Create: `common/src/main/java/dev/tomewisp/capability/CapabilityPolicyLoader.java`
- Create: `common/src/main/java/dev/tomewisp/capability/CapabilityPolicyWriter.java`
- Create: `common/src/main/java/dev/tomewisp/capability/CapabilityPolicyStore.java`
- Create: `common/src/test/java/dev/tomewisp/capability/CapabilityPolicyLoaderTest.java`
- Create: `common/src/test/java/dev/tomewisp/capability/CapabilityPolicyStoreTest.java`

- [ ] **Step 1: Write strict codec, unknown-ID retention, dependency, and atomicity tests**

```java
@Test
void retainsUnknownDisabledIdentitiesAndCanonicalizesOrder() {
    CapabilityPolicy policy = success(loader.load(new StringReader("""
        {"schemaVersion":1,
         "disabledTools":["future:tool","tomewisp:get_recipe"],
         "disabledSkills":["future-skill"]}
        """))).value();
    assertEquals(Set.of("future:tool", "tomewisp:get_recipe"), policy.disabledTools());
    assertEquals(policy, success(loader.load(
            new StringReader(writer.encode(policy)))).value());
}

@Test
void failedAtomicMoveRetainsPriorPolicyAndRuntime() {
    // Inject failing AtomicSettingsFile; assert old file bytes and current policy unchanged.
}
```

Reject extra/missing fields, duplicate IDs, invalid namespace/name, blank Skill
names, and unsupported schema. Missing file returns defaults without writing.

- [ ] **Step 2: Run tests and verify missing policy types**

```bash
./gradlew :common:test --tests 'dev.tomewisp.capability.CapabilityPolicy*Test'
```

- [ ] **Step 3: Implement immutable policy and atomic store**

```java
public record CapabilityPolicy(
        int schemaVersion,
        Set<String> disabledTools,
        Set<String> disabledSkills) {
    public static final int SCHEMA_VERSION = 1;
    public static CapabilityPolicy defaults() {
        return new CapabilityPolicy(SCHEMA_VERSION, Set.of(), Set.of());
    }
}

public final class CapabilityPolicyStore {
    public ToolResult<CapabilityPolicy> load();
    public ToolResult<CapabilityPolicy> save(CapabilityPolicy candidate);
}
```

Defensively copy into sorted immutable sets. The store validates by canonical
encode/decode before calling `AtomicSettingsFile.replace` and publishes only
after the move succeeds.

- [ ] **Step 4: Run tests and commit**

```bash
./gradlew :common:test --tests 'dev.tomewisp.capability.CapabilityPolicy*Test'
git add common/src/main/java/dev/tomewisp/capability \
  common/src/test/java/dev/tomewisp/capability
git commit -m "feat: persist local capability policy"
```

### Task 3: Build immutable Tool and Skill runtime catalogs

**Files:**
- Modify: `common/src/main/java/dev/tomewisp/tool/ToolRegistry.java`
- Create: `common/src/main/java/dev/tomewisp/tool/RegisteredTool.java`
- Create: `common/src/main/java/dev/tomewisp/agent/tool/ToolRuntimeCatalog.java`
- Create: `common/src/main/java/dev/tomewisp/skill/SkillCatalog.java`
- Create: `common/src/main/java/dev/tomewisp/skill/SkillCatalogSnapshot.java`
- Modify: `common/src/main/java/dev/tomewisp/skill/SkillRepository.java`
- Modify: `common/src/main/java/dev/tomewisp/skill/LoadSkillTool.java`
- Modify: `common/src/main/java/dev/tomewisp/agent/tool/LocalAgentToolExecutor.java`
- Modify: `common/src/test/java/dev/tomewisp/tool/ToolRegistryTest.java`
- Modify: `common/src/test/java/dev/tomewisp/skill/SkillRepositoryTest.java`
- Modify: `common/src/test/java/dev/tomewisp/skill/LoadSkillToolTest.java`
- Modify: `common/src/test/java/dev/tomewisp/agent/tool/LocalAgentToolExecutorTest.java`

- [ ] **Step 1: Write failing snapshot/authority tests**

Prove provider identities survive snapshotting, filtered Tools disappear from
definitions/required context/execution, Skill documents are immutable, a
disabled Skill cannot load, and base registries remain unchanged.

```java
@Test
void filteredCatalogCannotExecuteDisabledTool() {
    ToolRuntimeCatalog catalog = ToolRuntimeCatalog.from(
            registry.registrations(), Set.of("tomewisp:get_recipe"));
    LocalAgentToolExecutor executor = new LocalAgentToolExecutor(catalog, gson);

    assertFalse(executor.definitions().stream()
            .anyMatch(definition -> definition.name().contains("get_recipe")));
    assertFailure(executor.execute(encodedGetRecipe, new JsonObject(), context, cancel()),
            "tool_unavailable");
}

@Test
void skillSnapshotDoesNotObserveLaterRepositoryReload() {
    SkillCatalogSnapshot snapshot = repository.snapshot(Set.of());
    repository.reload(otherSources(), installedMods());
    assertTrue(snapshot.find("original-skill").isPresent());
}
```

- [ ] **Step 2: Run registry/executor/Skill tests and verify missing APIs**

```bash
./gradlew :common:test --tests 'dev.tomewisp.tool.ToolRegistryTest' \
  --tests 'dev.tomewisp.agent.tool.LocalAgentToolExecutorTest' \
  --tests 'dev.tomewisp.skill.*'
```

- [ ] **Step 3: Implement public immutable registration and filtered catalogs**

```java
public record RegisteredTool(String providerId, Tool<?, ?> tool) {
    public RegisteredTool {
        if (providerId == null || providerId.isBlank()) {
            throw new IllegalArgumentException("providerId must not be blank");
        }
        Objects.requireNonNull(tool, "tool");
    }
}

public interface SkillCatalog {
    Optional<SkillDocument> find(String name);
    List<SkillMetadata> metadata();
    String metadataPrompt();
}

public record ToolRuntimeCatalog(
        List<RegisteredTool> registrations,
        Map<String, Tool<?, ?>> byId) {}
```

`ToolRegistry.registrations()` returns a defensive immutable ordered list.
`LocalAgentToolExecutor` consumes `ToolRuntimeCatalog`, decodes only catalog
names, and returns a stable `tool_unavailable` failure for a name outside that
captured catalog. `LoadSkillTool` depends on `SkillCatalog`, allowing the server
to keep the repository and the client to use a snapshot.

- [ ] **Step 4: Run tests and commit**

```bash
./gradlew :common:test --tests 'dev.tomewisp.tool.*' \
  --tests 'dev.tomewisp.agent.tool.*' \
  --tests 'dev.tomewisp.skill.*'
git add common/src/main/java/dev/tomewisp/tool \
  common/src/main/java/dev/tomewisp/agent/tool \
  common/src/main/java/dev/tomewisp/skill \
  common/src/test/java/dev/tomewisp/tool \
  common/src/test/java/dev/tomewisp/agent/tool \
  common/src/test/java/dev/tomewisp/skill
git commit -m "refactor: snapshot Tool and Skill catalogs"
```

### Task 4: Validate policy dependencies and capture one client capability runtime

**Files:**
- Create: `common/src/main/java/dev/tomewisp/capability/ClientCapabilitySnapshot.java`
- Create: `common/src/main/java/dev/tomewisp/capability/ClientCapabilityResolver.java`
- Modify: `common/src/main/java/dev/tomewisp/client/ClientGuideRuntime.java`
- Modify: `common/src/main/java/dev/tomewisp/client/ClientModelRuntimeRegistry.java`
- Modify: `common/src/test/java/dev/tomewisp/client/ClientModelRuntimeRegistryTest.java`
- Create: `common/src/test/java/dev/tomewisp/capability/ClientCapabilityResolverTest.java`

- [ ] **Step 1: Write dependency and active-request capture races**

```java
@Test
void enabledSkillDependingOnDisabledToolRejectsCandidate() {
    ToolResult<ClientCapabilitySnapshot> result = resolver.resolve(
            policy(disabledTools("tomewisp:get_recipe")), tools, skills);
    assertFailure(result, "capability_dependency_conflict");
}

@Test
void capabilityReplacementAffectsFutureRequestOnlyAndSharesSchedulerHistory() {
    CompletableFuture<ModelTurn> firstTurn = new CompletableFuture<>();
    CompletableFuture<AgentResult> active = registry.ask(
            "main", actor, "main", UUID.randomUUID(), "question",
            ToolInvocationContext.developmentConsole("test"), ignored -> {});
    registry.replaceCapabilities(snapshotWithout("tomewisp:get_recipe"));
    firstTurn.complete(toolCall("get_recipe"));
    assertTrue(active.join().successful());

    ModelRequest next = askAndCapture(registry);
    assertFalse(hasTool(next, "get_recipe"));
    assertContainsPriorHistory(next);
    assertSame(endpointSchedulerBefore, endpointSchedulerAfter);
}
```

Also prove `load_skill` is derived only when at least one Skill is enabled and
is never represented in `disabledTools` UI policy.

- [ ] **Step 2: Run capability/registry tests and verify current shared repository leakage**

```bash
./gradlew :common:test --tests 'dev.tomewisp.capability.*' \
  --tests 'dev.tomewisp.client.ClientModelRuntimeRegistryTest'
```

- [ ] **Step 3: Implement dependency resolution and scheduler-preserving reconfiguration**

```java
public record ClientCapabilitySnapshot(
        CapabilityPolicy policy,
        ToolRuntimeCatalog localTools,
        SkillCatalogSnapshot skills,
        Set<ContextCapability> requiredContext) {}

public final class ClientCapabilityResolver {
    public ToolResult<ClientCapabilitySnapshot> resolve(
            CapabilityPolicy policy,
            List<RegisteredTool> registrations,
            SkillRepository skills) {
        // Exclude disabled Tools and disabled Skills, validate every enabled
        // Skill allowedTools subset, derive captured load_skill when non-empty.
    }
}
```

Refactor `ClientGuideRuntime` to separate an endpoint runtime (ModelClient,
`ModelRequestScheduler`, model config, compactor inputs) from capability-
specific Agent construction. `withCapabilities(snapshot)` shares the endpoint
runtime, `AgentSessionStore`, dispatcher, and traces but creates a new captured
local executor/system-prompt Skill view. Registry capability replacement uses
`withCapabilities`; model-profile replacement still creates new endpoints.
An already selected `ClientGuideRuntime` object continues an active ask.

- [ ] **Step 4: Run Agent/context/scheduler/registry tests and commit**

```bash
./gradlew :common:test --tests 'dev.tomewisp.capability.*' \
  --tests 'dev.tomewisp.client.*' \
  --tests 'dev.tomewisp.agent.*' \
  --tests 'dev.tomewisp.model.scheduling.*'
git add common/src/main/java/dev/tomewisp/capability \
  common/src/main/java/dev/tomewisp/client/ClientGuideRuntime.java \
  common/src/main/java/dev/tomewisp/client/ClientModelRuntimeRegistry.java \
  common/src/test/java/dev/tomewisp/capability \
  common/src/test/java/dev/tomewisp/client
git commit -m "feat: capture per-request client capabilities"
```

### Task 5: Add the trusted Knowledge and Capabilities catalog

**Files:**
- Create: `common/src/main/java/dev/tomewisp/capability/CapabilityKind.java`
- Create: `common/src/main/java/dev/tomewisp/capability/CapabilitySettingsDescriptor.java`
- Create: `common/src/main/java/dev/tomewisp/capability/CapabilitySettingsCatalog.java`
- Create: `common/src/main/java/dev/tomewisp/capability/CapabilitySettingsEntry.java`
- Create: `common/src/main/java/dev/tomewisp/capability/CapabilityChildPage.java`
- Create: `common/src/test/java/dev/tomewisp/capability/CapabilitySettingsCatalogTest.java`
- Modify: `common/src/main/java/dev/tomewisp/TomeWispBootstrap.java`

- [ ] **Step 1: Write registration authority, ordering, and child-route tests**

```java
@Test
void catalogsSourcesToolsAndSkillsWithRecipeAsToolChildPage() {
    List<CapabilitySettingsEntry> entries = catalog.snapshot(runtimeState()).entries();
    assertKinds(entries, KNOWLEDGE_SOURCE, TOOL, SKILL);
    CapabilitySettingsEntry recipes = entry(entries, "tomewisp:recipes");
    assertEquals(TOOL, recipes.kind());
    assertEquals(new CapabilityChildPage("tomewisp:recipe_settings"), recipes.childPage());
    assertFalse(entries.stream().anyMatch(entry -> entry.id().equals("top-level:recipes")));
}

@Test
void duplicateOrResourceAuthoredDescriptorFailsClosed() {
    assertThrows(IllegalStateException.class, () -> catalog.register(duplicate));
    assertFalse(CapabilitySettingsDescriptor.class
            .isAssignableFrom(ModelContent.class));
}
```

- [ ] **Step 2: Run catalog tests and verify missing types**

```bash
./gradlew :common:test --tests 'dev.tomewisp.capability.CapabilitySettingsCatalogTest'
```

- [ ] **Step 3: Implement code-owned descriptor registration and projection**

```java
public record CapabilitySettingsDescriptor(
        String id,
        CapabilityKind kind,
        String titleKey,
        String descriptionKey,
        CapabilityChildPage childPage) {}

public final class CapabilitySettingsCatalog {
    public synchronized void register(String ownerId,
                                      Collection<CapabilitySettingsDescriptor> descriptors);
    public CapabilityCatalogSnapshot snapshot(CapabilityCatalogState state);
}
```

Descriptors contain localization keys and typed route IDs only—no callbacks,
URLs, arbitrary paths, JSON schemas, Tools, or permissions. Bootstrap registers
known Tool/Skill/source descriptors; loader integrations may register only
their common recipe provider/navigator descriptor through existing bridges.

- [ ] **Step 4: Run tests and commit**

```bash
./gradlew :common:test --tests 'dev.tomewisp.capability.*' \
  --tests 'dev.tomewisp.architecture.*'
git add common/src/main/java/dev/tomewisp/capability \
  common/src/main/java/dev/tomewisp/TomeWispBootstrap.java \
  common/src/test/java/dev/tomewisp/capability
git commit -m "feat: catalog knowledge and Agent capabilities"
```

### Task 6: Integrate capability and recipe child actions into settings service

**Files:**
- Modify: `common/src/main/java/dev/tomewisp/settings/ClientSettingsService.java`
- Modify: `common/src/main/java/dev/tomewisp/settings/ClientSettingsSnapshot.java`
- Create: `common/src/main/java/dev/tomewisp/settings/capability/CapabilitySettingsView.java`
- Create: `common/src/main/java/dev/tomewisp/settings/capability/RecipeSettingsView.java`
- Modify: `common/src/test/java/dev/tomewisp/settings/ClientSettingsServiceTest.java`

- [ ] **Step 1: Add failing save/reload/independence tests**

Cover capability dependency rejection, atomic future-request publication,
recipe child save, source disappearance/reappearance, unavailable preferred
viewer, one operation slot, and no cross-file writes.

```java
@Test
void recipeChildSaveWritesOnlyRecipesDomain() {
    service.saveRecipeSettings(recipeCandidate()).join();
    assertEquals(1, files.writesTo("recipes.json"));
    assertEquals(0, files.writesTo("capabilities.json"));
    assertEquals(0, files.writesTo("models.json"));
}
```

- [ ] **Step 2: Run settings tests and observe missing actions/views**

```bash
./gradlew :common:test --tests 'dev.tomewisp.settings.*'
```

- [ ] **Step 3: Implement typed actions and snapshot projections**

```java
public CompletableFuture<ToolResult<Void>> saveCapabilities(CapabilityPolicy candidate);
public CompletableFuture<ToolResult<Void>> reloadCapabilities(boolean discardConfirmed);
public CompletableFuture<ToolResult<Void>> saveRecipeSettings(RecipeClientConfig candidate);
public CompletableFuture<ToolResult<Void>> reloadRecipeSettings(boolean discardConfirmed);
```

Prepare `ClientCapabilitySnapshot` before writing `capabilities.json`, atomically
write, then publish it through registry and settings snapshot. Prepare recipe
runtime config before writing, then replace `RecipeClientRuntime` only after the
move. Domain failure updates only its own diagnostic.

- [ ] **Step 4: Run settings/capability/recipe tests and commit**

```bash
./gradlew :common:test --tests 'dev.tomewisp.settings.*' \
  --tests 'dev.tomewisp.capability.*' \
  --tests 'dev.tomewisp.recipe.config.*'
git add common/src/main/java/dev/tomewisp/settings \
  common/src/test/java/dev/tomewisp/settings
git commit -m "feat: manage capability-owned settings"
```

### Task 7: Render Knowledge & Capabilities and recipe Tool child pages

**Files:**
- Modify: `common/src/main/java/dev/tomewisp/client/gui/TomeWispSettingsScreen.java`
- Create: `common/src/main/java/dev/tomewisp/client/gui/settings/CapabilitySettingsProjection.java`
- Create: `common/src/main/java/dev/tomewisp/client/gui/settings/RecipeSettingsProjection.java`
- Modify: `common/src/test/java/dev/tomewisp/client/gui/TomeWispSettingsScreenProjectionTest.java`
- Create: `common/src/test/java/dev/tomewisp/client/gui/settings/CapabilitySettingsProjectionTest.java`
- Create: `common/src/test/java/dev/tomewisp/client/gui/settings/RecipeSettingsProjectionTest.java`
- Modify: `common/src/main/resources/assets/tomewisp/lang/en_us.json`
- Modify: `common/src/main/resources/assets/tomewisp/lang/zh_cn.json`

- [ ] **Step 1: Write player-facing projection/navigation tests**

Test source/Tool/Skill filters, stable friendly ordering, dependency conflict
text, enabled/unavailable distinction, read-only server cards, no raw IDs in
normal mode, recipe child navigation, dynamically registered viewer rows,
unknown retained disabled sources, and absent EMI honesty.

```java
@Test
void recipeSettingsAreReachedThroughToolCardOnly() {
    projection.open(KNOWLEDGE_AND_CAPABILITIES);
    projection.activate("tomewisp:recipes");
    assertEquals(new CapabilityChildPage("tomewisp:recipe_settings"), projection.route());
    assertFalse(SettingsSection.topLevel().contains(RECIPES));
}
```

- [ ] **Step 2: Run settings GUI tests and verify missing projections**

```bash
./gradlew :common:test --tests 'dev.tomewisp.client.gui.*Settings*'
```

- [ ] **Step 3: Implement cards, toggles, child route, narration, and localization**

Normal cards show localized name/type, enabled/disabled/unavailable text,
installed integration name, and friendly diagnostics. Debug-only technical IDs
use the existing redacted debug projection. A toggle mutation edits the screen
draft; Save applies the complete domain candidate. Recipe source rows come from
registered snapshot entries rather than fixed JEI/REI/EMI widgets.

- [ ] **Step 4: Run UI/privacy/localization tests and commit**

```bash
./gradlew :common:test --tests 'dev.tomewisp.client.gui.*' \
  --tests 'dev.tomewisp.guide.ui.*' \
  --tests 'dev.tomewisp.capability.*'
git add common/src/main/java/dev/tomewisp/client/gui \
  common/src/test/java/dev/tomewisp/client/gui \
  common/src/main/resources/assets/tomewisp/lang
git commit -m "feat: render Knowledge and Capabilities settings"
```

### Task 8: Loader integration parity and full verification

**Files:**
- Modify: `fabric/src/main/java/dev/tomewisp/fabric/TomeWispFabricClient.java`
- Modify: `neoforge/src/main/java/dev/tomewisp/neoforge/TomeWispNeoForgeClient.java`
- Modify: `common/src/test/java/dev/tomewisp/client/ClientArchitectureTest.java`
- Modify: `README.md`
- Modify: `docs/development.md`
- Modify: `docs/isme/decisions/2026-07-18-017-capability-settings-policy.md`
- Modify: `docs/isme/SKMB.md`
- Modify: `docs/superpowers/plans/2026-07-18-phase-4h-knowledge-capability-settings.md`

- [ ] **Step 1: Prove both loaders supply the same policy/recipe paths and descriptors**

Extend architecture tests to assert `capabilities.json` and `recipes.json` are
passed to the common service in both entrypoints and loader integrations only
register stable source/navigator descriptors. Common code must still import no
loader APIs.

- [ ] **Step 2: Run the complete deterministic gate**

```bash
./gradlew clean :common:test :fabric:build :neoforge:build
git diff --check
```

Also run tracked shell/Python/JSON syntax checks, production-JAR credential
scans, and source-boundary assertions. Record actual common test totals and
both loader artifact SHA-256 values.

- [ ] **Step 3: Update truthful docs/status and commit**

Document the Knowledge & Capabilities hierarchy, deny-only local policy,
future-request capture, recipe child schema 2, unavailable-viewer behavior,
and which compatible viewers are actually implemented/installed. Do not claim
EMI merely because the schema supports its future stable ID.

```bash
git add fabric/src/main/java/dev/tomewisp/fabric/TomeWispFabricClient.java \
  neoforge/src/main/java/dev/tomewisp/neoforge/TomeWispNeoForgeClient.java \
  common/src/test/java/dev/tomewisp/client/ClientArchitectureTest.java \
  README.md docs/development.md docs/isme \
  docs/superpowers/plans/2026-07-18-phase-4h-knowledge-capability-settings.md
git commit -m "docs: verify Knowledge and Capabilities settings"
```

## Completion boundary

Phase 4H is complete when top-level settings enumerate registered knowledge
sources, Tools, and Skills; local deny policy is strict and captured for future
requests without widening authority; the recipe Tool owns generic stable-ID
source/viewer controls; both loaders remain in parity; and the full clean gate
passes. History administration and consolidated diagnostics/smoke remain
Phase 4I.
