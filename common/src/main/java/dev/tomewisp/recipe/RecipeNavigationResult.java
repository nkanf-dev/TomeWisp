package dev.tomewisp.recipe;

public record RecipeNavigationResult(boolean opened, String code, String message) {
    public RecipeNavigationResult {
        if (code == null || !code.matches("[a-z0-9_]+") || message == null || message.isBlank()) {
            throw new IllegalArgumentException("recipe navigation result is invalid");
        }
    }

    public static RecipeNavigationResult success() {
        return new RecipeNavigationResult(true, "opened", "Recipe viewer opened");
    }

    public static RecipeNavigationResult failed(String code, String message) {
        return new RecipeNavigationResult(false, code, message);
    }
}
