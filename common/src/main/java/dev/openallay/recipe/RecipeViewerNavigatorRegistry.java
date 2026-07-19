package dev.openallay.recipe;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

public final class RecipeViewerNavigatorRegistry {
    private static final Map<String, RecipeViewerNavigator> NAVIGATORS = new TreeMap<>();

    private RecipeViewerNavigatorRegistry() {}

    public static synchronized void register(RecipeViewerNavigator navigator) {
        Objects.requireNonNull(navigator, "navigator");
        NAVIGATORS.put(
                dev.openallay.context.RecipeReference.requireSourceId(navigator.viewerId()),
                navigator);
    }

    public static synchronized List<RecipeViewerNavigator> navigators() {
        return List.copyOf(NAVIGATORS.values());
    }
}
