package dev.tomewisp.client.gui.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tomewisp.model.config.ModelProfileDefinition;
import dev.tomewisp.model.config.ModelProtocol;
import dev.tomewisp.tool.ToolResult;
import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;

final class ModelProfileDraftTest {
    @Test
    void roundTripsValidDefinitionAndTracksDirtyState() {
        ModelProfileDefinition original = definition();
        ModelProfileDraft draft = ModelProfileDraft.from(original);

        assertFalse(draft.dirtyComparedTo(original));
        assertEquals(original, success(draft.validate()));

        ModelProfileDraft changed = draft.withModel("vendor/new-model");
        assertTrue(changed.dirtyComparedTo(original));
        assertEquals("vendor/new-model", success(changed.validate()).model());
    }

    @Test
    void invalidFieldsReturnStableFailureWithoutThrowing() {
        ModelProfileDraft invalid = new ModelProfileDraft(
                "bad id",
                "",
                true,
                ModelProtocol.OPENAI_CHAT,
                "http://remote.example/v1",
                "",
                "BAD KEY",
                "not-a-number",
                "0",
                "-1",
                "none");

        ToolResult<ModelProfileDefinition> result = invalid.validate();
        if (!(result instanceof ToolResult.Failure<ModelProfileDefinition> failure)) {
            throw new AssertionError("expected an invalid model profile draft");
        }
        assertEquals("invalid_model_profile", failure.code());
        assertFalse(failure.message().contains("http://remote.example/v1"));
    }

    @Test
    void catalogValidationDoesNotRequireAModelId() {
        ModelProfileDraft draft = ModelProfileDraft.create("new-profile");
        draft = new ModelProfileDraft(
                draft.id(), draft.displayName(), draft.enabled(), draft.protocol(),
                "https://provider.example/v1/", "", draft.credentialRef(),
                draft.contextWindowTokens(), draft.maxOutputTokens(),
                draft.connectTimeoutSeconds(), draft.requestTimeoutSeconds(), draft.metadata());

        var request = (ToolResult.Success<dev.tomewisp.model.catalog.ModelCatalogRequest>)
                assertInstanceOf(ToolResult.Success.class, draft.catalogRequest());

        assertEquals("https://provider.example/v1/", request.value().baseUri().toString());
        assertEquals("new-profile", request.value().profileId());
    }

    private static ModelProfileDefinition definition() {
        return new ModelProfileDefinition(
                "main",
                "Main",
                true,
                ModelProtocol.OPENAI_CHAT,
                URI.create("https://provider.example/v1"),
                "vendor/model",
                "MODEL_KEY",
                256_000,
                4_096,
                Duration.ofSeconds(30),
                Duration.ofSeconds(300),
                null);
    }

    private static ModelProfileDefinition success(
            ToolResult<ModelProfileDefinition> result) {
        if (result instanceof ToolResult.Success<ModelProfileDefinition> value) {
            return value.value();
        }
        throw new AssertionError("expected a successful model profile draft");
    }
}
