# Phase 4E Debug-Gated Player Tool Cards Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the always-technical tool/source detail panel with Minecraft-native player cards in normal mode and a separately gated, redacted diagnostic section in opt-in Debug Mode.

**Architecture:** Common code owns strict display configuration, immutable card projections, known-tool parsing, friendly narration, and debug-only diagnostic projections. `TomeWispScreen` renders the common cards with Minecraft item APIs and appends technical details only when `debugMode=true`; Fabric and NeoForge load the same client-local config and pass the same snapshot to the screen. Normal history remains unchanged because the setting controls projection only.

**Tech Stack:** Java 25 records/sealed interfaces, Gson strict JSON, existing normalized tool results and recipe references, Minecraft 26.2 item rendering, JUnit 5, Fabric/NeoForge loader adapters.

---

## Accepted Boundaries

- SKMB-2026-07-18-010 is the designer-approved authority.
- The player-facing label is `调试模式` / `Debug Mode`; default is `false`.
- Normal mode cannot represent tool/request/invocation IDs, evidence authority or completeness enums, capture timestamps, provenance, normalized JSON, or technical failure codes.
- Debug mode remains redacted and still cannot represent reasoning, credentials, authorization, raw provider bodies, or another player's state.
- This work package builds reusable typed cards for tool details. Parsing arbitrary model-authored semantic Markdown/components remains the next Phase 4 rich-message package.
- No result/card count cap is introduced. The existing detail scissor/scroll work must render every projected card without truncating the logical projection.

### Task 1: Strict Display Configuration

**Files:**
- Create: `common/src/main/java/dev/tomewisp/guide/ui/GuideDisplayConfig.java`
- Create: `common/src/main/java/dev/tomewisp/guide/ui/GuideDisplayConfigLoader.java`
- Test: `common/src/test/java/dev/tomewisp/guide/ui/GuideDisplayConfigLoaderTest.java`

- [x] **Step 1: Write failing default, round-trip, and strict-schema tests**

```java
@Test
void missingConfigDefaultsToDebugOff() {
    GuideDisplayConfig config = new GuideDisplayConfigLoader().load(missing).config();
    assertFalse(config.debugMode());
}

@Test
void readsVersionedDebugModeAndRejectsUnknownFields() {
    Files.writeString(path, "{\"schemaVersion\":1,\"debugMode\":true}");
    assertTrue(new GuideDisplayConfigLoader().load(path).config().debugMode());
    Files.writeString(path, "{\"schemaVersion\":1,\"debugMode\":false,\"rawSecrets\":true}");
    assertEquals("invalid_display_config", loader.load(path).failure().code());
}
```

- [x] **Step 2: Run the red test**

Run:

```bash
./gradlew :common:test --tests 'dev.tomewisp.guide.ui.GuideDisplayConfigLoaderTest'
```

Expected: compilation fails because the config types do not exist.

- [x] **Step 3: Implement immutable config and strict loader**

```java
public record GuideDisplayConfig(int schemaVersion, boolean debugMode) {
    public static final int SCHEMA_VERSION = 1;
    public static GuideDisplayConfig defaults() {
        return new GuideDisplayConfig(SCHEMA_VERSION, false);
    }
}

public record Load(GuideDisplayConfig config, GuideFailure failure) {}
```

The loader accepts exactly `schemaVersion` and `debugMode`, returns defaults for
a missing file, rejects future versions/unknown fields/type mismatches as
`invalid_display_config`, and never rewrites a malformed file.

- [x] **Step 4: Run the focused test and commit**

```bash
./gradlew :common:test --tests 'dev.tomewisp.guide.ui.GuideDisplayConfigLoaderTest'
git add common/src/main/java/dev/tomewisp/guide/ui common/src/test/java/dev/tomewisp/guide/ui/GuideDisplayConfigLoaderTest.java
git commit -m "feat: configure debug UI projection"
```

Expected: tests pass; missing config yields schema 1 with Debug Mode disabled.

The red run failed with nine missing-symbol errors. The green run passed all
three tests; malformed, future-version, and unknown-field files remain
untouched while the runtime receives the safe default with Debug Mode off.

### Task 2: Immutable Player Card Domain

**Files:**
- Create: `common/src/main/java/dev/tomewisp/guide/ui/GuideItemView.java`
- Create: `common/src/main/java/dev/tomewisp/guide/ui/GuideDetailCard.java`
- Create: `common/src/main/java/dev/tomewisp/guide/ui/GuideToolDetailView.java`
- Modify: `common/src/main/java/dev/tomewisp/guide/ui/GuideRecipeCard.java`
- Test: `common/src/test/java/dev/tomewisp/guide/ui/GuideToolDetailViewTest.java`

- [x] **Step 1: Write failing immutability and normal/debug separation tests**

```java
@Test
void normalProjectionHasNoTechnicalFields() {
    GuideToolDetailView detail = GuideToolDetailPresenter.project(activity, false);
    assertTrue(detail.debug().isEmpty());
    assertFalse(detail.narration().toString().contains("SERVER_AUTHORITATIVE"));
    assertFalse(detail.narration().toString().contains(activity.invocationId()));
}

@Test
void debugProjectionIsSeparateAndDefensivelyCopied() {
    GuideToolDetailView detail = GuideToolDetailPresenter.project(activity, true);
    assertEquals(activity.toolId(), detail.debug().orElseThrow().toolId());
    assertNotSame(activity.normalized(), detail.debug().orElseThrow().normalized());
}
```

- [x] **Step 2: Define card records without Minecraft classes**

```java
public record GuideItemView(String itemId, String displayName, long count) {}

public sealed interface GuideDetailCard permits
        GuideDetailCard.Recipe,
        GuideDetailCard.ItemGrid,
        GuideDetailCard.Requirements,
        GuideDetailCard.Text,
        GuideDetailCard.Error {
    record Recipe(GuideRecipeCard recipe) implements GuideDetailCard {}
    record ItemGrid(String titleKey, List<GuideItemView> items) implements GuideDetailCard {}
    record Requirements(
            boolean craftable,
            boolean conclusive,
            long requestedCrafts,
            long maximumCrafts,
            List<Requirement> requirements) implements GuideDetailCard {}
    record Requirement(
            String key,
            long required,
            long allocated,
            long missing,
            List<GuideItemView> alternatives) {}
    record Text(String titleKey, List<String> lines) implements GuideDetailCard {}
    record Error(String message) implements GuideDetailCard {}
}

public record GuideToolDetailView(
        String titleKey,
        GuideToolStatus status,
        List<GuideDetailCard> cards,
        List<String> narration,
        Optional<Debug> debug) {
    public record Debug(
            String invocationId,
            String toolId,
            List<GuideSource> sources,
            JsonObject normalized,
            String validationDiagnostic) {}
}
```

All constructors validate nonblank identifiers, positive/nonnegative counts,
copy lists, and deep-copy JSON. `GuideRecipeCard` gains immutable ingredient,
catalyst, byproduct, and processing projections using `GuideItemView` and
alternative item IDs.

- [x] **Step 3: Run the card-domain tests and commit**

```bash
./gradlew :common:test --tests 'dev.tomewisp.guide.ui.GuideToolDetailViewTest'
git add common/src/main/java/dev/tomewisp/guide/ui common/src/test/java/dev/tomewisp/guide/ui/GuideToolDetailViewTest.java
git commit -m "feat: define player tool detail cards"
```

The red run failed only on the missing card-domain symbols. The green run
passed both the new immutability/debug-separation tests and the existing recipe
presenter tests. All card lists are copied, debug JSON is deep-copied on input
and access, and recipe cards retain their compatibility constructor while
gaining ingredient, catalyst, byproduct, and processing projections.

### Task 3: Known-Tool Card Projection and Friendly Fallback

**Files:**
- Create: `common/src/main/java/dev/tomewisp/guide/ui/GuideToolDetailPresenter.java`
- Modify: `common/src/main/java/dev/tomewisp/guide/ui/GuideRecipePresenter.java`
- Modify: `common/src/main/java/dev/tomewisp/guide/GuideToolPresentation.java`
- Test: `common/src/test/java/dev/tomewisp/guide/ui/GuideToolDetailPresenterTest.java`
- Modify: `common/src/test/java/dev/tomewisp/guide/ui/GuideRecipePresenterTest.java`

- [x] **Step 1: Write red fixtures for recipe, inventory, craftability, failure, malformed, and unknown tools**

Use normalized fixtures with:

```json
{
  "status": "success",
  "value": {
    "result": {
      "craftable": false,
      "conclusive": true,
      "requestedCrafts": 1,
      "maximumCrafts": 0,
      "allocations": [
        {"requirementKey":"iron","itemId":"minecraft:iron_ingot","count":4}
      ],
      "missing": [
        {"requirementKey":"iron","required":9,"allocated":4,"missing":5,
         "alternatives":["minecraft:iron_ingot"]}
      ]
    }
  }
}
```

Assert normal projections contain requirements/items/friendly messages but no
`stale_reference`, generation hashes, authority/completeness, or raw JSON.
Assert debug projections retain the already-authorized technical fields in the
separate `Debug` record.

- [x] **Step 2: Implement strict known-tool projection**

`GuideToolDetailPresenter.project(activity, debugMode)` handles:

- `search_recipes` and `get_recipe`: one recipe card per valid record;
- `find_item_usages`: friendly usage/recipe cards;
- `inspect_inventory`: sorted item grid with counts;
- `calculate_craftability`: requirement rows merging allocations and missing
  records, with craftable/conclusive labels;
- known failures: localized-friendly message selected from a closed code map;
- unknown or malformed data: deterministic `Text` or `Error` card from retained
  presentation lines, never normalized JSON in normal mode.

The presenter returns every valid record and does not introduce a count cap.
Malformed records are omitted only from their typed card, append a friendly
fallback, and set a redacted validation diagnostic for Debug Mode.

- [x] **Step 3: Replace technical normal narration in `GuideToolPresentation`**

Remove provider generation, authority/completeness enums, raw status codes, and
raw `value.toString()` from normal presentation. Keep technical projection in
`GuideToolDetailView.Debug`; persisted normal presentation lines remain friendly
and safe after restart.

- [x] **Step 4: Run presenter/history privacy tests and commit**

```bash
./gradlew :common:test \
  --tests 'dev.tomewisp.guide.ui.GuideToolDetailPresenterTest' \
  --tests 'dev.tomewisp.guide.ui.GuideRecipePresenterTest' \
  --tests 'dev.tomewisp.guide.ui.GuideToolPresenterTest' \
  --tests 'dev.tomewisp.guide.history.*'
git add common/src/main/java/dev/tomewisp/guide common/src/test/java/dev/tomewisp/guide
git commit -m "feat: project friendly grounded tool cards"
```

The red run failed on the intentionally absent presenter. Focused recipe,
inventory, craftability, failure, malformed/unknown, and privacy tests then
passed, followed by the guide and durable-history regression suites. Normal
history no longer stores catalog enums, generation hashes, internal failure
codes, or normalized JSON; Debug Mode retains a defensive copy in its separate
projection.

### Task 4: Render Cards and Gate Diagnostics in the Native Screen

**Files:**
- Modify: `common/src/main/java/dev/tomewisp/client/gui/TomeWispScreen.java`
- Modify: `common/src/main/java/dev/tomewisp/guide/ui/GuideUiRow.java`
- Modify: `common/src/main/java/dev/tomewisp/guide/ui/GuideUiView.java`
- Modify: `common/src/main/resources/assets/tomewisp/lang/zh_cn.json`
- Modify: `common/src/main/resources/assets/tomewisp/lang/en_us.json`
- Test: `common/src/test/java/dev/tomewisp/guide/ui/GuideUiViewTest.java`
- Test: `common/src/test/java/dev/tomewisp/client/gui/TomeWispScreenProjectionTest.java`

- [x] **Step 1: Add red view tests proving technical data is absent by default**

```java
GuideUiView normal = GuideUiView.from(snapshot, GuideDisplayConfig.defaults());
GuideUiRow.Tool tool = (GuideUiRow.Tool) normal.rows().stream()
        .filter(GuideUiRow.Tool.class::isInstance).findFirst().orElseThrow();
assertTrue(tool.detail().debug().isEmpty());

GuideUiView debug = GuideUiView.from(snapshot, new GuideDisplayConfig(1, true));
assertTrue(((GuideUiRow.Tool) debug.rows().get(1)).detail().debug().isPresent());
```

- [x] **Step 2: Put the common detail projection in each tool row**

`GuideUiRow.Tool` carries both the immutable activity identity needed for
in-place refresh and `GuideToolDetailView detail`. `GuideUiView.from(snapshot,
displayConfig)` creates the player/debug projection. The existing one-argument
factory delegates to `GuideDisplayConfig.defaults()` so tests and non-screen
consumers remain normal-mode safe.

- [x] **Step 3: Render typed cards**

In `TomeWispScreen`:

- render item stacks through `BuiltInRegistries.ITEM`, `graphics.item`, native
  decorations, and native tooltips;
- render recipe ingredients and outputs as rows/grids before viewer actions;
- render craftability with explicit `可合成/不可合成/证据不足` text plus
  green/amber/red status and missing counts;
- render inventory grids with icons and counts;
- render friendly text/error cards when no typed card is valid;
- append a separated `调试信息` section only when `detail.debug().isPresent()`;
- move evidence authority/completeness/time/provenance and raw identifiers into
  that debug section;
- in normal transcript source rows show only `来源 · <friendly source>`;
- preserve scissor boundaries, keyboard/mouse actions, and text narration.

- [x] **Step 4: Add English and Simplified Chinese keys**

Add keys for Debug Mode, details, source, ingredients, outputs, workstation,
inventory, craftable/not-craftable/inconclusive, required/available/missing,
debug information, unknown tool, and friendly failure messages. Remove new
hard-coded player strings from the touched detail renderer.

- [x] **Step 5: Run screen/view tests and commit**

```bash
./gradlew :common:test \
  --tests 'dev.tomewisp.guide.ui.*' \
  --tests 'dev.tomewisp.client.gui.*'
git add common/src/main/java/dev/tomewisp/client/gui common/src/main/java/dev/tomewisp/guide/ui \
  common/src/main/resources/assets/tomewisp/lang common/src/test/java/dev/tomewisp
git commit -m "feat: render debug-gated tool cards"
```

The view tests failed first on the absent debug-aware row factory and screen
source-label projection. The focused tests and full common suite now pass.
Tool rows carry their immutable detail view, the detail pane independently
scrolls every card, native item icons/tooltips cover recipe inputs, outputs,
inventory, allocations, and missing materials, and normal evidence links do
not contain source IDs or authority/completeness enums. Both language files
also pass strict JSON parsing.

### Task 5: Wire Display Config on Both Loaders

**Files:**
- Modify: `fabric/src/main/java/dev/tomewisp/fabric/TomeWispFabricClient.java`
- Modify: `neoforge/src/main/java/dev/tomewisp/neoforge/TomeWispNeoForgeClient.java`
- Modify: `common/src/main/java/dev/tomewisp/client/gui/TomeWispScreen.java`
- Test: `common/src/test/java/dev/tomewisp/client/ClientArchitectureTest.java`

- [ ] **Step 1: Load one common config path on both loaders**

Both loaders create `GuideDisplayConfigLoader` for
`config/tomewisp/display.json`, retain the last valid/default result, and pass
its immutable config to the screen constructor. A malformed file shows a
friendly local notice while keeping Debug Mode off or the last valid value.

- [ ] **Step 2: Prove loader parity and no common loader imports**

Extend architecture tests/search assertions so both loader entrypoints use the
same common loader/config type and production common code remains free of
Fabric/NeoForge imports.

- [ ] **Step 3: Compile both loaders and commit**

```bash
./gradlew :common:test --tests 'dev.tomewisp.client.ClientArchitectureTest' \
  :fabric:compileJava :neoforge:compileJava
git add fabric neoforge common/src/test/java/dev/tomewisp/client/ClientArchitectureTest.java
git commit -m "feat: load debug UI settings on both loaders"
```

### Task 6: Verify the Work Package

**Files:**
- Modify: `README.md`
- Modify: `docs/development.md`
- Modify: `docs/isme/decisions/2026-07-18-010-debug-ui-projection.md`
- Modify: this plan

- [ ] **Step 1: Run focused and full gates**

```bash
./gradlew :common:test --tests 'dev.tomewisp.guide.ui.*' \
  --tests 'dev.tomewisp.client.gui.*' --tests 'dev.tomewisp.guide.history.*'
./gradlew clean :common:test :fabric:build :neoforge:build
```

- [ ] **Step 2: Run static/privacy checks**

Run `git diff --check`, tracked shell/Python/JSON syntax checks, production-JAR
credential scans, and source searches proving normal-mode projection types have
no raw diagnostic JSON/evidence metadata fields.

- [ ] **Step 3: Record evidence and commit**

Record test counts, artifact hashes, non-fatal warnings, the default-off Debug
Mode behavior, and the explicit unrun graphical-client status. Commit:

```bash
git add README.md docs
git commit -m "docs: verify player-friendly tool cards"
```

## Completion Boundary

This work package is complete when ordinary players receive friendly visual
cards without technical evidence internals, opt-in Debug Mode exposes only the
approved redacted diagnostic projection, both loaders use the same config, and
the full gate passes. Model-authored semantic Markdown/components, named model
profiles, settings screens, long-history paging, and final consolidated
graphical acceptance remain active Phase 4 work.
