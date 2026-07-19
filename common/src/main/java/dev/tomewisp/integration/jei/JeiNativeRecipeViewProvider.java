package dev.tomewisp.integration.jei;

import dev.tomewisp.client.gui.nativeview.NativeDomainView;
import dev.tomewisp.client.gui.nativeview.NativeDomainViewBinding;
import dev.tomewisp.client.gui.nativeview.NativeDomainViewProvider;
import dev.tomewisp.context.RecipeReference;
import dev.tomewisp.platform.PlatformServices;
import dev.tomewisp.recipe.RecipeProviderSnapshot;
import dev.tomewisp.recipe.RecipeProviderState;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.runtime.IJeiRuntime;

/** Exact JEI layout embedding, loaded only from the JEI plugin bridge. */
final class JeiNativeRecipeViewProvider implements NativeDomainViewProvider {
    private final Supplier<IJeiRuntime> runtime;

    JeiNativeRecipeViewProvider(Supplier<IJeiRuntime> runtime) {
        this.runtime = java.util.Objects.requireNonNull(runtime, "runtime");
    }

    @Override
    public String providerId() {
        return "viewer:jei";
    }

    @Override
    public int priority() {
        return 300;
    }

    @Override
    public boolean supports(NativeDomainViewBinding binding) {
        return binding instanceof NativeDomainViewBinding.Recipe recipe
                && recipe.recipe().references().stream()
                        .anyMatch(reference -> reference.sourceId().equals("viewer:jei"));
    }

    @Override
    public Attempt create(NativeDomainViewBinding binding) {
        IJeiRuntime current = runtime.get();
        if (current == null) return new Attempt.Unsupported("native_view_unavailable");
        NativeDomainViewBinding.Recipe recipe = (NativeDomainViewBinding.Recipe) binding;
        RecipeReference exact = recipe.recipe().references().stream()
                .filter(reference -> reference.sourceId().equals("viewer:jei"))
                .findFirst().orElseThrow();
        JeiRecipeProvider references = new JeiRecipeProvider(
                current, Instant.EPOCH, PlatformServices.load());
        RecipeProviderSnapshot snapshot = references.capture();
        if (!currentGenerationContains(snapshot, exact)) {
            return new Attempt.Unsupported("stale_reference");
        }
        IRecipeLayoutDrawable<?> layout = find(current, references, exact).orElse(null);
        return layout == null
                ? new Attempt.Unsupported("native_view_unavailable")
                : new Attempt.Ready(new View(layout, runtime, current));
    }

    static boolean currentGenerationContains(
            RecipeProviderSnapshot snapshot, RecipeReference exact) {
        return snapshot.state() == RecipeProviderState.AVAILABLE
                && exact.sourceId().equals(snapshot.sourceId())
                && exact.generation().equals(snapshot.generation())
                && snapshot.recipes().stream()
                        .anyMatch(recipe -> recipe.reference().equals(exact));
    }

    private static Optional<IRecipeLayoutDrawable<?>> find(
            IJeiRuntime runtime, JeiRecipeProvider references, RecipeReference exact) {
        List<IRecipeCategory<?>> categories = runtime.getRecipeManager()
                .createRecipeCategoryLookup()
                .includeHidden()
                .get()
                .toList();
        for (IRecipeCategory<?> category : categories) {
            Optional<IRecipeLayoutDrawable<?>> found = findInCategory(
                    runtime, references, category, exact.recipeId());
            if (found.isPresent()) return found;
        }
        return Optional.empty();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Optional<IRecipeLayoutDrawable<?>> findInCategory(
            IJeiRuntime runtime,
            JeiRecipeProvider references,
            IRecipeCategory category,
            String recipeId) {
        List<Object> recipes = runtime.getRecipeManager()
                .createRecipeLookup(category.getRecipeType())
                .includeHidden()
                .get()
                .map(value -> (Object) value)
                .toList();
        for (Object recipe : recipes) {
            if (!references.referenceIdIfSupported(category, recipe)
                    .filter(recipeId::equals)
                    .isPresent()) {
                continue;
            }
            return (Optional) runtime.getRecipeManager().createRecipeLayoutDrawable(
                    category,
                    recipe,
                    runtime.getJeiHelpers().getFocusFactory().getEmptyFocusGroup());
        }
        return Optional.empty();
    }

    private record View(
            IRecipeLayoutDrawable<?> layout,
            Supplier<IJeiRuntime> runtime,
            IJeiRuntime capturedRuntime) implements NativeDomainView {
        @Override
        public String providerId() {
            return "viewer:jei";
        }

        @Override
        public NativeDomainViewBinding.Family family() {
            return NativeDomainViewBinding.Family.RECIPE;
        }

        @Override
        public void tick() {
            if (runtime.get() != capturedRuntime) {
                throw new IllegalStateException("JEI runtime generation changed");
            }
            layout.tick();
        }

        @Override
        public void render(RenderContext context) {
            int layoutWidth = layout.getRecipeCategory().getWidth();
            int layoutHeight = layout.getRecipeCategory().getHeight();
            int contentHeight = context.bounds().height() - 16;
            if (layoutWidth > context.bounds().width()
                    || layoutHeight > contentHeight) {
                throw new IllegalStateException("JEI layout exceeds native component bounds");
            }
            int x = context.bounds().x() + Math.max(0, (context.bounds().width() - layoutWidth) / 2);
            int y = context.bounds().y() + Math.max(0, (contentHeight - layoutHeight) / 2);
            layout.setPosition(x, y);
            layout.drawRecipe(context.graphics(), context.mouseX(), context.mouseY());
            layout.drawOverlays(context.graphics(), context.mouseX(), context.mouseY());
        }
    }
}
