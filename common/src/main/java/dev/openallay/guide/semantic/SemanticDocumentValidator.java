package dev.openallay.guide.semantic;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Revalidates that grounded nodes belong to the target request before publication. */
public final class SemanticDocumentValidator {
    public SemanticDocument validate(
            UUID requestId, SemanticDocument document, SemanticReferenceIndex references) {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(document, "document");
        Objects.requireNonNull(references, "references");
        if (!requestId.equals(references.requestId())) {
            throw new IllegalArgumentException("semantic reference index belongs to another request");
        }
        for (SemanticBlock block : document.blocks()) {
            validateBlock(block, references);
        }
        return document;
    }

    private static void validateBlock(
            SemanticBlock block, SemanticReferenceIndex references) {
        switch (block) {
            case SemanticBlock.Paragraph paragraph -> validateInlines(paragraph.content(), references);
            case SemanticBlock.Heading heading -> validateInlines(heading.content(), references);
            case SemanticBlock.ListBlock list -> list.items().forEach(item ->
                    item.forEach(value -> validateBlock(value, references)));
            case SemanticBlock.Quote quote -> quote.content().forEach(value ->
                    validateBlock(value, references));
            case SemanticBlock.Table table -> {
                validateRow(table.header(), references);
                table.rows().forEach(row -> validateRow(row, references));
            }
            case SemanticBlock.CodeBlock ignored -> { }
            case SemanticBlock.ThematicBreak ignored -> { }
            case SemanticBlock.Component component -> validateComponent(component.component(), references);
        }
    }

    private static void validateRow(
            SemanticBlock.TableRow row, SemanticReferenceIndex references) {
        row.cells().forEach(cell -> validateInlines(cell.content(), references));
    }

    private static void validateInlines(
            List<SemanticInline> inlines, SemanticReferenceIndex references) {
        for (SemanticInline inline : inlines) {
            switch (inline) {
                case SemanticInline.Reference value -> validateReference(value.reference(), references);
                case SemanticInline.Emphasis value -> validateInlines(value.children(), references);
                case SemanticInline.Strong value -> validateInlines(value.children(), references);
                case SemanticInline.Text ignored -> { }
                case SemanticInline.Code ignored -> { }
                case SemanticInline.Break ignored -> { }
            }
        }
    }

    private static void validateReference(
            SemanticReference reference, SemanticReferenceIndex references) {
        if (reference.grounded()
                && !references.origin(reference.kind(), reference.target())
                        .filter(reference.originInvocationId()::equals).isPresent()) {
            throw new IllegalArgumentException("grounded semantic reference is stale or foreign");
        }
    }

    private static void validateComponent(
            RichComponent component, SemanticReferenceIndex references) {
        switch (component) {
            case RichComponent.ItemRow row -> row.items().forEach(item -> require(
                    references, SemanticReferenceKind.ITEM,
                    item.itemId(), item.originInvocationId()));
            case RichComponent.RecipeGrid recipe -> requireRecipe(
                    references, recipe.recipe(), recipe.originInvocationId());
            case RichComponent.IngredientCheck check -> check.ingredients().forEach(item -> require(
                    references, SemanticReferenceKind.ITEM,
                    item.itemId(), item.originInvocationId()));
            case RichComponent.CraftabilitySummary summary -> requireRecipe(
                    references, summary.recipe(), summary.originInvocationId());
            case RichComponent.SourceSummary summary -> summary.sources().forEach(source -> require(
                    references, SemanticReferenceKind.SOURCE,
                    source.sourceId(), source.originInvocationId()));
            case RichComponent.ProgressSteps ignored -> { }
            case RichComponent.StatusBadge ignored -> { }
            case RichComponent.ChoiceGroup ignored -> { }
        }
    }

    private static void requireRecipe(
            SemanticReferenceIndex references,
            dev.openallay.context.RecipeReference recipe,
            String origin) {
        require(references, SemanticReferenceKind.RECIPE,
                RecipeSemanticHandle.encode(recipe), origin);
    }

    private static void require(
            SemanticReferenceIndex references,
            SemanticReferenceKind kind,
            String target,
            String origin) {
        if (!references.origin(kind, target).filter(origin::equals).isPresent()) {
            throw new IllegalArgumentException("component reference is stale or foreign");
        }
    }
}
