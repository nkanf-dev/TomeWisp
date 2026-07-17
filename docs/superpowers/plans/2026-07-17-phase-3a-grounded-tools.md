# Phase 3A Grounded Tools Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (- [ ]) syntax for tracking.

**Goal:** Replace ambiguous recipe/player snapshots with evidence-bearing domain models and add deterministic recipe search, detail, usage, inventory, and craftability tools without project-defined truncation.

**Architecture:** Immutable source snapshots carry explicit evidence. A pure RecipeCatalog owns deterministic lookup, while a pure max-flow CraftabilityCalculator owns alternative allocation. Thin read-only tools validate inputs and project these services; client/server adapters only translate public Minecraft APIs.

**Tech Stack:** Java 25, Minecraft 26.2 public APIs, Gson 2.14, JUnit 5.11, Gradle 9.5, Fabric, NeoForge.

---

## File structure

Evidence/context:
- Create DataAuthority.java, DataCompleteness.java, EvidenceMetadata.java, and InventorySnapshot.java under common/src/main/java/dev/tomewisp/context/.
- Expand PlayerSnapshot.java, RegistrySnapshot.java, RecipeSnapshot.java, RecipeEntrySnapshot.java.
- Replace IngredientSlotSnapshot.java with IngredientAlternativeSnapshot.java and IngredientRequirementSnapshot.java.
- Add RecipeLayoutSnapshot.java, RecipeOutputSnapshot.java,
  FluidRequirementSnapshot.java, RecipeProcessingSnapshot.java, and
  RecipeReference.java.

Services/tools:
- Add common/src/main/java/dev/tomewisp/recipe/RecipeCatalog.java.
- Add common/src/main/java/dev/tomewisp/crafting/CraftabilityCalculator.java and result records.
- Add SearchRecipesTool.java, GetRecipeTool.java, FindItemUsagesTool.java, InspectInventoryTool.java, CalculateCraftabilityTool.java.
- Keep FindRecipesTool.java and PlayerContextTool.java as compatibility projections.

Adapters:
- Update ClientContextCapture.java and MinecraftContextCapture.java.
- Add gameVersion() to PlatformService and both loader implementations.

Integration:
- Update TomeWispBootstrap.java, bundled Skills, replay traces, tests, docs, and JAR verification.

### Task 1: Evidence metadata

**Files:**
- Create: common/src/main/java/dev/tomewisp/context/DataAuthority.java
- Create: common/src/main/java/dev/tomewisp/context/DataCompleteness.java
- Create: common/src/main/java/dev/tomewisp/context/EvidenceMetadata.java
- Test: common/src/test/java/dev/tomewisp/context/EvidenceMetadataTest.java

- [ ] **Step 1: Write the failing evidence test**

    @Test
    void validatesAndCopiesEvidenceDetails() {
        Map<String, String> details = new HashMap<>();
        details.put("tomewisp:scope", "active_connection");
        EvidenceMetadata evidence = new EvidenceMetadata(
                DataAuthority.CLIENT_VISIBLE, DataCompleteness.COMPLETE,
                Instant.EPOCH, "minecraft:client_recipe_book",
                "minecraft:client_recipe_display", "26.2", "fabric", details);
        details.clear();
        assertEquals("active_connection", evidence.details().get("tomewisp:scope"));
        assertThrows(UnsupportedOperationException.class,
                () -> evidence.details().put("x:y", "z"));
    }

- [ ] **Step 2: Run the focused test**

Run: ./gradlew-curl :common:test --tests dev.tomewisp.context.EvidenceMetadataTest --max-workers=1

Expected: test compilation fails because the evidence records do not exist.

- [ ] **Step 3: Implement exact evidence shapes**

    public enum DataAuthority {
        CLIENT_VISIBLE, SERVER_AUTHORITATIVE, RESOURCE_ASSET,
        INTEGRATION_API, DETERMINISTIC_TEST
    }

    public enum DataCompleteness { COMPLETE, PARTIAL, UNKNOWN }

    public record EvidenceMetadata(
            DataAuthority authority,
            DataCompleteness completeness,
            Instant capturedAt,
            String sourceId,
            String provenance,
            String gameVersion,
            String loader,
            Map<String, String> details) {}

The compact constructor must validate enum/time non-null, namespaced sourceId/provenance, nonblank gameVersion/loader, namespaced detail keys, nonblank detail values, and expose an unmodifiable TreeMap copy.

- [ ] **Step 4: Run the focused test and expect PASS**
- [ ] **Step 5: Commit**

    git add common/src/main/java/dev/tomewisp/context common/src/test/java/dev/tomewisp/context/EvidenceMetadataTest.java
    git commit -m "feat: define grounded evidence metadata"

### Task 2: Grounded inventory and recipe records

**Files:**
- Create: InventorySnapshot.java, IngredientAlternativeSnapshot.java,
  IngredientRequirementSnapshot.java, RecipeLayoutSnapshot.java,
  RecipeOutputSnapshot.java, FluidRequirementSnapshot.java,
  RecipeProcessingSnapshot.java, RecipeReference.java
- Modify: PlayerSnapshot.java, RecipeEntrySnapshot.java, RecipeSnapshot.java, RegistrySnapshot.java
- Delete: IngredientSlotSnapshot.java
- Test: common/src/test/java/dev/tomewisp/context/GroundedSnapshotTest.java
- Create: common/src/test/java/dev/tomewisp/testing/GroundedTestFixtures.java
- Modify: all Java fixtures returned by rg -l "new (RecipeEntrySnapshot|RecipeSnapshot|PlayerSnapshot|RegistrySnapshot)"

- [ ] **Step 1: Write failing shape/validation tests**

    IngredientRequirementSnapshot requirement = new IngredientRequirementSnapshot(
            "input-0", 9, true,
            List.of(new IngredientAlternativeSnapshot(
                    "item", "minecraft:iron_ingot",
                    List.of("minecraft:iron_ingot"))));
    assertEquals(9, requirement.count());
    assertThrows(IllegalArgumentException.class,
            () -> new RecipeOutputSnapshot(ItemStackSnapshot.empty(), 1.1D));

- [ ] **Step 2: Implement these public records**

    public record InventorySnapshot(
            List<InventorySlotSnapshot> slots, int totalSlots,
            int selectedHotbarSlot, int mainHandSlot,
            ItemStackSnapshot offHand, boolean complete,
            EvidenceMetadata evidence) {}

    public record IngredientAlternativeSnapshot(
            String kind, String id, List<String> resolvedItems) {}

    public record IngredientRequirementSnapshot(
            String key, long count, boolean consumed,
            List<IngredientAlternativeSnapshot> alternatives) {}

    public record RecipeLayoutSnapshot(int width, int height, boolean shaped) {
        public static RecipeLayoutSnapshot unknown() {
            return new RecipeLayoutSnapshot(0, 0, false);
        }
    }

    public record RecipeOutputSnapshot(ItemStackSnapshot stack, double probability) {}

    public record FluidRequirementSnapshot(
            String fluidId, long amount, boolean consumed) {}

    public record RecipeProcessingSnapshot(
            Long durationTicks, Long energy, Double temperature) {
        public static RecipeProcessingSnapshot unknown() {
            return new RecipeProcessingSnapshot(null, null, null);
        }
    }

    public record RecipeReference(String sourceId, String recipeId) {}
    public record RecipeSnapshot(EvidenceMetadata evidence, List<RecipeEntrySnapshot> recipes) {}
    public record RegistrySnapshot(EvidenceMetadata evidence, List<RegistryEntrySnapshot> entries) {}

PlayerSnapshot stores InventorySnapshot instead of a raw slot list and adds its
own EvidenceMetadata for identity/location facts. RecipeEntrySnapshot stores
reference, id, type, layout, nullable workstation, ingredients, catalysts,
fluids, outputs, byproducts, processing, conditions, a sorted namespaced
JsonObject extension map, and the source EvidenceMetadata. This lets the pure
calculator determine conclusiveness without consulting mutable catalog state.

Constructors copy all collections, reject duplicate requirement keys, require positive counts, validate probabilities in [0,1], accept only item/tag alternative kinds, and validate every namespaced ID.

- [ ] **Step 3: Migrate test fixtures and run context tests**

Create public GroundedTestFixtures factories for clientEvidence(),
serverEvidence(), recipeSnapshot(), fullContext(),
overlappingAlternativeRecipe(), and inventory(Map<String,Long>). Each factory
returns fresh immutable records so tests in context, recipe, crafting, tool, and
bridge packages share exact evidence without sharing mutable values.

Run: ./gradlew-curl :common:test --tests 'dev.tomewisp.context.*' --max-workers=1

Expected: PASS.

- [ ] **Step 4: Commit**

    git add common/src/main/java/dev/tomewisp/context common/src/test/java/dev/tomewisp
    git commit -m "feat: model grounded recipe and inventory snapshots"

### Task 3: Capture evidence on both game sides

**Files:**
- Modify: common/src/main/java/dev/tomewisp/client/context/ClientContextCapture.java
- Modify: common/src/main/java/dev/tomewisp/context/minecraft/MinecraftContextCapture.java
- Modify: PlatformService.java and loader implementations/tests
- Test: existing/new ContextCapture contract tests

- [ ] **Step 1: Write failing authority/completeness assertions**

    assertEquals(DataAuthority.CLIENT_VISIBLE,
            context.recipes().orElseThrow().evidence().authority());
    assertEquals(DataCompleteness.COMPLETE,
            context.player().orElseThrow().inventory().evidence().completeness());
    assertEquals(context.player().orElseThrow().inventory().totalSlots(),
            context.player().orElseThrow().inventory().slots().size());

- [ ] **Step 2: Add PlatformService.gameVersion()**

Loader implementations obtain the version from their loader metadata rather than a hard-coded production constant. Test platforms return "test".

- [ ] **Step 3: Implement client translation**

Use sources minecraft:client_registry, minecraft:client_recipe_book, and minecraft:client_player with CLIENT_VISIBLE authority. Preserve every inventory slot. mainHandSlot points at the selected hotbar slot and is not a second counted stack. Unknown layout/workstation/processing fields stay unknown.

- [ ] **Step 4: Implement server translation**

Use sources minecraft:registry, minecraft:recipe_manager, and minecraft:server_player with SERVER_AUTHORITATIVE authority. Preserve real RecipeHolder IDs, positive ingredient counts, and every server inventory slot.

- [ ] **Step 5: Verify**

Run: ./gradlew-curl :common:test --tests '*ContextCapture*' :fabric:compileJava :neoforge:compileJava --max-workers=1

Expected: PASS/BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

    git add common/src/main/java/dev/tomewisp/client/context common/src/main/java/dev/tomewisp/context/minecraft common/src/main/java/dev/tomewisp/platform fabric/src/main/java neoforge/src/main/java common/src/test
    git commit -m "feat: capture grounded Minecraft evidence"

### Task 4: RecipeCatalog and query tools

**Files:**
- Create: common/src/main/java/dev/tomewisp/recipe/RecipeCatalog.java
- Create: SearchRecipesTool.java, GetRecipeTool.java, FindItemUsagesTool.java
- Test: RecipeCatalogTest.java and RecipeQueryToolsTest.java

- [ ] **Step 1: Write failing stable query tests**

    RecipeCatalog catalog = new RecipeCatalog(GroundedTestFixtures.recipeSnapshot());
    assertEquals(List.of("example:alloy", "minecraft:iron_block"),
            catalog.search(new RecipeCatalog.Query(
                    null, null, "minecraft:iron_ingot", null))
                    .stream().map(s -> s.reference().recipeId()).toList());

- [ ] **Step 2: Implement RecipeCatalog**

Public shapes:

    public record Query(
            String recipeId, String outputItem,
            String inputItem, String recipeType) {}
    public record Summary(
            RecipeReference reference, String id, String type,
            List<RecipeOutputSnapshot> outputs, String workstation,
            EvidenceMetadata evidence) {}
    public enum UsageRole { INPUT, CATALYST, OUTPUT, BYPRODUCT }
    public record Usage(
            RecipeReference reference, UsageRole role,
            EvidenceMetadata evidence) {}

The constructor copies one RecipeSnapshot. Search requires at least one criterion, exact-matches IDs including resolved tag items, and sorts source then recipe ID. get matches source+recipe identity. usages emits each distinct recipe/role in deterministic order.

- [ ] **Step 3: Implement thin tools**

SearchRecipesTool.Input has four ToolOptional strings and rejects all-empty. GetRecipeTool.Input has sourceId+recipeId. FindItemUsagesTool.Input has itemId. Each output carries snapshot evidence. Missing recipe context is capability_unavailable; valid no-match search succeeds empty; unknown get is recipe_not_found.

- [ ] **Step 4: Verify and commit**

Run: ./gradlew-curl :common:test --tests 'dev.tomewisp.recipe.*' --tests '*RecipeQueryToolsTest' --tests 'dev.tomewisp.agent.tool.*' --max-workers=1

    git add common/src/main/java/dev/tomewisp/recipe common/src/main/java/dev/tomewisp/tool/builtin common/src/test
    git commit -m "feat: query grounded recipe catalogs"

### Task 5: Narrow inventory tool

**Files:**
- Create: InspectInventoryTool.java
- Modify: PlayerContextTool.java
- Test: InspectInventoryToolTest.java

- [ ] **Step 1: Write failing count/evidence test**

    ToolResult.Success<InspectInventoryTool.Output> success = assertInstanceOf(
            ToolResult.Success.class,
            new InspectInventoryTool().invoke(
                    GroundedTestFixtures.fullContext(),
                    new InspectInventoryTool.Input()));
    InspectInventoryTool.Output output = success.value();
    assertEquals(4L, output.counts().get("minecraft:iron_ingot"));
    assertEquals(DataAuthority.SERVER_AUTHORITATIVE, output.evidence().authority());

- [ ] **Step 2: Implement InspectInventoryTool**

Return InventorySnapshot, sorted Map<String,Long> counts, and evidence. Count inventory slots plus offhand only; exclude air; never add the selected main hand twice. Missing player is player_required. Update PlayerContextTool wording/output to expose the same evidence without claiming implicit completeness.

- [ ] **Step 3: Verify and commit**

Run: ./gradlew-curl :common:test --tests '*InspectInventoryToolTest' --tests '*KnowledgeToolsTest' --max-workers=1

    git add common/src/main/java/dev/tomewisp/tool/builtin common/src/test/java/dev/tomewisp/tool/builtin
    git commit -m "feat: inspect grounded player inventory"

### Task 6: Deterministic craftability

**Files:**
- Create: common/src/main/java/dev/tomewisp/crafting/CraftabilityCalculator.java
- Create: CraftabilityResult.java, IngredientAllocation.java, MissingRequirement.java
- Create: CalculateCraftabilityTool.java
- Test: CraftabilityCalculatorTest.java and CalculateCraftabilityToolTest.java

- [ ] **Step 1: Write failing direct/overlap/partial tests**

    CraftabilityResult result = new CraftabilityCalculator().calculate(
            GroundedTestFixtures.overlappingAlternativeRecipe(),
            GroundedTestFixtures.inventory(Map.of(
                    "minecraft:oak_planks", 1L,
                    "minecraft:birch_planks", 1L)), 1);
    assertTrue(result.craftable());
    assertEquals(2L, result.allocations().stream()
            .mapToLong(IngredientAllocation::count).sum());

A partial-inventory fixture with sufficient items must return craftable=true and conclusive=false.

- [ ] **Step 2: Implement the calculator**

Build a deterministic long-capacity Dinic network:

    source -> requirement (count * crafts)
    requirement -> each compatible sorted item (Long.MAX_VALUE / 4)
    item -> sink (available count)

Non-consumed catalysts require presence but do not consume capacity. Derive
allocations from used requirement-item edges and missing amounts from
unsatisfied source edges. conclusive requires COMPLETE recipe.evidence() and
inventory.evidence(). Compute maximumCrafts by overflow-safe exponential
upper-bound discovery plus binary search. Use Math.multiplyExact.

Result:

    public record CraftabilityResult(
            boolean craftable, boolean conclusive,
            long requestedCrafts, long maximumCrafts,
            List<IngredientAllocation> allocations,
            List<MissingRequirement> missing) {}

- [ ] **Step 3: Implement CalculateCraftabilityTool**

Input: sourceId, recipeId, positive long crafts. Required context: PLAYER and RECIPES. Resolve with RecipeCatalog, calculate against InventorySnapshot, and return recipe evidence, inventory evidence, and complete result. Missing context is capability_unavailable; unknown recipe is recipe_not_found; overflow/invalid count is invalid_arguments. Do not recursively craft intermediates.

- [ ] **Step 4: Verify and commit**

Run: ./gradlew-curl :common:test --tests 'dev.tomewisp.crafting.*' --tests '*CalculateCraftabilityToolTest' --max-workers=1

    git add common/src/main/java/dev/tomewisp/crafting common/src/main/java/dev/tomewisp/tool/builtin/CalculateCraftabilityTool.java common/src/test
    git commit -m "feat: calculate recipe craftability deterministically"

### Task 7: Register and migrate the workflow

**Files:**
- Modify: TomeWispBootstrap.java
- Modify: FindRecipesTool.java
- Modify: bundled Skill Markdown and replay JSON
- Modify: Skill/replay/schema/bridge tests
- Test: BuiltinToolRegistrationTest.java

- [ ] **Step 1: Write failing registration assertion**

    assertTrue(toolIds.containsAll(Set.of(
            "tomewisp:search_recipes",
            "tomewisp:get_recipe",
            "tomewisp:find_item_usages",
            "tomewisp:inspect_inventory",
            "tomewisp:calculate_craftability",
            "tomewisp:find_recipes")));

- [ ] **Step 2: Register in one deterministic list**

FindRecipesTool becomes a deprecated projection over RecipeCatalog rather than retaining an independent filter implementation.

- [ ] **Step 3: Migrate Skills and traces**

Recipe workflow:
resolve_resource -> search_recipes -> get_recipe -> inspect_inventory -> calculate_craftability when sufficiency is requested.

Migrate iron-ingot-recipe to search_recipes. Add iron-block-craftability requiring recipes+player and checking deterministic missing material output. Keep a compatibility replay for find_recipes.

- [ ] **Step 4: Verify and commit**

Run: ./gradlew-curl :common:test --tests 'dev.tomewisp.skill.*' --tests 'dev.tomewisp.trace.*' --tests 'dev.tomewisp.bridge.*' --tests '*BuiltinToolRegistrationTest' --max-workers=1

    git add common/src/main/java/dev/tomewisp/TomeWispBootstrap.java common/src/main/java/dev/tomewisp/tool/builtin/FindRecipesTool.java common/src/main/resources common/src/test
    git commit -m "feat: expose grounded recipe workflow"

### Task 8: Phase 3A completion evidence

**Files:**
- Modify: README.md
- Modify: docs/development.md
- Modify: /Users/nkanf/docs/neolongsur/PRODUCT_DESIGN.md after repository docs commit
- Modify SKMB only if implementation reveals a new state semantic

- [ ] **Step 1: Run full common tests**

Run: ./gradlew-curl :common:test --max-workers=1

Expected: all normal tests pass; opt-in live provider may be skipped without credentials.

- [ ] **Step 2: Build both loaders**

Run: ./gradlew-curl :fabric:build :neoforge:build --max-workers=1

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Inspect JARs and scan secrets**

Verify the five new tool classes and evidence/domain records exist in both production JARs. Search JAR contents, reports, resources, and Git objects with the repository secret regex; expect no match.

- [ ] **Step 4: Document exact behavior**

Document authority/completeness, new tool IDs, deterministic non-recursive craftability, compatibility alias, and explicit lack of JEI/REI/EMI or recursive production planning.

- [ ] **Step 5: Commit docs**

    git add README.md docs/development.md docs/isme
    git commit -m "docs: document grounded recipe tools"

- [ ] **Step 6: Record evidence**

Record exact test counts, artifact names, and commit IDs in product documentation. Mark Phase 3B/3C incomplete; do not claim Phase 3 complete.
