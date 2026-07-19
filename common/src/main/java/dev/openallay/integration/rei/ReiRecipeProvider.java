package dev.openallay.integration.rei;

import dev.architectury.fluid.FluidStack;
import dev.openallay.context.DataAuthority;
import dev.openallay.context.DataCompleteness;
import dev.openallay.context.EvidenceMetadata;
import dev.openallay.context.FluidRequirementSnapshot;
import dev.openallay.context.IngredientAlternativeSnapshot;
import dev.openallay.context.IngredientRequirementSnapshot;
import dev.openallay.context.ItemStackSnapshot;
import dev.openallay.context.RecipeEntrySnapshot;
import dev.openallay.context.RecipeLayoutSnapshot;
import dev.openallay.context.RecipeOutputSnapshot;
import dev.openallay.context.RecipeProcessingSnapshot;
import dev.openallay.context.RecipeReference;
import dev.openallay.platform.PlatformService;
import dev.openallay.recipe.RecipeCanonicalizer;
import dev.openallay.recipe.RecipeKnowledgeProvider;
import dev.openallay.recipe.RecipeProviderDiagnostic;
import dev.openallay.recipe.RecipeProviderSnapshot;
import dev.openallay.recipe.RecipeUnlockState;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Supplier;
import me.shedaniel.rei.api.client.registry.display.DisplayRegistry;
import me.shedaniel.rei.api.common.category.CategoryIdentifier;
import me.shedaniel.rei.api.common.display.Display;
import me.shedaniel.rei.api.common.entry.EntryIngredient;
import me.shedaniel.rei.api.common.entry.EntryStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

final class ReiRecipeProvider implements RecipeKnowledgeProvider {
    private static final String SOURCE_ID = "viewer:rei";
    private static final Identifier TAG_CATEGORY =
            Identifier.fromNamespaceAndPath("minecraft", "plugins/tag");
    private static final String CAPTURE_GENERATION_PLACEHOLDER = "0".repeat(64);

    private final Instant capturedAt;
    private final PlatformService platform;
    private final Supplier<DisplayRegistry> registry;

    ReiRecipeProvider(Instant capturedAt, PlatformService platform) {
        this(capturedAt, platform, DisplayRegistry::getInstance);
    }

    ReiRecipeProvider(
            Instant capturedAt, PlatformService platform, Supplier<DisplayRegistry> registry) {
        this.capturedAt = Objects.requireNonNull(capturedAt, "capturedAt");
        this.platform = Objects.requireNonNull(platform, "platform");
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    @Override
    public String sourceId() {
        return SOURCE_ID;
    }

    @Override
    public RecipeProviderSnapshot capture() {
        DisplayRegistry displayRegistry;
        try {
            displayRegistry = Objects.requireNonNull(registry.get(), "REI display registry");
        } catch (RuntimeException failure) {
            return RecipeProviderSnapshot.unavailable(
                    SOURCE_ID, "runtime_unavailable", "REI display registry is not available");
        }

        List<RecipeProviderDiagnostic> diagnostics = new ArrayList<>();
        TreeMap<String, RecipeEntrySnapshot> recipes = new TreeMap<>();
        TreeMap<String, Map.Entry<CategoryIdentifier<?>, List<Display>>> categories = new TreeMap<>();
        displayRegistry.getAll().forEach((category, displays) ->
                categories.put(category.getIdentifier().toString(), Map.entry(category, displays)));
        categories.values().forEach(category -> captureCategory(
                category.getKey(), category.getValue(), recipes, diagnostics));
        DataCompleteness completeness = diagnostics.isEmpty()
                ? DataCompleteness.COMPLETE
                : DataCompleteness.PARTIAL;
        return RecipeProviderSnapshot.available(
                SOURCE_ID, completeness, List.copyOf(recipes.values()), diagnostics);
    }

    private void captureCategory(
            CategoryIdentifier<?> category,
            List<Display> displays,
            Map<String, RecipeEntrySnapshot> recipes,
            List<RecipeProviderDiagnostic> diagnostics) {
        if (TAG_CATEGORY.equals(category.getIdentifier())) {
            // REI publishes tag membership as multi-output displays. They are useful viewer
            // metadata, but they are not recipes and must not become recipe search candidates.
            return;
        }
        for (Display display : List.copyOf(displays)) {
            try {
                RecipeEntrySnapshot detached = detach(category, display);
                RecipeEntrySnapshot previous = recipes.putIfAbsent(
                        detached.reference().recipeId(), detached);
                if (previous != null && !previous.equals(detached)) {
                    diagnostics.add(diagnostic(
                            "duplicate_reference",
                            "REI displays produced the same stable reference: "
                                    + detached.reference().recipeId()));
                }
            } catch (UnsupportedRecipe failure) {
                diagnostics.add(diagnostic(failure.code, failure.getMessage()));
            } catch (RuntimeException failure) {
                diagnostics.add(diagnostic(
                        "display_capture_failed",
                        "REI display could not be detached from category "
                                + category.getIdentifier()));
            }
        }
    }

    private RecipeEntrySnapshot detach(CategoryIdentifier<?> category, Display display) {
        Identifier categoryId = category.getIdentifier();
        if (!display.getCategoryIdentifier().getIdentifier().equals(categoryId)) {
            throw new UnsupportedRecipe(
                    "category_mismatch", "REI display belongs to another category");
        }
        CapturedEntries entries = captureEntries(display);
        Identifier location = display.getDisplayLocation().orElse(null);
        String provisionalId = location == null
                ? "openallay:rei_pending"
                : location.toString();
        RecipeEntrySnapshot provisional = entry(
                "openallay:rei_pending", provisionalId, categoryId, entries);
        String fingerprint = RecipeCanonicalizer.semanticFingerprint(provisional);
        String referenceId = referenceId(categoryId, location, fingerprint);
        String entryId = location == null
                ? "openallay:rei_fallback/" + fingerprint
                : location.toString();
        return entry(referenceId, entryId, categoryId, entries);
    }

    private CapturedEntries captureEntries(Display display) {
        List<IngredientRequirementSnapshot> ingredients = new ArrayList<>();
        List<FluidRequirementSnapshot> fluids = new ArrayList<>();
        List<RecipeOutputSnapshot> outputs = new ArrayList<>();
        int inputIndex = 0;
        for (EntryIngredient input : display.getInputEntries()) {
            if (input.isEmpty()) {
                continue;
            }
            if (allItems(input)) {
                ingredients.add(captureItemRequirement("input-" + inputIndex++, input));
            } else if (allFluids(input)) {
                fluids.add(captureFluid(input));
            } else {
                throw new UnsupportedRecipe(
                        "unsupported_ingredient", "REI input contains an unsupported entry kind");
            }
        }
        for (EntryIngredient output : display.getOutputEntries()) {
            if (!output.isEmpty()) {
                outputs.add(captureOutput(output));
            }
        }
        if (outputs.isEmpty()) {
            throw new UnsupportedRecipe(
                    "missing_output", "REI display has no representable item output");
        }
        return new CapturedEntries(ingredients, fluids, outputs);
    }

    private static IngredientRequirementSnapshot captureItemRequirement(
            String key, EntryIngredient values) {
        List<ItemStack> stacks = values.stream()
                .map(EntryStack::getValue)
                .map(ItemStack.class::cast)
                .filter(stack -> !stack.isEmpty())
                .toList();
        if (stacks.isEmpty()) {
            throw new UnsupportedRecipe("empty_ingredient", "REI item input is empty");
        }
        rejectComponents(stacks);
        int count = stacks.getFirst().getCount();
        if (count <= 0 || stacks.stream().anyMatch(stack -> stack.getCount() != count)) {
            throw new UnsupportedRecipe(
                    "alternative_count_mismatch", "REI item alternatives use different counts");
        }
        TreeMap<String, IngredientAlternativeSnapshot> alternatives = new TreeMap<>();
        stacks.forEach(stack -> {
            String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            alternatives.put(id, new IngredientAlternativeSnapshot("item", id, List.of(id)));
        });
        return new IngredientRequirementSnapshot(
                key, count, true, List.copyOf(alternatives.values()));
    }

    private static RecipeOutputSnapshot captureOutput(EntryIngredient values) {
        if (!allItems(values)) {
            throw new UnsupportedRecipe(
                    "unsupported_output", "REI output is not an item stack");
        }
        List<ItemStack> stacks = values.stream()
                .map(EntryStack::getValue)
                .map(ItemStack.class::cast)
                .filter(stack -> !stack.isEmpty())
                .toList();
        if (stacks.isEmpty()) {
            throw new UnsupportedRecipe("empty_output", "REI output item is empty");
        }
        rejectComponents(stacks);
        LinkedHashMap<String, ItemStack> unique = new LinkedHashMap<>();
        stacks.forEach(stack -> unique.putIfAbsent(
                BuiltInRegistries.ITEM.getKey(stack.getItem()) + "\u0000" + stack.getCount(), stack));
        if (unique.size() != 1) {
            throw new UnsupportedRecipe(
                    "alternative_output", "REI output contains alternatives");
        }
        ItemStack stack = unique.values().iterator().next();
        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        return new RecipeOutputSnapshot(
                new ItemStackSnapshot(id, stack.getCount(), stack.getHoverName().getString()), 1.0D);
    }

    private static FluidRequirementSnapshot captureFluid(EntryIngredient values) {
        List<FluidValue> fluids = values.stream()
                .map(EntryStack::getValue)
                .map(FluidStack.class::cast)
                .map(stack -> {
                    if (!stack.getPatch().isEmpty()) {
                        throw new UnsupportedRecipe(
                                "fluid_components_unsupported",
                                "REI fluid has components that cannot be represented losslessly");
                    }
                    return new FluidValue(
                            BuiltInRegistries.FLUID.getKey(stack.getFluid()).toString(),
                            stack.getAmount());
                })
                .distinct()
                .toList();
        if (fluids.size() != 1 || fluids.getFirst().amount <= 0) {
            throw new UnsupportedRecipe(
                    "alternative_fluid", "REI fluid input contains alternatives or an invalid amount");
        }
        FluidValue fluid = fluids.getFirst();
        return new FluidRequirementSnapshot(fluid.id, fluid.amount, true);
    }

    private static boolean allItems(EntryIngredient values) {
        return values.stream().allMatch(value -> value.getValue() instanceof ItemStack);
    }

    private static boolean allFluids(EntryIngredient values) {
        return values.stream().allMatch(value -> value.getValue() instanceof FluidStack);
    }

    private static void rejectComponents(List<ItemStack> stacks) {
        if (stacks.stream().anyMatch(stack -> !stack.getComponentsPatch().isEmpty())) {
            throw new UnsupportedRecipe(
                    "item_components_unsupported",
                    "REI item stack has components that cannot be represented losslessly");
        }
    }

    private RecipeEntrySnapshot entry(
            String referenceId, String entryId, Identifier categoryId, CapturedEntries entries) {
        return new RecipeEntrySnapshot(
                new RecipeReference(SOURCE_ID, CAPTURE_GENERATION_PLACEHOLDER, referenceId),
                entryId,
                categoryId.toString(),
                RecipeLayoutSnapshot.unknown(),
                null,
                entries.ingredients,
                List.of(),
                entries.fluids,
                entries.outputs,
                List.of(),
                RecipeProcessingSnapshot.unknown(),
                List.of(),
                Map.of(),
                RecipeUnlockState.UNKNOWN,
                evidence(categoryId));
    }

    private EvidenceMetadata evidence(Identifier categoryId) {
        return new EvidenceMetadata(
                DataAuthority.INTEGRATION_API,
                DataCompleteness.COMPLETE,
                capturedAt,
                SOURCE_ID,
                SOURCE_ID,
                platform.gameVersion(),
                platform.platformName(),
                Map.of("openallay:category", categoryId.toString()));
    }

    private static String referenceId(
            Identifier categoryId, Identifier location, String fingerprint) {
        StringBuilder path = new StringBuilder("rei/")
                .append(categoryId.getNamespace())
                .append('/')
                .append(categoryId.getPath());
        if (location != null) {
            path.append('/')
                    .append(location.getNamespace())
                    .append('/')
                    .append(location.getPath());
        } else {
            path.append("/generated");
        }
        path.append('/').append(fingerprint);
        return "openallay:" + path;
    }

    private static RecipeProviderDiagnostic diagnostic(String code, String message) {
        return new RecipeProviderDiagnostic(SOURCE_ID, code, message);
    }

    private record CapturedEntries(
            List<IngredientRequirementSnapshot> ingredients,
            List<FluidRequirementSnapshot> fluids,
            List<RecipeOutputSnapshot> outputs) {}

    private record FluidValue(String id, long amount) {}

    private static final class UnsupportedRecipe extends RuntimeException {
        private final String code;

        private UnsupportedRecipe(String code, String message) {
            super(message);
            this.code = code;
        }
    }
}
