package dev.openallay.guide.semantic;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.openallay.context.RecipeReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Strict durable codec for the OpenAllay-owned semantic AST. */
public final class SemanticDocumentCodec {
    private static final Set<String> DOCUMENT_FIELDS =
            Set.of("schemaVersion", "blocks", "fallbackText", "diagnostics");
    private static final Set<String> DIAGNOSTIC_FIELDS = Set.of("code", "nodeId");
    private static final Set<String> REFERENCE_FIELDS =
            Set.of("kind", "target", "label", "grounded", "originInvocationId");
    private static final Set<String> COMPONENT_FIELDS =
            Set.of("type", "nodeId", "properties", "fallbackText", "narration");

    public String encode(SemanticDocument document) {
        return encodeObject(document).toString();
    }

    public JsonObject encodeObject(SemanticDocument document) {
        java.util.Objects.requireNonNull(document, "document");
        requireUniqueNodeIds(document);
        JsonObject object = new JsonObject();
        object.addProperty("schemaVersion", document.schemaVersion());
        object.add("blocks", blocks(document.blocks()));
        object.addProperty("fallbackText", document.fallbackText());
        JsonArray diagnostics = new JsonArray();
        for (SemanticDiagnostic diagnostic : document.diagnostics()) {
            JsonObject encoded = new JsonObject();
            encoded.addProperty("code", diagnostic.code());
            encoded.addProperty("nodeId", diagnostic.nodeId());
            diagnostics.add(encoded);
        }
        object.add("diagnostics", diagnostics);
        return object;
    }

    public SemanticDocument decode(String json) {
        return decodeObject(object(JsonParser.parseString(json), "semantic document"));
    }

    public SemanticDocument decodeObject(JsonObject object) {
        exact(object, DOCUMENT_FIELDS, "semantic document");
        int version = integer(object, "schemaVersion");
        if (version != SemanticDocument.SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported semantic document schema " + version);
        }
        List<SemanticDiagnostic> diagnostics = new ArrayList<>();
        for (JsonElement value : array(object, "diagnostics")) {
            JsonObject encoded = object(value, "semantic diagnostic");
            exact(encoded, DIAGNOSTIC_FIELDS, "semantic diagnostic");
            diagnostics.add(new SemanticDiagnostic(
                    string(encoded, "code"), string(encoded, "nodeId")));
        }
        SemanticDocument document = new SemanticDocument(
                version,
                decodeBlocks(array(object, "blocks")),
                string(object, "fallbackText"),
                diagnostics);
        requireUniqueNodeIds(document);
        return document;
    }

    private static JsonArray blocks(List<SemanticBlock> values) {
        JsonArray encoded = new JsonArray();
        values.forEach(value -> encoded.add(block(value)));
        return encoded;
    }

    private static JsonObject block(SemanticBlock value) {
        JsonObject object = typed(value.nodeId());
        switch (value) {
            case SemanticBlock.Paragraph paragraph -> {
                object.addProperty("type", "paragraph");
                object.add("content", inlines(paragraph.content()));
            }
            case SemanticBlock.Heading heading -> {
                object.addProperty("type", "heading");
                object.addProperty("level", heading.level());
                object.add("content", inlines(heading.content()));
            }
            case SemanticBlock.ListBlock list -> {
                object.addProperty("type", "list");
                object.addProperty("ordered", list.ordered());
                object.addProperty("start", list.start());
                JsonArray items = new JsonArray();
                list.items().forEach(item -> items.add(blocks(item)));
                object.add("items", items);
            }
            case SemanticBlock.Quote quote -> {
                object.addProperty("type", "quote");
                object.add("content", blocks(quote.content()));
            }
            case SemanticBlock.CodeBlock code -> {
                object.addProperty("type", "code");
                object.addProperty("info", code.info());
                object.addProperty("code", code.code());
            }
            case SemanticBlock.Table table -> {
                object.addProperty("type", "table");
                object.add("header", row(table.header()));
                JsonArray rows = new JsonArray();
                table.rows().forEach(valueRow -> rows.add(row(valueRow)));
                object.add("rows", rows);
            }
            case SemanticBlock.ThematicBreak ignored ->
                    object.addProperty("type", "thematic_break");
            case SemanticBlock.Component component -> {
                object.addProperty("type", "component");
                object.add("component", component(component.component()));
            }
        }
        return object;
    }

    private static List<SemanticBlock> decodeBlocks(JsonArray values) {
        List<SemanticBlock> decoded = new ArrayList<>();
        for (JsonElement value : values) {
            JsonObject object = object(value, "semantic block");
            String type = string(object, "type");
            String nodeId = string(object, "nodeId");
            decoded.add(switch (type) {
                case "paragraph" -> {
                    exact(object, Set.of("type", "nodeId", "content"), "paragraph");
                    yield new SemanticBlock.Paragraph(
                            nodeId, decodeInlines(array(object, "content")));
                }
                case "heading" -> {
                    exact(object, Set.of("type", "nodeId", "level", "content"), "heading");
                    yield new SemanticBlock.Heading(
                            nodeId, integer(object, "level"),
                            decodeInlines(array(object, "content")));
                }
                case "list" -> {
                    exact(object, Set.of("type", "nodeId", "ordered", "start", "items"), "list");
                    List<List<SemanticBlock>> items = new ArrayList<>();
                    for (JsonElement item : array(object, "items")) {
                        if (!item.isJsonArray()) {
                            throw new IllegalArgumentException("semantic list item must be an array");
                        }
                        items.add(decodeBlocks(item.getAsJsonArray()));
                    }
                    yield new SemanticBlock.ListBlock(
                            nodeId, bool(object, "ordered"), integer(object, "start"), items);
                }
                case "quote" -> {
                    exact(object, Set.of("type", "nodeId", "content"), "quote");
                    yield new SemanticBlock.Quote(nodeId, decodeBlocks(array(object, "content")));
                }
                case "code" -> {
                    exact(object, Set.of("type", "nodeId", "info", "code"), "code block");
                    yield new SemanticBlock.CodeBlock(
                            nodeId, string(object, "info"), string(object, "code"));
                }
                case "table" -> {
                    exact(object, Set.of("type", "nodeId", "header", "rows"), "table");
                    List<SemanticBlock.TableRow> rows = new ArrayList<>();
                    for (JsonElement row : array(object, "rows")) {
                        rows.add(decodeRow(object(row, "table row")));
                    }
                    yield new SemanticBlock.Table(
                            nodeId,
                            decodeRow(object(object.get("header"), "table header")),
                            rows);
                }
                case "thematic_break" -> {
                    exact(object, Set.of("type", "nodeId"), "thematic break");
                    yield new SemanticBlock.ThematicBreak(nodeId);
                }
                case "component" -> {
                    exact(object, Set.of("type", "nodeId", "component"), "component block");
                    yield new SemanticBlock.Component(
                            nodeId, decodeComponent(object(object.get("component"), "component")));
                }
                default -> throw new IllegalArgumentException("unknown semantic block type " + type);
            });
        }
        return List.copyOf(decoded);
    }

    private static JsonArray inlines(List<SemanticInline> values) {
        JsonArray encoded = new JsonArray();
        for (SemanticInline value : values) {
            JsonObject object = typed(value.nodeId());
            switch (value) {
                case SemanticInline.Text text -> {
                    object.addProperty("type", "text");
                    object.addProperty("text", text.text());
                }
                case SemanticInline.Emphasis emphasis -> {
                    object.addProperty("type", "emphasis");
                    object.add("children", inlines(emphasis.children()));
                }
                case SemanticInline.Strong strong -> {
                    object.addProperty("type", "strong");
                    object.add("children", inlines(strong.children()));
                }
                case SemanticInline.Code code -> {
                    object.addProperty("type", "code");
                    object.addProperty("text", code.text());
                }
                case SemanticInline.Break lineBreak -> {
                    object.addProperty("type", "break");
                    object.addProperty("hard", lineBreak.hard());
                }
                case SemanticInline.Reference reference -> {
                    object.addProperty("type", "reference");
                    object.add("reference", reference(reference.reference()));
                }
            }
            encoded.add(object);
        }
        return encoded;
    }

    private static List<SemanticInline> decodeInlines(JsonArray values) {
        List<SemanticInline> decoded = new ArrayList<>();
        for (JsonElement value : values) {
            JsonObject object = object(value, "semantic inline");
            String type = string(object, "type");
            String nodeId = string(object, "nodeId");
            decoded.add(switch (type) {
                case "text" -> {
                    exact(object, Set.of("type", "nodeId", "text"), "text inline");
                    yield new SemanticInline.Text(nodeId, string(object, "text"));
                }
                case "emphasis" -> {
                    exact(object, Set.of("type", "nodeId", "children"), "emphasis inline");
                    yield new SemanticInline.Emphasis(
                            nodeId, decodeInlines(array(object, "children")));
                }
                case "strong" -> {
                    exact(object, Set.of("type", "nodeId", "children"), "strong inline");
                    yield new SemanticInline.Strong(
                            nodeId, decodeInlines(array(object, "children")));
                }
                case "code" -> {
                    exact(object, Set.of("type", "nodeId", "text"), "code inline");
                    yield new SemanticInline.Code(nodeId, string(object, "text"));
                }
                case "break" -> {
                    exact(object, Set.of("type", "nodeId", "hard"), "break inline");
                    yield new SemanticInline.Break(nodeId, bool(object, "hard"));
                }
                case "reference" -> {
                    exact(object, Set.of("type", "nodeId", "reference"), "reference inline");
                    yield new SemanticInline.Reference(
                            nodeId, decodeReference(object(object.get("reference"), "reference")));
                }
                default -> throw new IllegalArgumentException("unknown semantic inline type " + type);
            });
        }
        return List.copyOf(decoded);
    }

    private static JsonObject reference(SemanticReference value) {
        JsonObject object = new JsonObject();
        object.addProperty("kind", value.kind().name());
        object.addProperty("target", value.target());
        object.addProperty("label", value.label());
        object.addProperty("grounded", value.grounded());
        if (value.originInvocationId() == null) {
            object.add("originInvocationId", JsonNull.INSTANCE);
        } else {
            object.addProperty("originInvocationId", value.originInvocationId());
        }
        return object;
    }

    private static SemanticReference decodeReference(JsonObject object) {
        exact(object, REFERENCE_FIELDS, "semantic reference");
        return new SemanticReference(
                enumValue(SemanticReferenceKind.class, string(object, "kind"), "reference kind"),
                string(object, "target"),
                string(object, "label"),
                bool(object, "grounded"),
                nullableString(object, "originInvocationId"));
    }

    private static JsonObject row(SemanticBlock.TableRow value) {
        JsonObject object = new JsonObject();
        JsonArray cells = new JsonArray();
        for (SemanticBlock.TableCell cell : value.cells()) {
            JsonObject encoded = new JsonObject();
            encoded.addProperty("alignment", cell.alignment().name());
            encoded.add("content", inlines(cell.content()));
            cells.add(encoded);
        }
        object.add("cells", cells);
        return object;
    }

    private static SemanticBlock.TableRow decodeRow(JsonObject object) {
        exact(object, Set.of("cells"), "table row");
        List<SemanticBlock.TableCell> cells = new ArrayList<>();
        for (JsonElement value : array(object, "cells")) {
            JsonObject cell = object(value, "table cell");
            exact(cell, Set.of("alignment", "content"), "table cell");
            cells.add(new SemanticBlock.TableCell(
                    enumValue(SemanticBlock.Alignment.class,
                            string(cell, "alignment"), "table alignment"),
                    decodeInlines(array(cell, "content"))));
        }
        return new SemanticBlock.TableRow(cells);
    }

    private static JsonObject component(RichComponent value) {
        JsonObject object = new JsonObject();
        object.addProperty("type", componentType(value));
        object.addProperty("nodeId", value.nodeId());
        object.add("properties", componentProperties(value));
        object.addProperty("fallbackText", value.fallbackText());
        object.addProperty("narration", value.narration());
        return object;
    }

    private static String componentType(RichComponent value) {
        return switch (value) {
            case RichComponent.ItemRow ignored -> "item_row";
            case RichComponent.RecipeGrid ignored -> "recipe_grid";
            case RichComponent.IngredientCheck ignored -> "ingredient_check";
            case RichComponent.CraftabilitySummary ignored -> "craftability_summary";
            case RichComponent.ProgressSteps ignored -> "progress_steps";
            case RichComponent.SourceSummary ignored -> "source_summary";
            case RichComponent.StatusBadge ignored -> "status_badge";
            case RichComponent.ChoiceGroup ignored -> "choice_group";
        };
    }

    private static JsonObject componentProperties(RichComponent value) {
        JsonObject properties = new JsonObject();
        switch (value) {
            case RichComponent.ItemRow row -> {
                JsonArray items = new JsonArray();
                row.items().forEach(item -> items.add(item(item)));
                properties.add("items", items);
            }
            case RichComponent.RecipeGrid grid -> {
                recipe(properties, grid.recipe(), grid.originInvocationId());
                properties.addProperty("label", grid.label());
            }
            case RichComponent.IngredientCheck check -> {
                JsonArray ingredients = new JsonArray();
                for (RichComponent.Ingredient ingredient : check.ingredients()) {
                    JsonObject item = new JsonObject();
                    item.addProperty("itemId", ingredient.itemId());
                    item.addProperty("required", ingredient.required());
                    item.addProperty("available", ingredient.available());
                    item.addProperty("label", ingredient.label());
                    item.addProperty("originInvocationId", ingredient.originInvocationId());
                    ingredients.add(item);
                }
                properties.add("ingredients", ingredients);
            }
            case RichComponent.CraftabilitySummary summary -> {
                recipe(properties, summary.recipe(), summary.originInvocationId());
                properties.addProperty("craftable", summary.craftable());
                properties.addProperty("conclusive", summary.conclusive());
                properties.addProperty("requestedCrafts", summary.requestedCrafts());
                properties.addProperty("maximumCrafts", summary.maximumCrafts());
            }
            case RichComponent.ProgressSteps progress -> {
                JsonArray steps = new JsonArray();
                for (RichComponent.Step step : progress.steps()) {
                    JsonObject encoded = new JsonObject();
                    encoded.addProperty("id", step.id());
                    encoded.addProperty("label", step.label());
                    encoded.addProperty("state", step.state().name());
                    steps.add(encoded);
                }
                properties.add("steps", steps);
            }
            case RichComponent.SourceSummary summary -> {
                JsonArray sources = new JsonArray();
                for (RichComponent.Source source : summary.sources()) {
                    JsonObject encoded = new JsonObject();
                    encoded.addProperty("sourceId", source.sourceId());
                    encoded.addProperty("label", source.label());
                    encoded.addProperty("originInvocationId", source.originInvocationId());
                    sources.add(encoded);
                }
                properties.add("sources", sources);
            }
            case RichComponent.StatusBadge badge -> {
                properties.addProperty("state", badge.state().name());
                properties.addProperty("label", badge.label());
            }
            case RichComponent.ChoiceGroup group -> {
                properties.addProperty("prompt", group.prompt());
                JsonArray choices = new JsonArray();
                for (RichComponent.Choice choice : group.choices()) {
                    JsonObject encoded = new JsonObject();
                    encoded.addProperty("id", choice.id());
                    encoded.addProperty("label", choice.label());
                    choices.add(encoded);
                }
                properties.add("choices", choices);
            }
        }
        return properties;
    }

    private static RichComponent decodeComponent(JsonObject object) {
        exact(object, COMPONENT_FIELDS, "rich component");
        String type = string(object, "type");
        String nodeId = string(object, "nodeId");
        String fallback = string(object, "fallbackText");
        String narration = string(object, "narration");
        JsonObject properties = object(object.get("properties"), "component properties");
        return switch (type) {
            case "item_row" -> {
                exact(properties, Set.of("items"), "item row properties");
                List<RichComponent.Item> items = new ArrayList<>();
                for (JsonElement value : array(properties, "items")) {
                    JsonObject item = object(value, "component item");
                    exact(item, Set.of("itemId", "count", "label", "originInvocationId"), "component item");
                    items.add(new RichComponent.Item(
                            string(item, "itemId"), longValue(item, "count"),
                            string(item, "label"), string(item, "originInvocationId")));
                }
                yield new RichComponent.ItemRow(nodeId, items, fallback, narration);
            }
            case "recipe_grid" -> {
                exact(properties, Set.of("sourceId", "generation", "recipeId", "originInvocationId", "label"), "recipe grid properties");
                yield new RichComponent.RecipeGrid(
                        nodeId, recipe(properties), string(properties, "originInvocationId"),
                        string(properties, "label"), fallback, narration);
            }
            case "ingredient_check" -> {
                exact(properties, Set.of("ingredients"), "ingredient check properties");
                List<RichComponent.Ingredient> ingredients = new ArrayList<>();
                for (JsonElement value : array(properties, "ingredients")) {
                    JsonObject item = object(value, "component ingredient");
                    exact(item, Set.of("itemId", "required", "available", "label", "originInvocationId"), "component ingredient");
                    ingredients.add(new RichComponent.Ingredient(
                            string(item, "itemId"), longValue(item, "required"),
                            longValue(item, "available"), string(item, "label"),
                            string(item, "originInvocationId")));
                }
                yield new RichComponent.IngredientCheck(nodeId, ingredients, fallback, narration);
            }
            case "craftability_summary" -> {
                exact(properties, Set.of("sourceId", "generation", "recipeId", "originInvocationId", "craftable", "conclusive", "requestedCrafts", "maximumCrafts"), "craftability properties");
                yield new RichComponent.CraftabilitySummary(
                        nodeId, recipe(properties), string(properties, "originInvocationId"),
                        bool(properties, "craftable"), bool(properties, "conclusive"),
                        longValue(properties, "requestedCrafts"),
                        longValue(properties, "maximumCrafts"), fallback, narration);
            }
            case "progress_steps" -> {
                exact(properties, Set.of("steps"), "progress properties");
                List<RichComponent.Step> steps = new ArrayList<>();
                for (JsonElement value : array(properties, "steps")) {
                    JsonObject step = object(value, "component step");
                    exact(step, Set.of("id", "label", "state"), "component step");
                    steps.add(new RichComponent.Step(
                            string(step, "id"), string(step, "label"),
                            enumValue(RichComponent.StepState.class,
                                    string(step, "state"), "step state")));
                }
                yield new RichComponent.ProgressSteps(nodeId, steps, fallback, narration);
            }
            case "source_summary" -> {
                exact(properties, Set.of("sources"), "source summary properties");
                List<RichComponent.Source> sources = new ArrayList<>();
                for (JsonElement value : array(properties, "sources")) {
                    JsonObject source = object(value, "component source");
                    exact(source, Set.of("sourceId", "label", "originInvocationId"), "component source");
                    sources.add(new RichComponent.Source(
                            string(source, "sourceId"), string(source, "label"),
                            string(source, "originInvocationId")));
                }
                yield new RichComponent.SourceSummary(nodeId, sources, fallback, narration);
            }
            case "status_badge" -> {
                exact(properties, Set.of("state", "label"), "status badge properties");
                yield new RichComponent.StatusBadge(
                        nodeId,
                        enumValue(RichComponent.BadgeState.class,
                                string(properties, "state"), "badge state"),
                        string(properties, "label"), fallback, narration);
            }
            case "choice_group" -> {
                exact(properties, Set.of("prompt", "choices"), "choice group properties");
                List<RichComponent.Choice> choices = new ArrayList<>();
                for (JsonElement value : array(properties, "choices")) {
                    JsonObject choice = object(value, "component choice");
                    exact(choice, Set.of("id", "label"), "component choice");
                    choices.add(new RichComponent.Choice(
                            string(choice, "id"), string(choice, "label")));
                }
                yield new RichComponent.ChoiceGroup(
                        nodeId, string(properties, "prompt"), choices, fallback, narration);
            }
            default -> throw new IllegalArgumentException("unknown rich component type " + type);
        };
    }

    private static JsonObject item(RichComponent.Item value) {
        JsonObject item = new JsonObject();
        item.addProperty("itemId", value.itemId());
        item.addProperty("count", value.count());
        item.addProperty("label", value.label());
        item.addProperty("originInvocationId", value.originInvocationId());
        return item;
    }

    private static void recipe(JsonObject object, RecipeReference recipe, String origin) {
        object.addProperty("sourceId", recipe.sourceId());
        object.addProperty("generation", recipe.generation());
        object.addProperty("recipeId", recipe.recipeId());
        object.addProperty("originInvocationId", origin);
    }

    private static RecipeReference recipe(JsonObject object) {
        return new RecipeReference(
                string(object, "sourceId"), string(object, "generation"),
                string(object, "recipeId"));
    }

    private static JsonObject typed(String nodeId) {
        JsonObject object = new JsonObject();
        object.addProperty("nodeId", nodeId);
        return object;
    }

    private static void exact(JsonObject object, Set<String> expected, String label) {
        if (!object.keySet().equals(expected)) {
            Set<String> missing = new java.util.TreeSet<>(expected);
            missing.removeAll(object.keySet());
            Set<String> extra = new java.util.TreeSet<>(object.keySet());
            extra.removeAll(expected);
            throw new IllegalArgumentException(
                    label + " schema mismatch; missing=" + missing + ", extra=" + extra);
        }
    }

    private static JsonObject object(JsonElement value, String label) {
        if (value == null || !value.isJsonObject()) {
            throw new IllegalArgumentException(label + " must be an object");
        }
        return value.getAsJsonObject();
    }

    private static JsonArray array(JsonObject object, String field) {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonArray()) {
            throw new IllegalArgumentException(field + " must be an array");
        }
        return value.getAsJsonArray();
    }

    private static String string(JsonObject object, String field) {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException(field + " must be text");
        }
        return value.getAsString();
    }

    private static String nullableString(JsonObject object, String field) {
        JsonElement value = object.get(field);
        if (value == null) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.isJsonNull() ? null : string(object, field);
    }

    private static int integer(JsonObject object, String field) {
        long value = longValue(object, field);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(field + " must be an integer");
        }
        return (int) value;
    }

    private static long longValue(JsonObject object, String field) {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException(field + " must be an integer");
        }
        try {
            return value.getAsBigDecimal().longValueExact();
        } catch (ArithmeticException | NumberFormatException failure) {
            throw new IllegalArgumentException(field + " must be an integer", failure);
        }
    }

    private static boolean bool(JsonObject object, String field) {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isBoolean()) {
            throw new IllegalArgumentException(field + " must be boolean");
        }
        return value.getAsBoolean();
    }

    private static <E extends Enum<E>> E enumValue(
            Class<E> type, String value, String label) {
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("unknown semantic " + label + " " + value, failure);
        }
    }

    private static void requireUniqueNodeIds(SemanticDocument document) {
        java.util.HashSet<String> ids = new java.util.HashSet<>();
        document.blocks().forEach(block -> collect(block, ids));
        for (SemanticDiagnostic diagnostic : document.diagnostics()) {
            if (!ids.contains(diagnostic.nodeId())) {
                throw new IllegalArgumentException(
                        "semantic diagnostic refers to an unknown node");
            }
        }
    }

    private static void collect(SemanticBlock block, Set<String> ids) {
        add(block.nodeId(), ids);
        switch (block) {
            case SemanticBlock.Paragraph paragraph -> paragraph.content()
                    .forEach(value -> collect(value, ids));
            case SemanticBlock.Heading heading -> heading.content()
                    .forEach(value -> collect(value, ids));
            case SemanticBlock.ListBlock list -> list.items()
                    .forEach(item -> item.forEach(value -> collect(value, ids)));
            case SemanticBlock.Quote quote -> quote.content()
                    .forEach(value -> collect(value, ids));
            case SemanticBlock.Table table -> {
                collect(table.header(), ids);
                table.rows().forEach(row -> collect(row, ids));
            }
            case SemanticBlock.CodeBlock ignored -> { }
            case SemanticBlock.ThematicBreak ignored -> { }
            case SemanticBlock.Component ignored -> { }
        }
    }

    private static void collect(SemanticBlock.TableRow row, Set<String> ids) {
        row.cells().forEach(cell -> cell.content().forEach(value -> collect(value, ids)));
    }

    private static void collect(SemanticInline inline, Set<String> ids) {
        add(inline.nodeId(), ids);
        switch (inline) {
            case SemanticInline.Emphasis emphasis -> emphasis.children()
                    .forEach(value -> collect(value, ids));
            case SemanticInline.Strong strong -> strong.children()
                    .forEach(value -> collect(value, ids));
            case SemanticInline.Text ignored -> { }
            case SemanticInline.Code ignored -> { }
            case SemanticInline.Break ignored -> { }
            case SemanticInline.Reference ignored -> { }
        }
    }

    private static void add(String nodeId, Set<String> ids) {
        if (!ids.add(nodeId)) {
            throw new IllegalArgumentException("semantic node IDs must be unique");
        }
    }
}
