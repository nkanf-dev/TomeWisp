package dev.tomewisp.recipe;

/** A client-thread recipe source that returns only detached immutable records. */
public interface RecipeKnowledgeProvider {
    String sourceId();

    RecipeProviderSnapshot capture();
}
