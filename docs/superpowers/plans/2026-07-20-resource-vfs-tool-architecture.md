# Resource VFS Tool Architecture Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace OpenAllay's growing domain-Tool/result-JSON boundary with a request-scoped read-only Resource VFS in which game knowledge, generic mod resources, and completed Tool results are uniformly listable, readable, searchable, queryable, linkable, progressively projected, and natively presentable.

**Architecture:** Minecraft and loader integrations publish immutable evidence-bearing mount generations. Each request captures one authorized `ResourceView`. Five schema-validated VFS Tools operate over typed nodes and publish every completed result into an actor/session/connection-scoped `/result` mount before separate model, UI, and diagnostic projections are derived. Context assembly budgets semantic projections before every provider call; exact JSON remains internal truth and never becomes provider Tool text.

**Tech Stack:** Java 25, Gradle multi-loader build, Gson strict codecs, Java `HttpClient`, JUnit 5, Minecraft 26.2, Fabric, NeoForge, existing GuideService/model/bridge/history/native UI infrastructure.

---

## Preconditions and execution rules

- The accepted source of truth is
  `docs/isme/decisions/2026-07-20-026-resource-vfs-and-context-projection.md`.
- The companion design is
  `docs/superpowers/specs/2026-07-20-resource-vfs-tool-architecture-design.md`.
- Work in the numbered order. A task may be split into a temporary branch or
  delegated, but its public contracts and tests must land before a later task
  depends on them.
- Keep the existing domain Tools registered until Task 16. They provide a
  working comparison path while mounts and projections are introduced.
- Never expose an operating-system path, real shell, arbitrary URL, executable
  resource, live Minecraft object, credential, or unbounded binary content.
- Do not invent one global byte/result/history cap. Projection limits are
  derived from the selected model budget; lifecycle/retention decisions that
  need a numerical cap require operational evidence and a new SKMB entry.
- Run the focused command after every task. Run the full gate only at the
  integration milestones named below.

## Task 1: Establish canonical VFS value contracts

**Files:**

- Create: `common/src/main/java/dev/openallay/resource/vfs/ResourcePath.java`
- Create: `common/src/main/java/dev/openallay/resource/vfs/ResourceKind.java`
- Create: `common/src/main/java/dev/openallay/resource/vfs/ResourceValue.java`
- Create: `common/src/main/java/dev/openallay/resource/vfs/ResourceEntry.java`
- Create: `common/src/main/java/dev/openallay/resource/vfs/ResourceLink.java`
- Create: `common/src/main/java/dev/openallay/resource/vfs/ResourcePresentation.java`
- Create: `common/src/main/java/dev/openallay/resource/vfs/ResourceNode.java`
- Create: `common/src/test/java/dev/openallay/resource/vfs/ResourcePathTest.java`
- Create: `common/src/test/java/dev/openallay/resource/vfs/ResourceNodeTest.java`

- [ ] Write failing path tests for absolute paths, namespace/resource identity
  encoding, `%2F` canonicalization, reserved `@` children, round trips, stable
  comparison, and rejection of `..`, `.`, backslashes, controls, empty identity
  segments, invalid escapes, and unknown roots.
- [ ] Implement `ResourcePath` as decoded immutable segments plus one canonical
  renderer. Do not make it convertible to `java.nio.file.Path`.
- [ ] Write failing node tests proving defensive copies, mandatory evidence for
  factual nodes, stable child/link order, and closed presentation/kind enums.
- [ ] Implement the minimal immutable value tree: scalar, record, list, table,
  document, directory, binary-metadata, failure, and reference. Reuse existing
  `EvidenceMetadata`; do not introduce a competing evidence model.
- [ ] Add an architecture test assertion to
  `common/src/test/java/dev/openallay/context/ContextArchitectureTest.java`
  forbidding `java.nio.file` and loader imports from the VFS package.
- [ ] Run:
  `./gradlew :common:test --tests 'dev.openallay.resource.vfs.*' --tests 'dev.openallay.context.ContextArchitectureTest'`.
- [ ] Commit: `feat(resource): define canonical VFS values`

## Task 2: Implement mount registration and immutable generations

**Files:**

- Create: `common/src/main/java/dev/openallay/resource/vfs/ResourceMount.java`
- Create: `common/src/main/java/dev/openallay/resource/vfs/ResourceGeneration.java`
- Create: `common/src/main/java/dev/openallay/resource/vfs/ResourceSnapshot.java`
- Create: `common/src/main/java/dev/openallay/resource/vfs/ResourceMountRegistry.java`
- Create: `common/src/main/java/dev/openallay/resource/vfs/ResourceView.java`
- Create: `common/src/main/java/dev/openallay/resource/vfs/ResourceViewFactory.java`
- Create: `common/src/test/java/dev/openallay/resource/vfs/ResourceMountRegistryTest.java`
- Create: `common/src/test/java/dev/openallay/resource/vfs/ResourceViewTest.java`

- [ ] Write failing tests for unique mount roots, full-tree validation, atomic
  publication, prior-generation retention after a failed reload, reference
  ownership, and deterministic cross-mount link validation.
- [ ] Implement registry publication as one immutable generation swap. Retain
  old generations while any `ResourceView` owns them; never publish a partial
  tree.
- [ ] Write failing tests proving a view captures actor, session, request,
  connection generation, topology, mount generations, capabilities and
  cancellation without credentials or live objects.
- [ ] Implement view acquisition/release and explicit stale-generation failure.
- [ ] Run: `./gradlew :common:test --tests 'dev.openallay.resource.vfs.ResourceMountRegistryTest' --tests 'dev.openallay.resource.vfs.ResourceViewTest'`.
- [ ] Commit: `feat(resource): add immutable mount generations`

## Task 3: Capture generic per-mod raw resources on both loaders

**Files:**

- Create: `common/src/main/java/dev/openallay/resource/mod/ModResourceEntry.java`
- Create: `common/src/main/java/dev/openallay/resource/mod/ModResourceSnapshot.java`
- Create: `common/src/main/java/dev/openallay/resource/mod/ModRawMount.java`
- Modify: `common/src/main/java/dev/openallay/platform/PlatformService.java`
- Modify: `fabric/src/main/java/dev/openallay/fabric/FabricPlatformService.java`
- Modify: `neoforge/src/main/java/dev/openallay/neoforge/NeoForgePlatformService.java`
- Create: `common/src/test/java/dev/openallay/resource/mod/ModRawMountTest.java`
- Create: `common/src/test/java/dev/openallay/resource/mod/ModResourceSnapshotTest.java`

- [ ] Write failing tests for canonical paths beneath
  `/mod/<modid>/raw/assets`, `/data`, and `/metadata`; active/shadowed pack
  precedence; namespace ownership; duplicate logical resources; and provenance.
- [ ] Define a loader-neutral detached capture API returning logical resource
  identities, known public metadata, content kind, size/digest, precedence and
  lazy safe content access. The common API must contain no loader type or host
  path.
- [ ] Implement Fabric and NeoForge adapters using public loader/Minecraft
  resource APIs. Capture on the owning thread and detach before VFS workers use
  the data.
- [ ] Exclude `.class`, native libraries, signatures, credentials, unrelated
  private `META-INF`, physical JAR paths and unsupported binary payloads.
- [ ] Make UTF-8/text and known structured resources lazily readable/indexable
  per generation. Represent binary resources as metadata plus trusted native
  presentation reference only.
- [ ] Add both-loader parity assertions to
  `common/src/test/java/dev/openallay/context/ContextArchitectureTest.java`.
- [ ] Run focused common tests, then `./gradlew :fabric:compileJava :neoforge:compileJava`.
- [ ] Commit: `feat(resource): mount public mod resources`

## Task 4: Add the dynamic `/result` mount and lineage DAG

**Files:**

- Create: `common/src/main/java/dev/openallay/resource/result/ResourceResultId.java`
- Create: `common/src/main/java/dev/openallay/resource/result/ResourceResultLineage.java`
- Create: `common/src/main/java/dev/openallay/resource/result/ResourceResultRecord.java`
- Create: `common/src/main/java/dev/openallay/resource/result/ResourceResultMount.java`
- Create: `common/src/main/java/dev/openallay/resource/result/ResourceResultStore.java`
- Create: `common/src/test/java/dev/openallay/resource/result/ResourceResultStoreTest.java`
- Create: `common/src/test/java/dev/openallay/resource/result/ResourceResultLifecycleTest.java`

- [ ] Write failing tests proving validated publication precedes projection,
  each invocation receives an opaque public ID, equal immutable content may be
  deduplicated internally, and public invocation identity remains distinct.
- [ ] Record source paths, prior result paths, operation digest, evidence,
  invocation ID and content kind. Reject missing or cyclic/unpublished lineage.
- [ ] Scope lookup to actor, live session and connection. Release results on
  session deletion, disconnect and shutdown; reject late/cross-owner reads.
- [ ] Prove durable display receipts cannot resurrect `/result` resources or
  trigger implicit recomputation after restart.
- [ ] Run: `./gradlew :common:test --tests 'dev.openallay.resource.result.*'`.
- [ ] Commit: `feat(resource): publish Tool results as VFS nodes`

## Task 5: Build the shared VFS operation service

**Files:**

- Create: `common/src/main/java/dev/openallay/resource/vfs/ResourceFileSystem.java`
- Create: `common/src/main/java/dev/openallay/resource/vfs/ResourceDirectoryPage.java`
- Create: `common/src/main/java/dev/openallay/resource/vfs/ResourceReadRequest.java`
- Create: `common/src/main/java/dev/openallay/resource/vfs/ResourceGlobPattern.java`
- Create: `common/src/main/java/dev/openallay/resource/vfs/ResourceSearchIndex.java`
- Create: `common/src/main/java/dev/openallay/resource/vfs/ResourceOperationFailure.java`
- Create: `common/src/test/java/dev/openallay/resource/vfs/ResourceFileSystemTest.java`
- Create: `common/src/test/java/dev/openallay/resource/vfs/ResourceSearchIndexTest.java`

- [ ] Write failing tests for deterministic direct listing, batch exact reads,
  `*`/`?`/`**` globbing confined to one root, literal/token grep, requested
  fields, stable ordering, cancellation and partial mount degradation.
- [ ] Implement operations over `ResourceView`, never over mutable registry or
  live Minecraft state.
- [ ] Return structured failure codes with mount/field vocabulary. Do not
  fuzzily reinterpret invalid paths or patterns.
- [ ] Ensure the service treats `/result` and `/mod/.../raw` exactly like other
  authorized mounts.
- [ ] Run: `./gradlew :common:test --tests 'dev.openallay.resource.vfs.ResourceFileSystemTest' --tests 'dev.openallay.resource.vfs.ResourceSearchIndexTest'`.
- [ ] Commit: `feat(resource): add read list glob and grep services`

## Task 6: Port the typed query engine to VFS records

**Files:**

- Create: `common/src/main/java/dev/openallay/resource/query/ResourceQueryStage.java`
- Create: `common/src/main/java/dev/openallay/resource/query/ResourceQueryPlan.java`
- Create: `common/src/main/java/dev/openallay/resource/query/ResourceQueryEngine.java`
- Create: `common/src/main/java/dev/openallay/resource/query/ResourceFieldSchema.java`
- Create: `common/src/test/java/dev/openallay/resource/query/ResourceQueryEngineTest.java`
- Reference, then retire later: `common/src/main/java/dev/openallay/tool/query/QueryOperation.java`
- Reference, then retire later: `common/src/main/java/dev/openallay/tool/query/RegistryQueryEngine.java`

- [ ] Translate existing search/filter/select/expand/sort/group/aggregate/take
  behavior into immutable VFS-relative field stages.
- [ ] Add `Follow(relation)` with authority recheck and cycle detection by
  `(path, relation, generation)`.
- [ ] Discover field types and legal operations from `@schema`; do not add a
  nutrition/damage/duration field whitelist.
- [ ] Write tests using unknown mod fields, nested records, mixed types,
  missing values, batches, multiple roots, links and `/result` inputs.
- [ ] Assert input/output cardinality for every stage and deterministic failure
  guidance for unavailable fields.
- [ ] Run: `./gradlew :common:test --tests 'dev.openallay.resource.query.*' --tests 'dev.openallay.tool.query.*'`.
- [ ] Commit: `feat(resource): port typed queries to VFS`

## Task 7: Adapt registries and installed-mod metadata into typed mounts

**Files:**

- Create: `common/src/main/java/dev/openallay/resource/mount/RegistryResourceMount.java`
- Create: `common/src/main/java/dev/openallay/resource/mount/InstalledModResourceMount.java`
- Modify: `common/src/main/java/dev/openallay/context/minecraft/RegistryCatalogCapture.java`
- Modify: `common/src/main/java/dev/openallay/context/minecraft/RegistryPropertyContributor.java`
- Modify: `common/src/main/java/dev/openallay/platform/InstalledModMetadata.java`
- Create: `common/src/test/java/dev/openallay/resource/mount/RegistryResourceMountTest.java`
- Create: `common/src/test/java/dev/openallay/resource/mount/InstalledModResourceMountTest.java`

- [ ] Map items, blocks, effects, potions, entities and attributes to canonical
  paths with runtime-discovered fields, tags, components and public codecs.
- [ ] Link typed nodes to their raw origins where one exists.
- [ ] Keep installed mod count/details readable without requiring a Skill.
- [ ] Test fuzzy discovery through grep/glob but exact read through canonical
  path; ambiguity must return candidates, not choose one.
- [ ] Run: `./gradlew :common:test --tests 'dev.openallay.resource.mount.RegistryResourceMountTest' --tests 'dev.openallay.resource.mount.InstalledModResourceMountTest'`.
- [ ] Commit: `feat(resource): mount registries and mod metadata`

## Task 8: Adapt recipes, guides, knowledge and inventory into mounts

**Files:**

- Create: `common/src/main/java/dev/openallay/resource/mount/RecipeResourceMount.java`
- Create: `common/src/main/java/dev/openallay/resource/mount/GuideResourceMount.java`
- Create: `common/src/main/java/dev/openallay/resource/mount/KnowledgeResourceMount.java`
- Create: `common/src/main/java/dev/openallay/resource/mount/PlayerResourceMount.java`
- Modify: `common/src/main/java/dev/openallay/recipe/RecipeCatalog.java`
- Modify: `common/src/main/java/dev/openallay/knowledge/KnowledgeRegistry.java`
- Create: `common/src/test/java/dev/openallay/resource/mount/RecipeResourceMountTest.java`
- Create: `common/src/test/java/dev/openallay/resource/mount/GuideResourceMountTest.java`
- Create: `common/src/test/java/dev/openallay/resource/mount/PlayerResourceMountTest.java`

- [ ] Preserve stable source/generation recipe identities, ALL_KNOWN default,
  viewer provenance, partial source status, inputs/outputs/categories and native
  presentation hints.
- [ ] Link item inputs/outputs/usages, recipe categories, guide sections,
  citations, inventory slots and source documents.
- [ ] Preserve exact craftability inputs and evidence; do not ask the model to
  allocate overlapping inventory alternatives.
- [ ] Test optional JEI/REI/Patchouli/local/online source failure isolation.
- [ ] Run focused mount and existing recipe/knowledge tests.
- [ ] Commit: `feat(resource): mount recipes guides and player data`

## Task 9: Adapt observable game state into `/game`

**Files:**

- Create: `common/src/main/java/dev/openallay/resource/mount/GameStateResourceMount.java`
- Modify: `common/src/main/java/dev/openallay/context/game/ObservableGameStateSnapshot.java`
- Modify: `common/src/main/java/dev/openallay/client/context/ClientContextCapture.java`
- Create: `common/src/test/java/dev/openallay/resource/mount/GameStateResourceMountTest.java`
- Update: `common/src/test/java/dev/openallay/tool/builtin/InspectGameStateToolTest.java`

- [ ] Expose options, video/audio/controls/language/accessibility, active and
  available resource/data/shader packs, F3/HUD diagnostics, coordinates,
  direction, dimension, biome, topology and allowed read-only query state.
- [ ] Preserve per-field authority/completeness and independent degradation.
- [ ] Keep spatial world/container/block scanning outside `/game`; reserve
  `/world` for its later decision.
- [ ] Prove capture occurs on the owning thread and only detached immutable
  records enter the mount.
- [ ] Run focused game-state tests.
- [ ] Commit: `feat(resource): mount observable game state`

## Task 10: Expose the five model-facing VFS Tools

**Files:**

- Create: `common/src/main/java/dev/openallay/tool/resource/ResourceListTool.java`
- Create: `common/src/main/java/dev/openallay/tool/resource/ResourceReadTool.java`
- Create: `common/src/main/java/dev/openallay/tool/resource/ResourceGlobTool.java`
- Create: `common/src/main/java/dev/openallay/tool/resource/ResourceGrepTool.java`
- Create: `common/src/main/java/dev/openallay/tool/resource/ResourceQueryTool.java`
- Modify: `common/src/main/java/dev/openallay/agent/tool/ToolNameCodec.java`
- Modify: `common/src/main/java/dev/openallay/agent/tool/ToolRuntimeCatalog.java`
- Modify: `common/src/main/java/dev/openallay/tool/ToolRegistry.java`
- Create: `common/src/test/java/dev/openallay/tool/resource/ResourceToolsTest.java`
- Update: `common/src/test/java/dev/openallay/BuiltinToolRegistrationTest.java`

- [ ] Define small independent schemas with provider aliases `resource_list`,
  `resource_read`, `resource_glob`, `resource_grep`, `resource_query`.
- [ ] Support batch paths/roots in one call and correlate each item/root to its
  outcome. Avoid one model round trip per resource.
- [ ] Publish every success/failure envelope to `/result` after validation and
  before projection.
- [ ] Keep deterministic non-retrieval actions such as craftability narrow and
  VFS-reference-based.
- [ ] Run: `./gradlew :common:test --tests 'dev.openallay.tool.resource.*' --tests 'dev.openallay.BuiltinToolRegistrationTest'`.
- [ ] Commit: `feat(agent): expose Resource VFS tools`

## Task 11: Split exact truth from model, UI and diagnostic projections

**Files:**

- Modify: `common/src/main/java/dev/openallay/agent/tool/AgentToolResult.java`
- Create: `common/src/main/java/dev/openallay/agent/tool/ModelToolResultView.java`
- Create: `common/src/main/java/dev/openallay/agent/tool/ToolUiReference.java`
- Create: `common/src/main/java/dev/openallay/agent/tool/ToolResultDiagnostics.java`
- Create: `common/src/main/java/dev/openallay/resource/projection/ResourceModelProjector.java`
- Create: `common/src/main/java/dev/openallay/resource/projection/ResourceReceipt.java`
- Modify: `common/src/main/java/dev/openallay/agent/tool/LocalAgentToolExecutor.java`
- Create: `common/src/test/java/dev/openallay/resource/projection/ResourceModelProjectorTest.java`
- Update: `common/src/test/java/dev/openallay/agent/tool/LocalAgentToolExecutorTest.java`

- [ ] Make `truth`, `modelView`, `uiReference` and `diagnostics` explicit,
  immutable and independently testable.
- [ ] Render semantic line-oriented text by resource kind: records stay
  grouped, tables keep one header, documents keep headings/sections, failures
  keep stable code and correction, and receipts name the `/result` path.
- [ ] Prove model text contains all essential evidence fields but no raw JSON
  braces/quoting overhead, credentials or unbounded debug data.
- [ ] Prove UI and deterministic calculations receive exact truth, never parse
  model text, and unknown types retain a safe fallback.
- [ ] Run focused projection/executor tests.
- [ ] Commit: `refactor(agent): separate Tool result projections`

## Task 12: Send model text through both provider protocols

**Files:**

- Modify: `common/src/main/java/dev/openallay/model/ModelContent.java`
- Modify: `common/src/main/java/dev/openallay/model/openai/OpenAiJsonCodec.java`
- Modify: `common/src/main/java/dev/openallay/model/anthropic/AnthropicJsonCodec.java`
- Update: `common/src/test/java/dev/openallay/model/openai/OpenAiJsonCodecTest.java`
- Update: `common/src/test/java/dev/openallay/model/anthropic/AnthropicJsonCodecTest.java`
- Update: `common/src/test/java/dev/openallay/trace/replay/ToolCodecAndNormalizerTest.java`

- [ ] Change provider-neutral ToolResult content to explicit model text plus
  internal receipt identity. Keep exact JSON outside `ModelContent`.
- [ ] Prove OpenAI and Anthropic codecs preserve Tool call/result IDs and send
  the semantic text verbatim, never `gson.toJson(truth)`.
- [ ] Add malicious/large truth fixtures proving provider payload size follows
  model projection rather than normalized truth size.
- [ ] Preserve strict protocol parsing and redacted failure handling.
- [ ] Run focused codec and trace tests.
- [ ] Commit: `refactor(model): send semantic Tool result text`

## Task 13: Add semantic cursors and per-dispatch projection budgeting

**Files:**

- Create: `common/src/main/java/dev/openallay/resource/cursor/ResourceCursor.java`
- Create: `common/src/main/java/dev/openallay/resource/cursor/ResourceCursorStore.java`
- Create: `common/src/main/java/dev/openallay/resource/projection/ToolGroupBudgetAllocator.java`
- Create: `common/src/main/java/dev/openallay/agent/context/ContextAssembler.java`
- Modify: `common/src/main/java/dev/openallay/agent/context/ContextBudget.java`
- Modify: `common/src/main/java/dev/openallay/agent/context/ContextProjection.java`
- Create: `common/src/test/java/dev/openallay/resource/cursor/ResourceCursorStoreTest.java`
- Create: `common/src/test/java/dev/openallay/resource/projection/ToolGroupBudgetAllocatorTest.java`
- Create: `common/src/test/java/dev/openallay/agent/context/ContextAssemblerTest.java`

- [ ] Define cursors over semantic positions: child entry, record, row,
  document section or text line. Bind each token to actor/session/request/
  connection/view generation/query digest and expiry lifecycle.
- [ ] Allocate the current Tool group from the selected model input window after
  system prompt, disclosed Tools, protected structure, output reserve and
  minimum receipts are estimated.
- [ ] Preserve invocation order and give every parallel result at least a
  status/receipt before distributing remaining complete semantic units.
- [ ] If one unit cannot fit, return its manifest and an exact narrower read;
  never split JSON bytes or silently drop a result.
- [ ] Prove assembly runs before initial, Tool continuation, Skill continuation,
  summary and retry dispatches.
- [ ] Run focused cursor/projection/context tests.
- [ ] Commit: `feat(context): budget semantic Tool projections`

## Task 14: Replace historical JSON reduction with structural context editing

**Files:**

- Modify: `common/src/main/java/dev/openallay/agent/context/ContextCompactor.java`
- Replace: `common/src/main/java/dev/openallay/agent/context/ToolResultContextReducer.java`
- Replace: `common/src/main/java/dev/openallay/agent/context/ReducedToolResult.java`
- Modify: `common/src/main/java/dev/openallay/agent/context/ContextStructure.java`
- Update: `common/src/test/java/dev/openallay/agent/context/ContextCompactorTest.java`
- Replace: `common/src/test/java/dev/openallay/agent/context/ToolResultContextReducerTest.java`

- [ ] Preserve current ToolUse/ToolResult pairs and active factual results.
- [ ] Replace old model-facing bodies with compact receipts naming resource
  identities, evidence class, returned ranges and `/result` lineage; do not
  inspect arbitrary JSON keys to guess importance.
- [ ] Keep original durable chronology and exact live truth unchanged.
- [ ] Run conversation compaction only after Tool-result editing; keep summaries
  source-hashed, versioned, provider-neutral and explicitly non-evidence.
- [ ] Add a 100K-window deterministic regression with repeated large Tool turns
  and assert no provider request is emitted above the resolved budget.
- [ ] Run focused context tests.
- [ ] Commit: `refactor(context): edit historical results structurally`

## Task 15: Migrate the Agent loop and no-progress policy

**Files:**

- Modify: `common/src/main/java/dev/openallay/agent/GameGuideAgent.java`
- Modify: `common/src/main/java/dev/openallay/agent/tool/AgentToolExecutor.java`
- Modify: `common/src/main/java/dev/openallay/agent/tool/CompositeAgentToolExecutor.java`
- Update: `common/src/test/java/dev/openallay/agent/GameGuideAgentTest.java`
- Update: `common/src/test/java/dev/openallay/agent/tool/CompositeAgentToolExecutorTest.java`

- [ ] Capture one `ResourceView` per request and release it on every terminal,
  cancel, disconnect and shutdown path.
- [ ] Execute same-turn independent calls concurrently, publish each result,
  then continue in original ToolUse order.
- [ ] Track information delta by factual resource path, field/range and
  generation—not only Tool arguments or names.
- [ ] First no-progress repetition returns the existing receipt and synthesis
  guidance. A second no-progress attempt requests final synthesis instead of
  looping; retain all prior evidence.
- [ ] Prove a no-information result can be corrected once and that a valid
  exact result is not swallowed or immediately repeated.
- [ ] Run focused Agent/executor tests.
- [ ] Commit: `refactor(agent): navigate immutable Resource views`

## Task 16: Migrate client/server VFS routing and bridge payloads

**Files:**

- Modify: `common/src/main/java/dev/openallay/bridge/protocol/BridgeProtocol.java`
- Modify: `common/src/main/java/dev/openallay/bridge/protocol/BridgeJsonCodec.java`
- Modify: `common/src/main/java/dev/openallay/bridge/protocol/ResultChunker.java`
- Modify: `common/src/main/java/dev/openallay/bridge/client/ClientPlacedToolExecutor.java`
- Modify: `common/src/main/java/dev/openallay/bridge/server/PlayerClientToolRouter.java`
- Modify: `fabric/src/main/java/dev/openallay/fabric/network/FabricClientBridge.java`
- Modify: `fabric/src/main/java/dev/openallay/fabric/network/FabricServerBridge.java`
- Modify: `neoforge/src/main/java/dev/openallay/neoforge/network/NeoForgeClientBridge.java`
- Modify: `neoforge/src/main/java/dev/openallay/neoforge/network/NeoForgeServerBridge.java`
- Update: `common/src/test/java/dev/openallay/bridge/RemoteToolBridgeTest.java`
- Update: `common/src/test/java/dev/openallay/bridge/PlayerClientToolRouterTest.java`

- [ ] Version the bridge for VFS operation/view identity and exact validated
  result transfer. Preserve actor/request/invocation correlation.
- [ ] Chunk/hash-check exact internal truth; assemble model text only at the
  Agent owner after the complete result validates.
- [ ] Route server-model/client-resource and client-model/server-resource calls
  symmetrically. A link or result path never grants broader remote authority.
- [ ] Test cancellation, disconnect, late/malformed/duplicate chunks, sparse
  assembly, stale generations and cross-player result IDs.
- [ ] Run bridge tests and both loader compilation.
- [ ] Commit: `refactor(bridge): route Resource VFS operations`

## Task 17: Bind native UI directly to VFS truth

**Files:**

- Modify: `common/src/main/java/dev/openallay/guide/ui/GuideToolPresenter.java`
- Modify: `common/src/main/java/dev/openallay/guide/ui/GuideToolDetailPresenter.java`
- Modify: `common/src/main/java/dev/openallay/guide/ui/GuideRecipePresenter.java`
- Modify: `common/src/main/java/dev/openallay/client/gui/nativeview/NativeDomainViewBindings.java`
- Modify: `common/src/main/java/dev/openallay/guide/semantic/SemanticReferenceIndex.java`
- Update: `common/src/test/java/dev/openallay/guide/ui/GuideToolPresenterTest.java`
- Update: `common/src/test/java/dev/openallay/client/gui/nativeview/NativeDomainViewRegistryTest.java`

- [ ] Select presenters from resource kind/path/presentation hint and validated
  exact truth, not Tool ID plus parsed model prose.
- [ ] Keep recipe viewer/native recipe canvas, item, table, document, options,
  diagnostics and readable fallback presenters.
- [ ] Make Tool cards show operation, requested roots/paths, returned counts,
  key fields, source and continuation status. Debug may show bounded redacted
  schema/generation/lineage/timing, never full truth on the render thread.
- [ ] Prove selection identity updates the clicked card, virtualization remains
  bounded, and result/resources render without model-authored dynamic UI JSON.
- [ ] Run focused UI tests.
- [ ] Commit: `refactor(ui): present validated VFS resources`

## Task 18: Rewrite system guidance and progressively disclosed Skills

**Files:**

- Modify: `common/src/main/java/dev/openallay/agent/AgentSystemPrompt.java`
- Modify: `common/src/main/resources/assets/openallay/openallay_skills/analyze-game-data/SKILL.md`
- Modify: `common/src/main/resources/assets/openallay/openallay_skills/analyze-game-data/references/datasets.md`
- Modify: `common/src/main/resources/assets/openallay/openallay_skills/analyze-game-data/references/pipelines.md`
- Modify: `common/src/main/resources/assets/openallay/openallay_skills/analyze-game-data/references/examples.md`
- Modify: `common/src/main/resources/assets/openallay/openallay_skills/answer-modded-minecraft-question/SKILL.md`
- Modify: `common/src/main/resources/assets/openallay/openallay_skills/diagnose-missing-recipe/SKILL.md`
- Modify: `common/src/main/resources/assets/openallay/openallay_skills/search-guide-books/SKILL.md`
- Update: `common/src/test/java/dev/openallay/agent/AgentSystemPromptTest.java`
- Update: `common/src/test/java/dev/openallay/skill/BundledSkillsTest.java`

- [ ] Keep the base prompt short: inspect before guessing, use exact paths,
  batch independent work, discover `@schema`, follow links, treat receipts as
  continuations, use `/result` for refinement, and synthesize when evidence is
  sufficient.
- [ ] Do not require a Skill for simple installed-mod or exact-resource reads.
  Require relevant Skill loading for complex domain workflows, then teach
  field discovery and result-on-result pipelines progressively with examples.
- [ ] Include examples for poison resources, maximum saturation, missing recipe,
  guide retrieval, unknown mod raw data and mixed client/server resources.
- [ ] Assert Skills cannot execute, add Tools, grant mounts or escape declared
  read-only references.
- [ ] Run focused prompt/Skill tests.
- [ ] Commit: `docs(agent): teach Resource VFS workflows`

## Task 19: Remove the advertised legacy retrieval surface

**Files:**

- Modify: `common/src/main/java/dev/openallay/OpenAllay.java`
- Modify: `common/src/main/java/dev/openallay/agent/tool/ToolRuntimeCatalog.java`
- Delete or internalize as adapters: `common/src/main/java/dev/openallay/tool/builtin/ResolveResourceTool.java`
- Delete or internalize as adapters: `common/src/main/java/dev/openallay/tool/builtin/SearchRecipesTool.java`
- Delete or internalize as adapters: `common/src/main/java/dev/openallay/tool/builtin/GetRecipeTool.java`
- Delete or internalize as adapters: `common/src/main/java/dev/openallay/tool/builtin/FindItemUsagesTool.java`
- Delete or internalize as adapters: `common/src/main/java/dev/openallay/tool/builtin/InspectInventoryTool.java`
- Delete or internalize as adapters: `common/src/main/java/dev/openallay/tool/builtin/InspectGameStateTool.java`
- Delete or internalize as adapters: `common/src/main/java/dev/openallay/tool/builtin/SearchKnowledgeTool.java`
- Delete or internalize as adapters: `common/src/main/java/dev/openallay/tool/builtin/GetKnowledgeDocumentTool.java`
- Modify: `common/src/main/java/dev/openallay/guide/GuideToolMessageCodec.java`
- Update: `common/src/test/java/dev/openallay/BuiltinToolRegistrationTest.java`
- Update: `common/src/test/java/dev/openallay/guide/history/GuideHistoryCodecTest.java`

- [x] Confirm every legacy read workflow has an equivalent VFS path/operation
  and every native presenter consumes the new truth/reference.
- [x] Remove every legacy retrieval ID from registration and the advertised
  model catalog. New calls use only the Resource VFS family.
- [x] Preserve display-only decoders for existing pre-release history; never
  re-execute old calls or advertise them to the model.
- [x] Delete the obsolete domain query surface
  only after no production call site remains.
- [x] Run: `./gradlew :common:test`.
- [ ] Commit: `refactor(agent): retire domain retrieval Tools`

## Task 20: Migrate traces, E2E fixtures, history projections and diagnostics

**Files:**

- Modify fixtures under: `common/src/main/resources/data/openallay/agent_traces/`
- Modify: `common/src/main/java/dev/openallay/trace/replay/AgentTraceReplayer.java`
- Modify: `common/src/main/java/dev/openallay/guide/history/GuideHistoryCodec.java`
- Modify: `common/src/main/java/dev/openallay/devmode/DevelopmentToolInspector.java`
- Update: `common/src/test/java/dev/openallay/trace/replay/AgentTraceReplayerTest.java`
- Update: `common/src/test/java/dev/openallay/guide/GuideProductE2ETest.java`
- Update: `common/src/test/java/dev/openallay/guide/history/GuideHistoryCodecTest.java`

- [ ] Re-record deterministic traces with VFS aliases, semantic model text,
  `/result` receipts, exact internal truth and native UI references.
- [ ] Keep normal history to friendly cards and chronology. Exclude exact truth,
  live views, cursors and capabilities from durable rows.
- [ ] Add redacted metrics for mount generations, paths/records scanned,
  result lineage, exact/model sizes, projection budget, cursor use, historical
  edits, compaction and provider-reported token usage.
- [ ] Prove Debug inspection paginates and cannot freeze the client by laying
  out complete truth.
- [ ] Run trace, history and E2E deterministic tests.
- [ ] Commit: `test(agent): migrate VFS traces and history`

## Task 21: Complete deterministic acceptance and both-loader gates

**Files:**

- Create: `common/src/test/java/dev/openallay/guide/ResourceVfsProductE2ETest.java`
- Update: `common/src/test/java/dev/openallay/server/ServerClientToolAgentTest.java`
- Update: `common/src/test/java/dev/openallay/model/live/LiveModelAcceptanceTest.java`
- Update: `scripts/live-model-smoke.sh`
- Update: `scripts/run-real-client-e2e.sh`

- [ ] Add deterministic acceptance for installed mod list/details, apple cider
  exact recipe/native reference, poison-related content plus acquisition links,
  Farmer's Delight highest saturation via discovered schema, video/packs/F3,
  large guide section continuation, unknown mod raw text, and result-on-result
  grep/query.
- [ ] Add a long multi-turn 100K-window fixture proving every provider dispatch
  fits, old Tool bodies become receipts, and exact current evidence survives.
- [ ] Run: `./gradlew clean :common:test :fabric:build :neoforge:build`.
- [ ] Inspect `git diff --check`, tracked fixture sizes, credential scans and
  loader parity before any live request.
- [ ] Commit: `test(agent): verify Resource VFS architecture`

## Task 22: Perform opt-in live and real-client acceptance

**Files:**

- Create retained redacted report under: `docs/verification/`
- Update: `docs/development.md`
- Update: `README.md` only where player-visible capabilities/status changed

- [ ] Use explicit environment/profile credential references; never put the
  user-provided key or private endpoint in repository files, command output,
  screenshots, reports or commits.
- [ ] Run multiple live tasks with the selected 100K model: poison acquisition,
  highest-saturation analysis, unknown mod raw search, long result refinement,
  and a multi-turn follow-up using prior `/result` resources.
- [ ] Run the real Fabric client E2E only after deterministic gates pass. Verify
  Tool cards, dynamic recipe UI, bounded Debug details, stable scrolling and
  cancellation. Repeat NeoForge behavior through deterministic parity and a
  real client when practical.
- [ ] Retain redacted provider/model ID, artifact versions, mod hashes, commands,
  timestamps, outcomes and screenshots only when a graphical run actually
  occurred.
- [ ] If any acceptance fails, return to the owning task; do not add another
  output limiter or special result reader.
- [ ] Commit: `docs(verification): retain Resource VFS acceptance`

## Task 23: Final documentation and release readiness review

**Files:**

- Modify: `docs/development.md`
- Modify: `docs/isme/SKMB.md`
- Modify: `docs/superpowers/README.md`
- Modify: `README.md` only for honest player-facing capability/roadmap status

- [ ] Document mount/path semantics, generic mod raw exclusions, `/result`
  lifecycle, Tool schemas, Skill progression, context layers, failure codes,
  observability and client/server placement.
- [ ] Confirm SKMB-026 transitions/invariants/failures match implemented code and
  tests. Record any newly discovered state-machine decision before selecting an
  implementation default.
- [ ] Confirm the withdrawn emergency 0.1.1 bounded-result design remains absent
  and is not cited as authority.
- [ ] Run the full gate once more only if code changed after Task 21; otherwise
  rely on the retained exact commit SHA and CI truth.
- [ ] Open/update issue #8 with implementation and verification links only after
  the documents exist on the remote.
- [ ] Do not publish a release until CI, deterministic acceptance and the
  retained opt-in evidence satisfy the companion design's acceptance criteria.
- [ ] Commit: `docs(agent): finalize Resource VFS architecture`

## Completion checklist

- [ ] The model catalog exposes the five VFS retrieval Tools plus only genuinely
  non-resource deterministic actions.
- [ ] Every successful Tool invocation publishes a composable `/result` node.
- [ ] Every installed mod has a safe generic raw logical-resource subtree.
- [ ] Unknown fields are discoverable through `@schema` without core hard-code.
- [ ] Exact JSON remains internal truth; provider Tool results are semantic text.
- [ ] Every provider dispatch is budgeted against the selected model window.
- [ ] Large data is narrowed through semantic cursors and `/result` pipelines.
- [ ] UI is driven by exact resources and stays bounded on the render thread.
- [ ] Client/server placement, authority, cancellation and both loaders remain
  in parity.
- [ ] Deterministic, build, live-provider and real-client claims are backed by
  their applicable retained evidence.
