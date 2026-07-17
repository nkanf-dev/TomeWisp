package dev.tomewisp.context;

public record RecipeLayoutSnapshot(int width, int height, boolean shaped) {
    public RecipeLayoutSnapshot {
        if (width < 0 || height < 0 || (width == 0) != (height == 0)) {
            throw new IllegalArgumentException("recipe layout dimensions are invalid");
        }
        if (shaped && width == 0) {
            throw new IllegalArgumentException("shaped recipe layout requires dimensions");
        }
    }

    public static RecipeLayoutSnapshot unknown() {
        return new RecipeLayoutSnapshot(0, 0, false);
    }
}
