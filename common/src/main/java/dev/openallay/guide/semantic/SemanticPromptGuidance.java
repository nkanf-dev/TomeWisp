package dev.openallay.guide.semantic;

/** Provider-neutral output guidance for OpenAllay's closed player presentation language. */
public final class SemanticPromptGuidance {
    private SemanticPromptGuidance() {}

    public static String text() {
        StringBuilder catalog = new StringBuilder();
        for (BuiltinRichComponents.PromptCatalogEntry entry
                : BuiltinRichComponents.promptCatalog()) {
            catalog.append("- ").append(entry.type()).append(": ")
                    .append(entry.guidance()).append('\n');
        }
        return """
                Player-visible output may use ordinary CommonMark prose and two OpenAllay-only syntaxes.
                Inline references use [[tw:<kind>|<target>|<optional label>]]. Prefer exact handles returned by tools.
                Raw item, block, fluid, entity, biome, dimension, tag, or key IDs are presentation only and do not create evidence.
                Controlled components use one fenced openallay-component JSON object with exactly these envelope fields:
                {"schemaVersion":1,"type":string,"properties":object,"fallback":string,"narration":string}.
                fallback and narration must be non-empty player-readable text. Do not add envelope or properties fields.
                Referenced items, recipes, and sources are accepted only when their exact IDs/handles came from Tool evidence in this request; never invent or repair them.
                A recipe_grid must copy the complete exact recipe handle from the same request's Tool result. Never author slots, coordinates, textures, widget names, or a recipe layout; OpenAllay binds the handle to trusted native recipe data.
                Use only a component in this closed catalog and follow its exact properties contract:
                %s
                A controlled component is presentation only: it never adds factual authority, permissions, callbacks, or execution.
                Do not emit HTML, links, URLs, scripts, callbacks, commands, executable code, arbitrary component types, or invented handles.
                Use plain prose when a component would not make the answer clearer.
                """.formatted(catalog.toString().stripTrailing());
    }
}
