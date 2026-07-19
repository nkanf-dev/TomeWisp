package dev.tomewisp.guide.semantic;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

final class SemanticPromptGuidanceTest {
    @Test
    void describesOnlyClosedPresentationSyntaxAndItsAuthorityBoundary() {
        String guidance = SemanticPromptGuidance.text();

        assertTrue(guidance.contains("[[tw:<kind>|<target>|<optional label>]]"));
        assertTrue(guidance.contains("tomewisp-component"));
        assertTrue(guidance.contains("do not create evidence"));
        assertTrue(guidance.contains("\"schemaVersion\":1"));
        assertTrue(guidance.contains("Do not add envelope or properties fields"));
        assertTrue(guidance.contains("never invent or repair them"));
        assertTrue(guidance.contains("Do not emit HTML"));
        assertFalse(guidance.contains("apiKey"));
        assertFalse(guidance.contains("reasoning"));
    }

    @Test
    void promptCatalogAndBuiltinRegistryHaveExactlyTheSameTypes() {
        Set<String> promptTypes = BuiltinRichComponents.promptCatalog().stream()
                .map(BuiltinRichComponents.PromptCatalogEntry::type)
                .collect(Collectors.toUnmodifiableSet());
        Set<String> registeredTypes = RichComponentRegistry.builtins().registeredTypes();

        assertEquals(registeredTypes, promptTypes);
        assertEquals(8, promptTypes.size());
        String guidance = SemanticPromptGuidance.text();
        promptTypes.forEach(type -> assertTrue(
                guidance.contains("- " + type + ":"), "missing prompt entry for " + type));
    }

    @Test
    void catalogPublishesStrictFieldsEnumsAndEvidenceBindings() {
        String guidance = SemanticPromptGuidance.text();

        assertTrue(guidance.contains(
                "{\"items\":[{\"itemId\":string,\"count\":nonnegative integer,\"label\":string|null}]}"));
        assertTrue(guidance.contains(
                "{\"sourceId\":string,\"generation\":string,\"recipeId\":string,\"label\":string|null}"));
        assertTrue(guidance.contains(
                "\"state\":PENDING|ACTIVE|COMPLETE|FAILED"));
        assertTrue(guidance.contains(
                "\"state\":INFO|SUCCESS|WARNING|ERROR"));
        assertTrue(guidance.contains("copy the complete sourceId/generation/recipeId handle"));
        assertTrue(guidance.contains("every sourceId must copy a Tool-returned source reference"));
        assertTrue(guidance.contains("cannot encode callbacks, commands, or URLs"));
    }
}
