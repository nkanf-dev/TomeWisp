package dev.tomewisp.guide.semantic;

import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses the closed inline syntax and enforces same-request stable-handle authority. */
public final class SemanticReferenceValidator {
    private static final Pattern TOKEN = Pattern.compile(
            "\\[\\[tw:([a-z_]+)\\|([^|\\]\\r\\n]+)(?:\\|([^\\]\\r\\n]+))?\\]\\]");
    private static final Pattern RESOURCE = Pattern.compile(
            "[a-z0-9_.-]+:[a-z0-9_./-]+");
    private static final Pattern KEY = Pattern.compile("[a-zA-Z0-9_.-]+");

    public Validation validate(String token, SemanticReferenceIndex index) {
        Objects.requireNonNull(index, "index");
        Matcher matcher = TOKEN.matcher(Objects.requireNonNullElse(token, ""));
        if (!matcher.matches()) {
            return Validation.failure("semantic_content_invalid");
        }
        SemanticReferenceKind kind;
        try {
            kind = SemanticReferenceKind.fromToken(matcher.group(1));
        } catch (IllegalArgumentException unknown) {
            return Validation.failure("semantic_reference_unsupported");
        }
        String target = matcher.group(2).strip();
        String label = matcher.group(3) == null ? "" : matcher.group(3).strip();
        if (target.isBlank() || (matcher.group(3) != null && label.isBlank())) {
            return Validation.failure("semantic_content_invalid");
        }
        if (!syntax(kind, target)) {
            return Validation.failure("semantic_reference_unresolved");
        }
        Optional<String> origin = index.origin(kind, target);
        if (origin.isPresent()) {
            return Validation.success(new SemanticReference(
                    kind, target, label, true, origin.orElseThrow()));
        }
        if (!kind.permitsUngroundedPresentation()) {
            return Validation.failure("semantic_reference_unresolved");
        }
        return Validation.success(new SemanticReference(kind, target, label, false, null));
    }

    Matcher matcher(String source) {
        return TOKEN.matcher(Objects.requireNonNullElse(source, ""));
    }

    public static boolean isResourceId(String value) {
        return value != null && RESOURCE.matcher(value).matches();
    }

    private static boolean syntax(SemanticReferenceKind kind, String target) {
        return switch (kind) {
            case ITEM, BLOCK, FLUID, ENTITY, BIOME, DIMENSION -> isResourceId(target);
            case TAG -> target.startsWith("#") && isResourceId(target.substring(1));
            case KEY -> KEY.matcher(target).matches();
            case RECIPE -> recipe(target);
            case SOURCE, EVIDENCE -> isResourceId(target);
        };
    }

    private static boolean recipe(String target) {
        try {
            RecipeSemanticHandle.decode(target);
            return true;
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    public record Validation(SemanticReference reference, String failureCode) {
        public Validation {
            if ((reference == null) == (failureCode == null)) {
                throw new IllegalArgumentException("reference validation must succeed or fail");
            }
        }

        public static Validation success(SemanticReference reference) {
            return new Validation(Objects.requireNonNull(reference, "reference"), null);
        }

        public static Validation failure(String code) {
            return new Validation(null, Objects.requireNonNull(code, "code"));
        }

        public boolean successful() {
            return reference != null;
        }
    }
}
