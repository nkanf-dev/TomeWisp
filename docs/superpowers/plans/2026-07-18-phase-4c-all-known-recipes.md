# Phase 4C All-Known Recipes and Viewer Integration Plan

> **For agentic workers:** Use `executing-plans` task by task. This is an
> internal work package in the single Phase 4, not a separate product phase.

**Goal:** Replace recipe-book-bounded client capture with a generation-aware,
multi-source `ALL_KNOWN` catalog, optional JEI/REI capture and navigation, and
native recipe actions that work without making a viewer a hard dependency.

**Verified compatibility on 2026-07-18:** Modrinth v2 exact filters found JEI
30.12.0.69 and REI 26.2.820 for both Fabric and NeoForge 26.2. EMI returned no
26.2 version. Patchouli returned no 26.2 version. Farmer's Delight Refabricated
26.2-3.6.7 is available for Fabric. Public API inspection proved JEI category,
recipe-layout slot, focus/navigation, and runtime lifecycle access, plus REI
display-registry, entry-group, and view-search access. Research downloads lived
only in a system temporary directory.

**Architecture:** Common owns detached provider records, visibility, generation,
merge, configuration, diagnostics, and viewer intents. `ClientContextCapture`
samples an injected recipe knowledge service on the client thread. JEI and REI
adapters compile against exact public API artifacts but are loaded only when the
platform reports the mod installed. Loader entrypoints provide config paths and
viewer availability; they do not implement recipe semantics.

## Task 1: Prove Optional API Dependencies

- [ ] Add exact JEI common API and REI API compile-only repositories/versions.
- [ ] Add API surface contract tests or compile probes for enumeration,
  grouped ingredients, stable IDs, runtime availability, and navigation.
- [ ] Prove TomeWisp starts/builds without either viewer on both loaders.
- [ ] Record exact Maven artifacts and commit `build: verify recipe viewer APIs`.

## Task 2: Define Provider, Generation, and Visibility Domain

- [ ] Add provider state/diagnostic/snapshot records and `RecipeUnlockState`.
- [ ] Extend `RecipeReference` with a required generation and update tool schemas.
- [ ] Return `stale_reference` for absent generation/record; keep malformed input
  distinct as `invalid_arguments`.
- [ ] Add persisted client recipe config with default `ALL_KNOWN`, per-source
  enable flags, and preferred viewer; never store credentials.
- [ ] Add strict configuration, generation stability, and stale-reference tests.
- [ ] Commit `feat: define all-known recipe sources`.

## Task 3: Merge Canonical Provider Snapshots

- [ ] Implement deterministic semantic fingerprints and authority ordering.
- [ ] Group only identical semantics while retaining all references/evidence.
- [ ] Preserve same-ID conflicts as distinct variants with diagnostics.
- [ ] Aggregate completeness honestly; vanilla recipe-book-only capture is
  partial under `ALL_KNOWN`.
- [ ] Add permutation, conflict, failure-isolation, and visibility tests.
- [ ] Commit `feat: merge recipe provider snapshots`.

## Task 4: Capture Vanilla Unlock Metadata

- [ ] Refactor current recipe-book traversal behind the provider contract.
- [ ] Mark synchronized entries `UNLOCKED`, source them from
  `minecraft:client_recipe_book`, and never claim all-known completeness.
- [ ] Preserve immutable thread capture and existing ingredient/layout behavior.
- [ ] Add locked/unknown filter and no-viewer regression tests.
- [ ] Commit `refactor: isolate vanilla recipe provider`.

## Task 5: Integrate JEI Through Its Public API

- [ ] Register a minimal `@JeiPlugin` runtime lifecycle bridge.
- [ ] Enumerate hidden and visible recipes from every category, build layouts
  with an empty focus, and detach slot alternatives by role.
- [ ] Use category recipe identifiers when present and a semantic digest fallback.
- [ ] Omit unsupported ingredient records with partial diagnostics.
- [ ] Implement item recipe/usage navigation; advertise exact navigation only
  where the API can select the exact recipe.
- [ ] Add fake-public-interface adapter tests and both loader builds.
- [ ] Commit `feat: integrate jei recipe knowledge`.

## Task 6: Integrate REI Through Its Public API

- [ ] Read `DisplayRegistry.getAll()` and detach category, display location,
  grouped inputs, outputs, item/fluid values, and stable fallback IDs.
- [ ] Keep registry/display objects on the client thread.
- [ ] Implement item recipes/usages with `ViewSearchBuilder`; exact navigation
  remains explicitly unsupported unless a verified selector exists.
- [ ] Resolve Fabric/NeoForge compile-only dependencies without packaging REI.
- [ ] Add adapter/failure/parity tests and commit
  `feat: integrate rei recipe knowledge`.

## Task 7: Connect Context, Tools, and Native Recipe Actions

- [ ] Inject the combined recipe knowledge service into client context capture.
- [ ] Expose source states, generations, completeness, conflicts, and diagnostics
  in tool evidence without asking the model to infer them.
- [ ] Add recipe output item icons and in-game recipes/usages/open-viewer actions.
- [ ] Disable unavailable actions with localized diagnostics; never open a browser.
- [ ] Add prompt/Skill guidance for generation-bearing exact lookup and semantic
  item references.
- [ ] Add English/Simplified Chinese text and deterministic UI/tool E2E coverage.
- [ ] Commit `feat: expose all-known recipe actions`.

## Task 8: Verify and Retain Recipe Smoke Evidence

- [ ] Run focused provider/catalog/tool/UI/architecture tests.
- [ ] Run `./gradlew clean :common:test :fabric:build :neoforge:build`.
- [ ] Build an ignored Fabric smoke profile with JEI, REI where coexistence is
  practical, Farmer's Delight Refabricated, and required dependencies.
- [ ] Since Patchouli has no 26.2 artifact, exercise TomeWisp's retained
  resource-based Patchouli fixture and record that boundary explicitly.
- [ ] Prove a recipe absent from the vanilla unlock set is searchable, exact
  lookup round-trips its generation, viewer navigation opens, and Farmer's
  Delight custom processing records remain grounded.
- [ ] Retain artifact URLs, versions, SHA-256, logs/report, screenshots, and
  secret scan; do not claim EMI or Patchouli runtime integration.
- [ ] Update SKMB-007 implementation evidence and commit
  `docs: verify all-known recipe integration`.

## Completion Boundary

Phase 4C completes the recipe/viewer slice only. Context compaction, semantic
rich-message schema, settings/developer diagnostics, history paging, and the
final consolidated Phase 4 client smoke remain active goal work.
