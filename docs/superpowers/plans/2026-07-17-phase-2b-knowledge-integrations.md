# Phase 2B Knowledge Integrations Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add normalized knowledge providers, deterministic search, complete Patchouli asset extraction, optional FTB Quests compatibility, and the bundled guide Skills used by the live Agent.

**Architecture:** Providers build immutable document snapshots and diagnostics. A deterministic index ranks exact IDs, associated items, titles, namespaces, and body terms without embeddings. Patchouli is parsed from active client assets; FTB Quests sits behind a validated optional method-handle mapping and never becomes a 26.2 hard dependency.

**Tech Stack:** Java 25, Gson, Minecraft client resource APIs, MethodHandles, JUnit fixtures, Fabric/NeoForge loader resource adapters.

---

### Task 1: Knowledge source contracts and immutable registry

**Files:**
- Create: `common/src/main/java/dev/tomewisp/knowledge/KnowledgeKind.java`
- Create: `common/src/main/java/dev/tomewisp/knowledge/KnowledgeDocument.java`
- Create: `common/src/main/java/dev/tomewisp/knowledge/KnowledgeDiagnostic.java`
- Create: `common/src/main/java/dev/tomewisp/knowledge/KnowledgeSnapshot.java`
- Create: `common/src/main/java/dev/tomewisp/knowledge/KnowledgeSourceProvider.java`
- Create: `common/src/main/java/dev/tomewisp/knowledge/KnowledgeRegistry.java`
- Test: `common/src/test/java/dev/tomewisp/knowledge/KnowledgeRegistryTest.java`

- [ ] **Step 1: Write duplicate, atomic reload, and provenance tests**

```java
@Test void failedProviderDoesNotReplaceLastGoodSnapshot() {
    registry.reload(List.of(goodProvider));
    registry.reload(List.of(failingProvider));
    assertEquals("first", registry.snapshot().documents().getFirst().title());
    assertEquals("provider_failure", registry.diagnostics().getFirst().code());
}
```

- [ ] **Step 2: Run and verify failure**

Run: `./gradlew-curl :common:test --tests 'dev.tomewisp.knowledge.KnowledgeRegistryTest' --max-workers=1`

Expected: compilation failure.

- [ ] **Step 3: Implement immutable contracts and atomic replacement**

```java
public record KnowledgeDocument(String sourceId, String documentId,
        KnowledgeKind kind, String title, String body, String namespace,
        Set<String> itemIds, Set<String> recipeIds,
        String structureRef, boolean visible, String provenance) {}
```

Reject duplicate `(sourceId, documentId)` values and retain provider diagnostics.

- [ ] **Step 4: Run tests and commit**

Run: `./gradlew-curl :common:test --tests 'dev.tomewisp.knowledge.*' --max-workers=1`

```bash
git add common/src/main/java/dev/tomewisp/knowledge common/src/test/java/dev/tomewisp/knowledge
git commit -m "feat: add normalized knowledge source registry"
```

### Task 2: Deterministic search and knowledge tools

**Files:**
- Create: `common/src/main/java/dev/tomewisp/knowledge/search/KnowledgeTokenizer.java`
- Create: `common/src/main/java/dev/tomewisp/knowledge/search/KnowledgeIndex.java`
- Create: `common/src/main/java/dev/tomewisp/knowledge/search/KnowledgeSearchResult.java`
- Create: `common/src/main/java/dev/tomewisp/tool/builtin/ListKnowledgeSourcesTool.java`
- Create: `common/src/main/java/dev/tomewisp/tool/builtin/SearchKnowledgeTool.java`
- Create: `common/src/main/java/dev/tomewisp/tool/builtin/GetKnowledgeDocumentTool.java`
- Test: `common/src/test/java/dev/tomewisp/knowledge/search/KnowledgeIndexTest.java`

- [ ] **Step 1: Write ranking, Chinese token, ID, item, and stable tie tests**

Assert exact document ID outranks title, associated item outranks body-only, all
matches are returned when `limit` is absent, an explicit positive limit is
honored, and equal scores sort by source/document ID.

- [ ] **Step 2: Run and verify failure**

Run: `./gradlew-curl :common:test --tests 'dev.tomewisp.knowledge.search.*' --max-workers=1`

Expected: compilation failure.

- [ ] **Step 3: Implement index and tools**

Use lowercase Unicode code-point tokenization, resource-ID segmentation, term
frequency, deterministic integer weights, and full provenance in every result.
Register all three tools as read-only built-ins.

- [ ] **Step 4: Run tests and commit**

Run: `./gradlew-curl :common:test --tests 'dev.tomewisp.knowledge.*' --tests 'dev.tomewisp.tool.builtin.*' --max-workers=1`

```bash
git add common/src/main/java/dev/tomewisp/knowledge common/src/main/java/dev/tomewisp/tool common/src/test/java
git commit -m "feat: search grounded guide knowledge"
```

### Task 3: Client asset resource abstraction

**Files:**
- Create: `common/src/main/java/dev/tomewisp/client/resource/ClientResourceAccess.java`
- Create: `common/src/main/java/dev/tomewisp/client/resource/ClientResource.java`
- Create: `common/src/main/java/dev/tomewisp/client/resource/MinecraftClientResourceAccess.java`
- Test: `common/src/test/java/dev/tomewisp/client/resource/ClientResourceAccessTest.java`

- [ ] **Step 1: Write pack-stack and locale resource tests**

Use in-memory resources with two packs for the same path. Assert highest-priority
selection and complete provenance while rejecting traversal and non-asset paths.

- [ ] **Step 2: Implement resource access**

Expose listing under a validated prefix, opening UTF-8 readers, pack ID, resource
ID, and priority. The Minecraft implementation wraps the active client resource
manager and never holds a reload-era `Resource` after snapshot creation.

- [ ] **Step 3: Compile and commit**

Run: `./gradlew-curl :common:test --tests 'dev.tomewisp.client.resource.*' :fabric:compileJava :neoforge:compileJava --max-workers=1`

```bash
git add common/src/main/java/dev/tomewisp/client/resource common/src/test/java/dev/tomewisp/client/resource
git commit -m "feat: expose safe client asset resources"
```

### Task 4: Patchouli book and multiblock extraction

**Files:**
- Create: `common/src/main/java/dev/tomewisp/integration/patchouli/PatchouliKnowledgeProvider.java`
- Create: `common/src/main/java/dev/tomewisp/integration/patchouli/PatchouliBookParser.java`
- Create: `common/src/main/java/dev/tomewisp/integration/patchouli/PatchouliTextNormalizer.java`
- Create: `common/src/main/java/dev/tomewisp/integration/patchouli/PatchouliMultiblock.java`
- Create: `common/src/main/java/dev/tomewisp/integration/patchouli/PatchouliDiagnostics.java`
- Test resources: `common/src/test/resources/patchouli-fixture/assets/example/patchouli_books/machines/**`
- Test: `common/src/test/java/dev/tomewisp/integration/patchouli/PatchouliKnowledgeProviderTest.java`

- [ ] **Step 1: Add official-shape fixtures and failing assertions**

Fixtures include `zh_cn`, active-locale, and `en_us` entries; text, spotlight,
crafting, relations, dense multiblock, sparse multiblock, config-gated, and
malformed pages. Assert complete body, item/recipe IDs, locale precedence,
structure coordinates, and diagnostics.

- [ ] **Step 2: Run and verify failure**

Run: `./gradlew-curl :common:test --tests 'dev.tomewisp.integration.patchouli.*' --max-workers=1`

Expected: compilation failure.

- [ ] **Step 3: Implement parser without a Patchouli binary dependency**

Scan `assets/*/patchouli_books/<book>/<locale>/entries/**/*.json`, normalize page
types and formatting commands, preserve unknown page JSON in provenance details,
and exclude content whose visibility cannot be established.

- [ ] **Step 4: Run tests and commit**

Run: `./gradlew-curl :common:test --tests 'dev.tomewisp.integration.patchouli.*' --max-workers=1`

```bash
git add common/src/main/java/dev/tomewisp/integration/patchouli common/src/test/java/dev/tomewisp/integration/patchouli common/src/test/resources/patchouli-fixture
git commit -m "feat: index Patchouli guide assets"
```

### Task 5: Optional Patchouli opening and multiblock API bridge

**Files:**
- Create: `common/src/main/java/dev/tomewisp/integration/patchouli/PatchouliRuntimeBridge.java`
- Create: `common/src/main/java/dev/tomewisp/integration/patchouli/ReflectivePatchouliBridge.java`
- Create: `common/src/main/java/dev/tomewisp/tool/builtin/GetPatchouliMultiblockTool.java`
- Test: `common/src/test/java/dev/tomewisp/integration/patchouli/ReflectivePatchouliBridgeTest.java`

- [ ] **Step 1: Write compatible, absent, and mismatched-shape tests**

Fixture classes expose `PatchouliAPI.get()`, `isStub()`, `getMultiblock`, and
open methods. Assert the bridge validates exact parameter/return types, never
uses `setAccessible`, and returns `integration_unavailable` for a mismatch.

- [ ] **Step 2: Implement cached MethodHandles**

Resolve only the allowlisted public methods once, keep opening as a client UI
action outside the model's read-only registry, and expose multiblock reading as
a read tool.

- [ ] **Step 3: Test and commit**

Run: `./gradlew-curl :common:test --tests 'dev.tomewisp.integration.patchouli.*' --max-workers=1`

```bash
git add common/src/main/java/dev/tomewisp/integration/patchouli common/src/test/java/dev/tomewisp/integration/patchouli common/src/main/java/dev/tomewisp/tool/builtin
git commit -m "feat: bridge optional Patchouli runtime"
```

### Task 6: Optional FTB Quests compatibility adapter

**Files:**
- Create: `common/src/main/java/dev/tomewisp/integration/ftb/quests/FtbQuestsBridge.java`
- Create: `common/src/main/java/dev/tomewisp/integration/ftb/quests/FtbQuestsMapping.java`
- Create: `common/src/main/java/dev/tomewisp/integration/ftb/quests/ReflectiveFtbQuestsBridge.java`
- Create: `common/src/main/java/dev/tomewisp/integration/ftb/quests/FtbQuestSnapshot.java`
- Create: `common/src/main/java/dev/tomewisp/integration/ftb/quests/FtbQuestsKnowledgeProvider.java`
- Create: `common/src/main/java/dev/tomewisp/tool/builtin/SearchFtbQuestsTool.java`
- Create: `common/src/main/java/dev/tomewisp/tool/builtin/GetNextFtbQuestsTool.java`
- Test fixtures: `common/src/test/java/dev/ftb/mods/ftbquests/**`
- Test: `common/src/test/java/dev/tomewisp/integration/ftb/quests/ReflectiveFtbQuestsBridgeTest.java`

- [ ] **Step 1: Add official-shape fixture graph and visibility tests**

Build chapters, visible/hidden quests, dependencies, completed/claimed state,
tasks, and rewards. Assert hidden descriptions never enter snapshots and next
quests require all visible dependencies complete.

- [ ] **Step 2: Run and verify failure**

Run: `./gradlew-curl :common:test --tests 'dev.tomewisp.integration.ftb.quests.*' --max-workers=1`

Expected: compilation failure.

- [ ] **Step 3: Implement allowlisted mapping and provider**

Resolve exact public methods with `MethodHandles.publicLookup()`. The mapping
returns detached Java records immediately. Missing mod, class, method, or type
returns a diagnostic and no tool registration; it never falls back to field
access or private reflection.

- [ ] **Step 4: Test and commit**

Run: `./gradlew-curl :common:test --tests 'dev.tomewisp.integration.ftb.quests.*' --max-workers=1`

```bash
git add common/src/main/java/dev/tomewisp/integration/ftb common/src/test/java/dev/tomewisp/integration/ftb common/src/test/java/dev/ftb common/src/main/java/dev/tomewisp/tool/builtin
git commit -m "feat: read optional visible FTB quests"
```

### Task 7: Bundled guide Skills and prompt integration

**Files:**
- Create: `common/src/main/resources/assets/tomewisp/tomewisp_skills/answer-modded-minecraft-question/skill.md`
- Create: `common/src/main/resources/assets/tomewisp/tomewisp_skills/explain-machine-usage/skill.md`
- Create: `common/src/main/resources/assets/tomewisp/tomewisp_skills/diagnose-missing-recipe/skill.md`
- Create: `common/src/main/resources/assets/tomewisp/tomewisp_skills/guide-ftb-progression/skill.md`
- Create: `common/src/main/resources/assets/tomewisp/tomewisp_skills/search-guide-books/skill.md`
- Create: `common/src/test/java/dev/tomewisp/skill/BundledSkillsTest.java`

- [ ] **Step 1: Write a test that loads every bundled Skill**

Assert unique names, descriptions, existing allowed tools, no scripts, no
unresolved references, and explicit grounding/unavailable rules.

- [ ] **Step 2: Add complete Skill documents**

Each Skill defines trigger conditions, ordered tool workflow, evidence policy,
failure behavior, and answer structure. `guide-ftb-progression` states it is
unavailable without visible quest data; no Skill claims a Ponder write tool.

- [ ] **Step 3: Test and commit**

Run: `./gradlew-curl :common:test --tests dev.tomewisp.skill.BundledSkillsTest --max-workers=1`

```bash
git add common/src/main/resources/assets/tomewisp/tomewisp_skills common/src/test/java/dev/tomewisp/skill/BundledSkillsTest.java
git commit -m "feat: bundle grounded guide skills"
```

### Task 8: Knowledge reload and diagnostics wiring

**Files:**
- Modify: `common/src/main/java/dev/tomewisp/client/ClientGuideRuntime.java`
- Modify: `fabric/src/main/java/dev/tomewisp/fabric/TomeWispFabricClient.java`
- Modify: `neoforge/src/main/java/dev/tomewisp/neoforge/TomeWispNeoForgeClient.java`
- Modify: `fabric/src/main/java/dev/tomewisp/fabric/FabricGuideCommands.java`
- Modify: `neoforge/src/main/java/dev/tomewisp/neoforge/NeoForgeGuideCommands.java`
- Test: `common/src/test/java/dev/tomewisp/knowledge/KnowledgeReloadIntegrationTest.java`

- [ ] **Step 1: Write atomic reload integration test**

Simulate initial resources, a reload with changed Patchouli text, and a failed
FTB mapping. Assert in-flight requests retain the old snapshot while new
requests see the new one and diagnostics show the FTB failure.

- [ ] **Step 2: Register reload lifecycle and commands**

`/guide sources` lists counts and diagnostics; `/guide skills` lists compatible
metadata. Reload builds Skills and knowledge off-thread where safe, then swaps
them on the client thread.

- [ ] **Step 3: Full verification and commit**

Run: `./gradlew-curl :common:test :fabric:build :neoforge:build --max-workers=1`

```bash
git add common fabric neoforge
git commit -m "feat: reload client guide knowledge atomically"
```
