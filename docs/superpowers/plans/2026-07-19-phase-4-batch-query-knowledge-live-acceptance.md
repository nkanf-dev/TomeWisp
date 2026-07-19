# Phase 4 Batch Query, Knowledge, and Live Acceptance Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make broad and multi-turn Minecraft questions complete reliably through ordered parallel Tools, typed bulk queries, fixed documentation search, accurate provider diagnostics, and real DeepSeek acceptance.

**Architecture:** Keep the Agent loop provider-neutral and the Tool count small. Add batch/query behavior to existing domain Tools over immutable snapshots, keep external documentation behind fixed adapters using the shared JDK transport, and retry only transport failures proven to have emitted no response progress.

**Tech Stack:** Java 25, CompletableFuture, Gson strict Tool schemas, JDK HttpClient transport, Minecraft 26.2 data components, JUnit 5, Fabric and NeoForge.

---

### Task 1: Provider error classification and safe transport recovery

**Files:**
- Modify: `common/src/main/java/dev/openallay/model/http/ModelHttpErrors.java`
- Modify: `common/src/main/java/dev/openallay/model/http/HttpModelTransport.java`
- Modify: `common/src/main/java/dev/openallay/model/scheduling/ModelRequestScheduler.java`
- Test: `common/src/test/java/dev/openallay/model/http/ModelHttpErrorsTest.java`
- Test: `common/src/test/java/dev/openallay/model/scheduling/ModelRequestSchedulerTest.java`

- [ ] Add failing tests for bounded JSON 400 classification, body redaction, pre-progress TLS/reset retry, post-progress no-retry, cancellation, and exhaustion.
- [ ] Implement an allowlisted `ModelHttpErrorDetail` parser that never retains a raw body and maps context/protocol/request rejection to stable codes.
- [ ] Track per-attempt progress in the scheduler and retry `model_transport_error` at most twice only before progress.
- [ ] Run `./gradlew :common:test --tests 'dev.openallay.model.http.*' --tests 'dev.openallay.model.scheduling.*'` and expect all tests to pass.

### Task 2: Ordered parallel Tool groups

**Files:**
- Modify: `common/src/main/java/dev/openallay/agent/GameGuideAgent.java`
- Test: `common/src/test/java/dev/openallay/agent/GameGuideAgentTest.java`
- Test: `common/src/test/java/dev/openallay/agent/context/ContextStructureTest.java`

- [ ] Add tests whose Tools finish in reverse order and assert concurrent start, correlated UI completion, original-order provider results, and valid context units.
- [ ] Add cancellation and same-turn duplicate tests that prove no partial continuation and one physical execution.
- [ ] Replace the serial future chain with indexed futures plus `CompletableFuture.allOf`, keeping trace and result publication correlation stable.
- [ ] Run the focused Agent/context suite and expect all tests to pass.

### Task 3: Rich item capture and typed virtual query

**Files:**
- Modify: `common/src/main/java/dev/openallay/context/minecraft/RegistryCatalogCapture.java`
- Modify: `common/src/main/java/dev/openallay/tool/builtin/ResolveResourceTool.java`
- Create: `common/src/main/java/dev/openallay/tool/query/QueryOperation.java`
- Create: `common/src/main/java/dev/openallay/tool/query/RegistryQueryEngine.java`
- Test: `common/src/test/java/dev/openallay/tool/builtin/ResolveResourceCatalogTest.java`
- Test: `common/src/test/java/dev/openallay/context/minecraft/RegistryCatalogCaptureTest.java`

- [ ] Add fixture tests for nutrition/saturation capture and namespace/filter/sort/select/group/aggregate/take pipelines.
- [ ] Define enum-backed operation, comparator, direction, and aggregate records with optional fields validated per operation.
- [ ] Capture `DataComponents.FOOD` nutrition, saturation, and always-eat values as stable metadata.
- [ ] Implement the pipeline over detached `RegistryEntrySnapshot` rows with per-stage counts and exact schema diagnostics.
- [ ] Return `result_too_large` when an unprojected normalized result exceeds the observed safe payload boundary.
- [ ] Run the catalog/query tests and expect all tests to pass.

### Task 4: Batch recipe search

**Files:**
- Modify: `common/src/main/java/dev/openallay/tool/builtin/SearchRecipesTool.java`
- Modify: `common/src/main/java/dev/openallay/guide/GuideToolPresentation.java`
- Test: `common/src/test/java/dev/openallay/tool/builtin/RecipeQueryToolsTest.java`
- Test: `common/src/test/java/dev/openallay/guide/GuideToolPresentationTest.java`

- [ ] Add tests for one query, multiple correlated queries, partial catalog evidence, malformed empty batches, and player-friendly batch cards.
- [ ] Extract the existing exact criteria into `Query`, accept one `query` or `queries`, and return indexed `QueryResult` values.
- [ ] Preserve the single-query JSON projection and deprecated `find_recipes` compatibility without encouraging it in descriptions.
- [ ] Run the focused recipe and presentation suites and expect all tests to pass.

### Task 5: Fixed online knowledge adapters

**Files:**
- Modify: `common/src/main/java/dev/openallay/tool/Tool.java`
- Modify: `common/src/main/java/dev/openallay/agent/tool/LocalAgentToolExecutor.java`
- Modify: `common/src/main/java/dev/openallay/tool/builtin/SearchKnowledgeTool.java`
- Create: `common/src/main/java/dev/openallay/knowledge/online/OnlineKnowledgeSource.java`
- Create: `common/src/main/java/dev/openallay/knowledge/online/MinecraftWikiKnowledgeSource.java`
- Create: `common/src/main/java/dev/openallay/knowledge/online/McmodKnowledgeSource.java`
- Test: `common/src/test/java/dev/openallay/tool/builtin/KnowledgeToolsTest.java`
- Test: `common/src/test/java/dev/openallay/knowledge/online/OnlineKnowledgeSourceTest.java`

- [ ] Add fake-transport tests for local+online merge, fixed-origin requests, cancellation, timeout, malformed response, independent degradation, and cache reuse.
- [ ] Add a default async Tool invocation method and make the local executor use it without blocking a model worker.
- [ ] Implement the MediaWiki JSON adapter and fixed MC百科 HTML result parser using the shared HTTP transport.
- [ ] Merge source-scoped hits deterministically and return partial public-documentation evidence plus diagnostics.
- [ ] Run knowledge/executor tests and expect all tests to pass.

### Task 6: Skill guidance and client-visible location correction

**Files:**
- Modify: `common/src/main/java/dev/openallay/agent/AgentSystemPrompt.java`
- Modify: `common/src/main/java/dev/openallay/tool/builtin/InspectGameStateTool.java`
- Modify: `common/src/main/resources/assets/openallay/openallay_skills/inspect-game-state/SKILL.md`
- Create: `common/src/main/resources/assets/openallay/openallay_skills/analyze-game-data/SKILL.md`
- Create: `common/src/main/resources/assets/openallay/openallay_skills/analyze-game-data/references/datasets.md`
- Create: `common/src/main/resources/assets/openallay/openallay_skills/analyze-game-data/references/pipelines.md`
- Create: `common/src/main/resources/assets/openallay/openallay_skills/analyze-game-data/references/examples.md`
- Test: `common/src/test/java/dev/openallay/skill/BundledSkillsTest.java`
- Test: `common/src/test/java/dev/openallay/tool/builtin/InspectGameStateToolTest.java`

- [ ] Add tests for Skill discovery/references, biome routing guidance, batch prompt guidance, and a recoverable mistaken world-query alias.
- [ ] Document datasets, operations, field types, and worked ranking/grouping/batch examples with progressive disclosure.
- [ ] Make biome/coordinate/dimension/direction aliases direct the model to `DIAGNOSTICS category:position` without permission misclassification.
- [ ] Run Skill/game-state tests and expect all tests to pass.

### Task 7: Real DeepSeek acceptance and release gate

**Files:**
- Modify: `common/src/test/java/dev/openallay/model/live/LiveConfiguredGameStateAcceptanceTest.java`
- Create: `common/src/test/java/dev/openallay/model/live/LivePhase4LongTaskAcceptanceTest.java`
- Modify: `scripts/live-model-smoke.sh`
- Create: `docs/verification/phase-4-live-deepseek/README.md`
- Modify: `docs/development.md`

- [ ] Add an environment-gated multi-scenario live harness that records only redacted metrics and terminal outcomes.
- [ ] Run the ordinary, biome, bulk recipe, food ranking, poison workflow, online knowledge, and multi-turn scenarios against `deepseek-v4-pro`.
- [ ] Fix every reproducible Tool/Skill/knowledge gap and rerun until every required scenario terminates with a grounded answer.
- [ ] Run `./gradlew clean :common:test :fabric:build :neoforge:build`, package checks, script checks, `git diff --check`, and a credential scan.
- [ ] Record exact test/build/live evidence without keys, endpoints, reasoning, or raw provider bodies; then commit and push `main` and execute the authorized GitHub/Modrinth release workflow.
