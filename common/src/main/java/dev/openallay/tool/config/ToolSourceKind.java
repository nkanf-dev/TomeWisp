package dev.openallay.tool.config;

import com.google.gson.JsonObject;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Trusted source-kind registration; this metadata is code authority, not persisted input. */
public record ToolSourceKind(
        String sourceKind,
        ToolFamilyId owner,
        Set<ToolSourceDefinition.Lifecycle> lifecycles,
        boolean userCreatable,
        List<EditorField> editorFields,
        int configSchemaVersion,
        boolean credentialReferenceAllowed,
        ConfigValidator configValidator) {
    private static final Pattern KIND = Pattern.compile("[a-z0-9][a-z0-9_.-]*");

    @FunctionalInterface
    public interface ConfigValidator {
        JsonObject validate(JsonObject config);
    }

    public record EditorField(String key, String translationKey, FieldType type, boolean required) {
        public EditorField {
            if (key == null || key.isBlank() || translationKey == null || translationKey.isBlank()) {
                throw new IllegalArgumentException("Editor field key and translation key are required");
            }
            Objects.requireNonNull(type, "type");
        }
    }

    public enum FieldType {
        TEXT,
        LOCALE,
        CREDENTIAL_REFERENCE,
        BOOLEAN
    }

    public ToolSourceKind {
        if (sourceKind == null || !KIND.matcher(sourceKind).matches()) {
            throw new IllegalArgumentException("Invalid source kind " + sourceKind);
        }
        Objects.requireNonNull(owner, "owner");
        lifecycles = Set.copyOf(lifecycles);
        if (lifecycles.isEmpty()) {
            throw new IllegalArgumentException("A source kind must support at least one lifecycle");
        }
        if (userCreatable && !lifecycles.contains(ToolSourceDefinition.Lifecycle.USER)) {
            throw new IllegalArgumentException("A user-creatable source kind must support USER lifecycle");
        }
        editorFields = List.copyOf(editorFields);
        if (configSchemaVersion <= 0) {
            throw new IllegalArgumentException("Source config schema version must be positive");
        }
        if (!credentialReferenceAllowed
                && editorFields.stream().anyMatch(field -> field.type() == FieldType.CREDENTIAL_REFERENCE)) {
            throw new IllegalArgumentException(
                    "Credential-reference editor fields require explicit kind capability");
        }
        Objects.requireNonNull(configValidator, "configValidator");
    }

    public ToolSourceKind(
            String sourceKind,
            ToolFamilyId owner,
            Set<ToolSourceDefinition.Lifecycle> lifecycles,
            boolean userCreatable,
            List<EditorField> editorFields,
            ConfigValidator configValidator) {
        this(
                sourceKind,
                owner,
                lifecycles,
                userCreatable,
                editorFields,
                1,
                false,
                configValidator);
    }

    public JsonObject validateConfig(JsonObject config) {
        Objects.requireNonNull(config, "config");
        JsonObject validated = Objects.requireNonNull(
                configValidator.validate(config.deepCopy()), "validated config");
        return validated.deepCopy();
    }

    public static ToolSourceKind localMarkdown() {
        return new ToolSourceKind(
                "local_markdown",
                ToolFamilyId.GUIDES,
                Set.of(ToolSourceDefinition.Lifecycle.USER),
                true,
                List.of(
                        new EditorField(
                                "directory",
                                "openallay.settings.tools.local_markdown.directory",
                                FieldType.TEXT,
                                true),
                        new EditorField(
                                "locale",
                                "openallay.settings.tools.local_markdown.locale",
                                FieldType.LOCALE,
                                true)),
                1,
                false,
                LocalMarkdownKnowledgeProvider::validateConfig);
    }
}
