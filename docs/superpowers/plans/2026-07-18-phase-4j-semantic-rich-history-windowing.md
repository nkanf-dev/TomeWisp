# Phase 4J Semantic Rich Messages and Windowed History Implementation Plan

> Execute sequentially with deterministic checkpoints. This is the final Phase
> 4 implementation package; graphical/live acceptance remains the last task.

**Goal:** Replace assistant plain text and full-partition history loading with a
safe Minecraft semantic document model, registered controlled components,
incremental durable commits, model-budgeted context reads, viewport paging, and
native virtualized rendering on Fabric and NeoForge.

**Architecture:** CommonMark is only a parser adapter; TomeWisp owns a strict
sealed AST, reference authority index, component registry, codec, and native
renderer. SQLite schema 4 is a clean pre-release replacement with incremental
upserts and independent metadata/page/context queries. `GuideService` remains
the connection-scoped owner and publishes generation-checked windows; the GUI
owns viewport/focus/scroll state only.

**Dependency:** `org.commonmark:commonmark:0.28.0` and
`org.commonmark:commonmark-ext-gfm-tables:0.28.0`, bundled identically by both
loaders. The official project describes the core as small, dependency-free,
Java 11+, and AST-oriented; TomeWisp converts it immediately into product types.

**Decision:** `docs/isme/decisions/2026-07-18-018-semantic-history-windowing.md`

---

### Task 1: Add the closed semantic AST and CommonMark adapter

**Files:**
- Modify: `gradle.properties`
- Modify: `common/build.gradle`
- Modify: `fabric/build.gradle`
- Modify: `neoforge/build.gradle`
- Create: `common/src/main/java/dev/tomewisp/guide/semantic/SemanticDocument.java`
- Create: `common/src/main/java/dev/tomewisp/guide/semantic/SemanticBlock.java`
- Create: `common/src/main/java/dev/tomewisp/guide/semantic/SemanticInline.java`
- Create: `common/src/main/java/dev/tomewisp/guide/semantic/SemanticStyle.java`
- Create: `common/src/main/java/dev/tomewisp/guide/semantic/SemanticDiagnostic.java`
- Create: `common/src/main/java/dev/tomewisp/guide/semantic/SemanticMessageParser.java`
- Create: `common/src/main/java/dev/tomewisp/guide/semantic/SemanticStreamingState.java`
- Test: `common/src/test/java/dev/tomewisp/guide/semantic/SemanticMessageParserTest.java`
- Test: `common/src/test/java/dev/tomewisp/guide/semantic/SemanticStreamingStateTest.java`
- Test: `common/src/test/java/dev/tomewisp/guide/semantic/SemanticDependencyPackagingTest.java`

- [x] **Step 1: Write parser safety and supported-node tests**

Cover paragraphs, headings, ordered/unordered lists, emphasis/strong,
blockquote, table, inline/fenced code, hard/soft breaks, literal HTML, links,
images, malformed delimiters, deeply nested content, Unicode, and empty text.
HTML/link destinations/images must never become actionable nodes. Every
document must produce the exact plain-text/narration fallback.

- [x] **Step 2: Add and package the parser dependencies symmetrically**

Pin one `commonmark_version` property. Compile against both artifacts in common;
Fabric `include`s and NeoForge `jarJar`s both. Add JAR-content tests so a build
cannot compile and then omit the parser at runtime. Record BSD-2-Clause in the
existing dependency/license documentation if present.

- [x] **Step 3: Implement the sealed product AST and adapter**

Translate only supported CommonMark nodes. Do not expose `org.commonmark` types
outside the adapter. Unknown/unsafe nodes collapse to literal text. Defensive
copies, node-depth validation, stable node IDs derived from block position and
content hash, and a non-empty fallback are mandatory.

- [x] **Step 4: Implement block-incremental streaming state**

Split completed block prefixes from the mutable tail, cache completed blocks by
hash, and reparse only the tail. Incomplete fenced code, emphasis, table,
semantic token, or component block remains literal. Tests must assert object
identity reuse for unchanged completed blocks and no stale content after a
reconciled `FinalText`.

- [x] **Step 5: Run focused tests and commit**

```bash
./gradlew :common:test --tests 'dev.tomewisp.guide.semantic.*' \
  :fabric:build :neoforge:build
git commit -m "feat: parse safe semantic guide messages"
```

### Task 2: Validate semantic references and controlled components

**Files:**
- Create: `common/src/main/java/dev/tomewisp/guide/semantic/SemanticReference.java`
- Create: `common/src/main/java/dev/tomewisp/guide/semantic/SemanticReferenceKind.java`
- Create: `common/src/main/java/dev/tomewisp/guide/semantic/SemanticReferenceIndex.java`
- Create: `common/src/main/java/dev/tomewisp/guide/semantic/SemanticReferenceValidator.java`
- Create: `common/src/main/java/dev/tomewisp/guide/semantic/RichComponent.java`
- Create: `common/src/main/java/dev/tomewisp/guide/semantic/RichComponentEnvelope.java`
- Create: `common/src/main/java/dev/tomewisp/guide/semantic/RichComponentRegistry.java`
- Create: `common/src/main/java/dev/tomewisp/guide/semantic/BuiltinRichComponents.java`
- Create: `common/src/main/java/dev/tomewisp/guide/semantic/SemanticDocumentValidator.java`
- Test: `common/src/test/java/dev/tomewisp/guide/semantic/SemanticReferenceValidatorTest.java`
- Test: `common/src/test/java/dev/tomewisp/guide/semantic/RichComponentRegistryTest.java`
- Test: `common/src/test/java/dev/tomewisp/guide/semantic/SemanticAuthorityTest.java`

- [x] **Step 1: Write closed-schema and authority tests**

Test all eleven inline kinds and all eight component kinds. Reject unknown
keys, versions, types, actions, Java names, scripts, commands, URLs, texture or
filesystem paths, NBT selectors, oversized numeric values, duplicate node IDs,
foreign-request handles, stale recipe generations, and unregistered source or
evidence IDs. Fuzz malformed JSON/token boundaries and assert readable fallback.

- [x] **Step 2: Build the same-request reference index**

Derive immutable handles only from typed `GuideToolActivity` normalized results
and `GuideSource` records. Index resource IDs separately as ungrounded display
references. Never infer a stable handle from arbitrary matching prose.

- [x] **Step 3: Register strict built-in component records**

Implement item row, recipe grid, ingredient check, craftability summary,
progress steps, source summary, status badge, and bounded choice group as
sealed records. Each codec has an exact key set, schema version 1, allowlisted
display properties, typed intent declarations, and mandatory fallback/narration.
The model cannot register new definitions.

- [x] **Step 4: Validate full documents in request context and commit**

```bash
./gradlew :common:test --tests 'dev.tomewisp.guide.semantic.*'
git commit -m "feat: validate controlled guide components"
```

### Task 3: Put semantic documents into the Agent timeline and prompt

**Files:**
- Modify: `common/src/main/java/dev/tomewisp/guide/GuideTimelineEntry.java`
- Modify: `common/src/main/java/dev/tomewisp/guide/GuideStateReducer.java`
- Modify: `common/src/main/java/dev/tomewisp/client/ClientGuideRuntime.java`
- Modify: `common/src/main/java/dev/tomewisp/server/ServerGuideRuntime.java`
- Modify: `common/src/main/java/dev/tomewisp/client/gui/TomeWispScreen.java`
- Create: `common/src/main/java/dev/tomewisp/guide/semantic/SemanticDocumentCodec.java`
- Modify: `common/src/main/java/dev/tomewisp/guide/history/GuideHistoryCodec.java`
- Modify: `common/src/main/java/dev/tomewisp/guide/ui/GuideUiRow.java`
- Modify: `common/src/main/java/dev/tomewisp/guide/ui/GuideUiView.java`
- Test: `common/src/test/java/dev/tomewisp/guide/GuideStateReducerTest.java`
- Test: `common/src/test/java/dev/tomewisp/guide/GuideServiceTest.java`
- Test: `common/src/test/java/dev/tomewisp/guide/semantic/SemanticDocumentCodecTest.java`
- Test: `common/src/test/java/dev/tomewisp/guide/ui/GuideUiViewTest.java`

- [x] **Step 1: Write streaming, final reconciliation, and privacy tests**

Assert text-before-tool and text-after-tool use independent parser states;
tool results extend only the later segment's reference index; repeated tool
names remain invocation-correlated; final reconciliation changes only the last
segment. Reasoning and raw provider content must remain unrepresentable.

- [x] **Step 2: Add semantic syntax guidance to the system prompt**

Teach the two closed syntaxes, prefer handles returned by tools, explain raw
resource IDs are presentation-only, require fallback text, and prohibit HTML,
URLs, code/actions, arbitrary components, or claims that presentation creates
evidence. Do not force components when ordinary prose is clearer.

- [x] **Step 3: Parse/validate the mutable timeline tail**

Assistant entries retain exact model source text plus a safe fallback-bearing
`SemanticDocument`. The
reducer carries streaming parse state internally, while immutable snapshots
publish only safe semantic content and redacted diagnostics. Completed segments
are stable across later tool updates.

- [x] **Step 4: Add strict semantic persistence codec**

Encode only TomeWisp AST version 1, exact node/component keys, validated stable
references, and fallback text. Unknown version/key fails the containing history
record closed. No CommonMark classes or transient layout/cache data are stored.

- [x] **Step 5: Run timeline/semantic tests and commit**

```bash
./gradlew :common:test --tests 'dev.tomewisp.guide.*' \
  --tests 'dev.tomewisp.guide.semantic.*'
git commit -m "feat: persist semantic agent timeline"
```

### Task 4: Replace full-partition persistence with schema 4 incremental commits

**Files:**
- Modify: `common/src/main/java/dev/tomewisp/guide/history/GuideHistoryPartition.java`
- Replace/modify: `common/src/main/java/dev/tomewisp/guide/history/GuideHistoryStore.java`
- Replace/modify: `common/src/main/java/dev/tomewisp/guide/history/GuideHistoryAccess.java`
- Create: `common/src/main/java/dev/tomewisp/guide/history/GuideHistoryCommit.java`
- Create: `common/src/main/java/dev/tomewisp/guide/history/GuideHistoryMutation.java`
- Create: `common/src/main/java/dev/tomewisp/guide/history/GuideHistoryMetadata.java`
- Create: `common/src/main/java/dev/tomewisp/guide/history/GuideHistoryPageRequest.java`
- Create: `common/src/main/java/dev/tomewisp/guide/history/GuideHistoryPage.java`
- Create: `common/src/main/java/dev/tomewisp/guide/history/GuideHistoryCursor.java`
- Create: `common/src/main/java/dev/tomewisp/guide/history/GuideHistoryContextRequest.java`
- Create: `common/src/main/java/dev/tomewisp/guide/history/GuideHistoryContextSeed.java`
- Modify: `common/src/main/java/dev/tomewisp/guide/history/SqliteGuideHistoryStore.java`
- Modify: `common/src/main/java/dev/tomewisp/guide/history/GuideHistoryRepository.java`
- Test: `common/src/test/java/dev/tomewisp/guide/history/SqliteGuideHistoryStoreTest.java`
- Test: `common/src/test/java/dev/tomewisp/guide/history/GuideHistoryRepositoryTest.java`

- [x] **Step 1: Write schema/replacement/incremental SQL tests first**

Assert fresh schema version 4, strict unsupported v1/v2/v3/v99 failure without
mutation, explicit reset recreation, stable request sequence, cascades,
interrupted recovery, and transaction rollback injection. Prove updating one
tool entry does not delete/reinsert unrelated requests. Delete old schema-3-only
production code rather than adding migration branches.

- [x] **Step 2: Define typed commits and independent read contracts**

A commit contains only partition metadata/session/request/message/timeline/
checkpoint upserts or explicit scoped deletes. Page requests use exclusive
sequence/ordinal cursors plus a positive caller count. Context requests carry
the actual selected-model budget/reservations, not a page count or guessed
default.

- [x] **Step 3: Implement normalized schema 4 and transaction batches**

Use foreign keys, stable natural/UUID keys, monotonic per-session request
sequence, indexed page order, strict semantic JSON columns, and one transaction
per ordered commit. Preserve hashed scope/actor isolation and no raw world/
server path. Never save credentials, reasoning, provider bodies, full inventory,
or transient render state.

- [x] **Step 4: Implement metadata/page/context reads**

Metadata reads no request/timeline bodies. Pages fetch exactly the requested
neighborhood and cursors. Context reads stream newest structurally complete
messages/tool pairs and validated checkpoints until the supplied token budget,
without constructing the full partition. Add query-plan/index assertions and a
large deterministic fixture proving bounded returned objects.

- [x] **Step 5: Keep ordered async/deletion semantics and commit**

Repository commits, metadata, page, context, delete/reset, flush, and close all
share the existing single ordered worker and reservation rules. Page/context
reads expose cancellation/generation results but cannot bypass deletion gates.

```bash
./gradlew :common:test --tests 'dev.tomewisp.guide.history.*'
git commit -m "feat: page incremental guide history"
```

### Task 5: Make GuideService own history windows and budgeted context preparation

**Files:**
- Modify: `common/src/main/java/dev/tomewisp/guide/GuideService.java`
- Modify: `common/src/main/java/dev/tomewisp/guide/GuideServiceManager.java`
- Modify: `common/src/main/java/dev/tomewisp/guide/GuideSessionSnapshot.java`
- Modify: `common/src/main/java/dev/tomewisp/guide/GuideSnapshot.java`
- Create: `common/src/main/java/dev/tomewisp/guide/GuideHistoryWindowSnapshot.java`
- Create: `common/src/main/java/dev/tomewisp/guide/GuideHistoryPageState.java`
- Modify: `common/src/main/java/dev/tomewisp/guide/GuideLocalEndpoint.java`
- Modify: `common/src/main/java/dev/tomewisp/client/ClientModelRuntimeRegistry.java`
- Modify: `common/src/main/java/dev/tomewisp/client/ClientGuideRuntime.java`
- Modify: `common/src/main/java/dev/tomewisp/agent/session/AgentSessionStore.java`
- Modify: `common/src/main/java/dev/tomewisp/guide/GuideRemoteEndpoint.java`
- Modify: common bridge capability/request protocol and both loader codecs
- Test: `common/src/test/java/dev/tomewisp/guide/GuideServiceHistoryWindowTest.java`
- Test: `common/src/test/java/dev/tomewisp/guide/GuideServiceContextLoadTest.java`
- Test: `common/src/test/java/dev/tomewisp/client/ClientModelRuntimeRegistryTest.java`
- Test: common/fabric/neoforge bridge protocol tests

- [x] **Step 1: Write metadata-only startup and page-race tests**

On restart, sessions/counts load but request bodies do not. Test first/earlier/
later viewport reads, duplicate coalescing, superseded viewport, session switch,
disconnect, actor/scope replacement, delete/reset, page failure/retry, active
entry pinning, and stale late completion suppression.

- [x] **Step 2: Write model-context preparation tests**

Before provider dispatch, load a seed under the actual selected profile budget.
Test model/provider switch recalculation, valid checkpoint reuse, stale hash,
missing/invalid server budget, cancellation during context load, context failure,
one provider dispatch only, no GUI dependency, and no summary-as-evidence.

- [x] **Step 3: Publish window metadata in immutable snapshots**

Separate total request counts/cursors/page status from loaded request rows.
`GuideService.requestHistoryWindow(...)` is the only UI entrypoint. It owns one
active page read per session generation and returns completions through the
client dispatcher. Commands/settings continue to use the same service snapshot.

- [x] **Step 4: Replace full saves with event-specific commits**

Project each accepted state transition into the minimum durable batch. Commit
terminal interruption/cancellation before clearing. Deletion/reset invalidates
page and context generations before SQL begins so late reads cannot resurrect
or reveal removed rows.

- [x] **Step 5: Prepare provider-neutral context asynchronously**

Add a selected-topology context-budget contract. Local profiles expose their
resolved `ContextBudget`; server capability protocol advertises its budget.
GuideService enters `context_loading`, waits only on async repository work,
hydrates/replaces the bounded Agent session seed, then captures current game
context and dispatches. Cancellation stops before dispatch. Protocol decoding
remains strict and loaders do not infer limits.

- [x] **Step 6: Bound live in-memory Agent context**

After a successful durable checkpoint/commit, discard summarized source
messages from the runtime seed while the database retains originals. In
non-durable mode, retain only the valid summary plus recent verbatim tail after
compaction. Never prune a current tool pair or before checkpoint success.

- [x] **Step 7: Run service/protocol/race tests and commit**

```bash
./gradlew :common:test --tests 'dev.tomewisp.guide.*' \
  --tests 'dev.tomewisp.client.*' \
  --tests 'dev.tomewisp.bridge.*' \
  :fabric:build :neoforge:build
git commit -m "feat: window durable guide sessions"
```

### Task 6: Render semantic content with viewport virtualization

**Files:**
- Create: `common/src/main/java/dev/tomewisp/guide/ui/SemanticLayout.java`
- Create: `common/src/main/java/dev/tomewisp/guide/ui/SemanticLayoutCache.java`
- Create: `common/src/main/java/dev/tomewisp/guide/ui/GuideTranscriptVirtualizer.java`
- Create: `common/src/main/java/dev/tomewisp/guide/ui/GuideViewportAnchor.java`
- Create: `common/src/main/java/dev/tomewisp/client/gui/MinecraftSemanticResolver.java`
- Create: `common/src/main/java/dev/tomewisp/client/gui/MinecraftSemanticRenderer.java`
- Modify: `common/src/main/java/dev/tomewisp/client/gui/TomeWispScreen.java`
- Modify: `common/src/main/java/dev/tomewisp/guide/ui/GuideUiRow.java`
- Modify: `common/src/main/java/dev/tomewisp/guide/ui/GuideUiView.java`
- Modify: `common/src/main/java/dev/tomewisp/recipe/viewer/RecipeViewerNavigatorRegistry.java`
- Modify: English and Simplified Chinese language files
- Test: `common/src/test/java/dev/tomewisp/guide/ui/GuideTranscriptVirtualizerTest.java`
- Test: `common/src/test/java/dev/tomewisp/guide/ui/SemanticLayoutTest.java`
- Test: `common/src/test/java/dev/tomewisp/client/gui/MinecraftSemanticRendererTest.java`
- Test: existing `TomeWispScreen`/layout/accessibility tests

- [x] **Step 1: Write viewport, anchor, and auto-scroll tests**

Use thousands of variable-height rows. Assert binary-search visibility,
measurement cache reuse, one-row invalidation, active-tail updates, earlier-page
anchor preservation, player-owned scroll while above bottom, follow-at-bottom,
session reset, and no heavy render state outside the neighborhood.

- [x] **Step 2: Write native semantic rendering tests**

Cover headings/lists/quotes/tables/code, icon/name/count/tooltip for resolved
items/blocks/fluids, unknown resource fallback, recipe/source/evidence handles,
all component kinds, typed recipe/usage/viewer intents, malformed fallback,
keyboard focus, narration, and color-independent state labels.

- [x] **Step 3: Implement pure layout and virtualization**

Cache wrapping/height by stable semantic row ID, content hash, width, locale,
font identity, and display settings. Maintain prefix offsets with targeted
invalidation. Ask GuideService for a viewport-derived page when approaching an
unloaded cursor; never read SQLite or hold live registries in the pure view.

- [x] **Step 4: Implement Minecraft-thread resolver and renderer**

Resolve raw resource IDs only on the client thread into detached presentation
records. Render native icons/tooltips/rarity/glint and component slots without
retaining live registry/level objects. Dispatch only typed existing intents.
Unknown or failed native rendering uses exact fallback text/narration.

- [x] **Step 5: Integrate scroll/focus/narration and commit**

Preserve scissor bounds, responsive narrow/wide layout, session overlay,
details, composer, and model/settings controls. Tool entries still update in
place. Focus keys remain stable across page merges; screen close detaches only.

```bash
./gradlew :common:test --tests 'dev.tomewisp.guide.ui.*' \
  --tests 'dev.tomewisp.client.gui.*'
git commit -m "feat: virtualize semantic guide rendering"
```

### Task 7: Add implemented presentation controls and diagnostics

**Files:**
- Modify: `common/src/main/java/dev/tomewisp/guide/ui/GuideDisplayConfig.java`
- Modify: display config loader/writer/runtime
- Modify: General settings projection and `TomeWispSettingsScreen`
- Modify: diagnostics aggregation/snapshot/projection
- Modify: English and Simplified Chinese language files
- Modify: display/settings tests
- Modify: documentation and SKMB decision 010/016/018 references

- [x] **Step 1: Replace display schema cleanly and test strict failure**

Use current pre-release schema 2 with `debugMode` and `animationsEnabled` only.
Do not migrate schema 1. Missing file defaults to debug off/animations on;
explicit old/unknown schema fails closed and retains the last valid runtime.

- [x] **Step 2: Apply animation as presentation only**

The setting may control ingredient-alternative cycling or subtle progress
presentation, but never component state, action availability, evidence, layout
identity, or narration. Reopening rebuilds the same semantic document.

- [x] **Step 3: Add friendly and debug performance diagnostics**

Normal mode may say history is loaded on demand and whether the current page is
loading/failed. Debug mode may show redacted loaded/total row counts, page
cursors as counts (not raw IDs), cache hit/miss counts, semantic fallback count,
and context-token estimates. No transcript, provider body, path, actor, raw
scope, or component payload.

- [x] **Step 4: Run settings/localization/privacy tests and commit**

```bash
./gradlew :common:test --tests 'dev.tomewisp.settings.*' \
  --tests 'dev.tomewisp.client.gui.*' \
  --tests 'dev.tomewisp.guide.ui.*'
git commit -m "feat: configure semantic guide presentation"
```

### Task 8: Loader parity, dependency/package audit, and deterministic scale gate

**Files:**
- Modify: Fabric and NeoForge client entrypoints only if new common adapters need wiring
- Modify: `common/src/test/java/dev/tomewisp/client/ClientArchitectureTest.java`
- Modify/create: dependency packaging verification script
- Modify: README, development guide, decisions, SKMB, this plan
- Create: `docs/verification/phase-4j-semantic-history/README.md`

- [x] **Step 1: Assert common ownership and both-loader parity**

Both loaders must supply the same display/settings/history/semantic runtime,
context-budget protocol, lifecycle close order, and bundled CommonMark modules.
Common imports no loader APIs. Viewer availability differences remain explicit.

- [x] **Step 2: Run deterministic scale/performance tests**

Create a retained generated fixture with at least tens of thousands of timeline
rows and mixed semantic blocks. Assert metadata-only startup materializes zero
request bodies, viewport reads return only the requested neighborhood, context
reads stay within supplied budget, one timeline update touches only its rows,
virtualization measures/renders only the neighborhood, and anchor/auto-scroll
semantics hold. Record elapsed time and object/query counts as evidence, not as
new hard product caps.

- [x] **Step 3: Run the clean product/security/package gate**

```bash
./gradlew clean :common:test :fabric:build :neoforge:build
git diff --check
bash -n scripts/*.sh
python3 -m py_compile scripts/*.py
```

Validate JSON, schema-4 absence of migration code, SQLite and CommonMark
packaging on both JARs, native targets, generic credential patterns, test
totals, and artifact SHA-256 values.

- [x] **Step 4: Update deterministic evidence and commit**

```bash
git commit -m "docs: verify semantic windowed history"
```

### Task 9: Consolidated Phase 4 real-client and final acceptance

**Files:**
- Modify: deterministic loopback fixture and real-client E2E controller as needed
- Modify: `scripts/run-real-client-e2e.sh`
- Create: `docs/verification/phase-4-final-acceptance/` reports/screenshots/manifests
- Modify: README, development guide, Phase 4 plans, decisions, and SKMB

- [x] **Step 1: Extend the deterministic graphical scenario**

The loopback stream must split Markdown/semantic tokens across deltas, call
recipe and knowledge tools, return a stable handle, render prose → tool → rich
continuation → tool → final segment, include one malformed component fallback,
and leave a long paged history. Retain redacted semantic/component/page/cache
assertions in the report; never retain provider body or credentials.

- [x] **Step 2: Preflight real mods and both loader profiles**

Use every actually compatible installed viewer practical per loader, Patchouli
when a verified 26.2 artifact exists or the honestly labeled resource-book
fallback otherwise, and Farmer's Delight/retained compatible recipe-rich
sample. Record source URLs, versions, SHA-256, loader/game/mod versions. Do not
claim EMI/Patchouli through generic code when no compatible artifact exists.

- [x] **Step 3: Run Fabric and NeoForge graphical smoke**

With the Mac unlocked, use only the approved harness and local deterministic
provider. Verify all-known locked recipe discovery, provenance merge, item
icons/tooltips, exact viewer navigation, Patchouli/resource knowledge, semantic
streaming/fallback, controlled components, keyboard/narration, animation off,
wide/narrow layout, settings taxonomy, history paging/anchor, restart recovery,
interrupted request, deletion/reset confirmations, and secret redaction.

- [x] **Step 4: Run an optional real provider probe only when env is present**

If `TOMEWISP_API_KEY` is already exported into the launched process, run exactly
the approved isolated settings probe and retain only redacted category/latency.
Never reconstruct a key from conversation or put it in argv, files, logs, or
screenshots. If absent, record the probe as not run.

- [x] **Step 5: Audit every Phase 4 completion claim**

Map Phase 4A–K and the consolidated completion bullets to deterministic tests,
retained graphical evidence, or an explicit honest unavailable/deferred note.
Ensure structure-to-Ponder, recursive technology planning, old ports, and
formal release remain deferred. No unshipped migration paths or arbitrary
history caps remain.

Steps 1–5 completed in the consolidated acceptance run. Fabric completed with
JEI, REI, and Farmer's Delight Refabricated. NeoForge completed with JEI and
Cooking for Blockheads on NeoForge 26.2.0.25-beta; REI was removed from the
accepted NeoForge profile after its current artifact displayed an upstream
`@OnlyIn` loading warning. EMI and a Patchouli 26.2 runtime remained
unavailable, so the Patchouli claim is limited to the retained resource-parser
fixture and knowledge-source evidence. A 50-request seed run followed by a
graphical restart proved metadata-only recovery (`loadedRequests=1`,
`totalRequests=51`, `hasEarlier=1`).

The final environment did not contain `TOMEWISP_API_KEY`, so Step 4 made no
provider request. The real-client harness never reconstructed the previously
supplied conversational credential. The combined acceptance uses real-client
reports for loader/runtime/tool/semantic/history integration, retained Phase 4C
screenshots for exact JEI navigation, and deterministic tests for destructive
settings actions, wide/narrow layout, keyboard/narration, cancellation,
interruption, and confirmation semantics. It does not mislabel those
deterministic assertions as manual clicks. The complete mapping is retained in
`docs/verification/phase-4-final-acceptance/README.md`.

- [x] **Step 6: Run final clean gate, update all plans/status, and commit**

```bash
./gradlew clean :common:test :fabric:build :neoforge:build
git diff --check
git commit -m "docs: complete phase 4 acceptance"
```

The from-clean gate completed with 427 common tests, zero failures, zero
errors, two explicit opt-in skips, and successful Fabric/NeoForge production
builds. Shell/Python syntax, tracked JSON, schema/package/native-library,
credential-pattern, and diff checks passed. Final production artifact SHA-256:

```text
Fabric   55fd8070032d9675d6c0f7f6b8f2b1944971c173de21ab3233190e3173fede2d
NeoForge 6fbad2c184700e843bd0d83b6369f61dafa225ac57d791a26151481a7b35b7e6
```

## Completion boundary

Phase 4 is complete only after Tasks 1–9 pass and the final evidence matrix
supports each claim. Compilation is not graphical proof; missing compatible
upstream integrations are reported honestly. The persistent goal may be marked
complete only after no implementation or required acceptance work remains.
