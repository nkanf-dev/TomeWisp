package dev.tomewisp.recipe;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import dev.tomewisp.context.EvidenceMetadata;
import dev.tomewisp.context.FluidRequirementSnapshot;
import dev.tomewisp.context.IngredientAlternativeSnapshot;
import dev.tomewisp.context.IngredientRequirementSnapshot;
import dev.tomewisp.context.ItemStackSnapshot;
import dev.tomewisp.context.RecipeEntrySnapshot;
import dev.tomewisp.context.RecipeOutputSnapshot;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/** Stable semantic and provider-generation digests for detached recipe records. */
public final class RecipeCanonicalizer {
    private RecipeCanonicalizer() {}

    public static String semanticFingerprint(RecipeEntrySnapshot recipe) {
        Digest digest = new Digest();
        digest.string("type");
        digest.string(recipe.type());
        digest.string("layout");
        digest.number(recipe.layout().width());
        digest.number(recipe.layout().height());
        digest.bool(recipe.layout().shaped());
        digest.string("workstation");
        digest.string(recipe.workstation());
        digest.string("ingredients");
        requirements(digest, recipe.ingredients(), recipe.layout().shaped());
        digest.string("catalysts");
        requirements(digest, recipe.catalysts(), false);
        digest.string("fluids");
        digest.number(recipe.fluids().size());
        sorted(recipe.fluids(), RecipeCanonicalizer::fluidKey)
                .forEach(value -> fluid(digest, value));
        digest.string("outputs");
        digest.number(recipe.outputs().size());
        sorted(recipe.outputs(), RecipeCanonicalizer::outputKey)
                .forEach(value -> output(digest, value));
        digest.string("byproducts");
        digest.number(recipe.byproducts().size());
        sorted(recipe.byproducts(), RecipeCanonicalizer::outputKey)
                .forEach(value -> output(digest, value));
        digest.string("processing");
        nullableNumber(digest, recipe.processing().durationTicks());
        nullableNumber(digest, recipe.processing().energy());
        nullableDouble(digest, recipe.processing().temperature());
        digest.string("conditions");
        digest.number(recipe.conditions().size());
        recipe.conditions().stream().sorted().forEach(digest::string);
        digest.string("extensions");
        digest.number(recipe.extensions().size());
        recipe.extensions().forEach((key, value) -> {
            digest.string(key);
            json(digest, value);
        });
        return digest.finish();
    }

    public static String providerGeneration(
            String sourceId, List<RecipeEntrySnapshot> recipes) {
        Digest digest = new Digest();
        digest.string(sourceId);
        digest.number(recipes.size());
        recipes.stream()
                .sorted(Comparator.comparing((RecipeEntrySnapshot value) ->
                                value.reference().recipeId())
                        .thenComparing(RecipeEntrySnapshot::id)
                        .thenComparing(RecipeCanonicalizer::semanticFingerprint))
                .forEach(recipe -> {
                    digest.string("recipe");
                    digest.string(recipe.reference().recipeId());
                    digest.string(recipe.id());
                    digest.string(semanticFingerprint(recipe));
                    digest.string(recipe.unlockState().name());
                    evidence(digest, recipe.evidence());
                });
        return digest.finish();
    }

    private static void requirements(
            Digest digest,
            List<IngredientRequirementSnapshot> requirements,
            boolean ordered) {
        List<IngredientRequirementSnapshot> values = ordered
                ? requirements
                : sorted(requirements, RecipeCanonicalizer::requirementKey);
        digest.number(values.size());
        values.forEach(value -> requirement(digest, value));
    }

    private static void requirement(Digest digest, IngredientRequirementSnapshot value) {
        digest.number(value.count());
        digest.bool(value.consumed());
        List<IngredientAlternativeSnapshot> alternatives =
                sorted(value.alternatives(), RecipeCanonicalizer::alternativeKey);
        digest.number(alternatives.size());
        alternatives.forEach(alternative -> {
            digest.string(alternative.kind());
            digest.string(alternative.id());
            List<String> resolved = alternative.resolvedItems().stream().sorted().toList();
            digest.number(resolved.size());
            resolved.forEach(digest::string);
        });
    }

    private static String requirementKey(IngredientRequirementSnapshot value) {
        Digest digest = new Digest();
        requirement(digest, value);
        return digest.finish();
    }

    private static String alternativeKey(IngredientAlternativeSnapshot value) {
        return value.kind() + "\u0000" + value.id() + "\u0000"
                + String.join("\u0000", value.resolvedItems().stream().sorted().toList());
    }

    private static void fluid(Digest digest, FluidRequirementSnapshot value) {
        digest.string(value.fluidId());
        digest.number(value.amount());
        digest.bool(value.consumed());
    }

    private static String fluidKey(FluidRequirementSnapshot value) {
        return value.fluidId() + "\u0000" + value.amount() + "\u0000" + value.consumed();
    }

    private static void output(Digest digest, RecipeOutputSnapshot value) {
        stack(digest, value.stack());
        digest.floating(value.probability());
    }

    private static String outputKey(RecipeOutputSnapshot value) {
        return value.stack().itemId() + "\u0000" + value.stack().count() + "\u0000"
                + Double.toHexString(value.probability());
    }

    private static void stack(Digest digest, ItemStackSnapshot value) {
        digest.string(value.itemId());
        digest.number(value.count());
    }

    private static void evidence(Digest digest, EvidenceMetadata value) {
        digest.string(value.authority().name());
        digest.string(value.completeness().name());
        digest.string(value.sourceId());
        digest.string(value.provenance());
        digest.string(value.gameVersion());
        digest.string(value.loader());
        value.details().forEach((key, detail) -> {
            digest.string(key);
            digest.string(detail);
        });
    }

    private static void json(Digest digest, JsonElement value) {
        if (value == null || value.isJsonNull()) {
            digest.marker((byte) 0);
        } else if (value.isJsonObject()) {
            digest.marker((byte) 1);
            value.getAsJsonObject().keySet().stream().sorted().forEach(key -> {
                digest.string(key);
                json(digest, value.getAsJsonObject().get(key));
            });
        } else if (value.isJsonArray()) {
            digest.marker((byte) 2);
            value.getAsJsonArray().forEach(element -> json(digest, element));
        } else {
            JsonPrimitive primitive = value.getAsJsonPrimitive();
            if (primitive.isBoolean()) {
                digest.marker((byte) 3);
                digest.bool(primitive.getAsBoolean());
            } else if (primitive.isNumber()) {
                digest.marker((byte) 4);
                digest.string(new BigDecimal(primitive.getAsString())
                        .stripTrailingZeros().toPlainString());
            } else {
                digest.marker((byte) 5);
                digest.string(primitive.getAsString());
            }
        }
    }

    private static void nullableNumber(Digest digest, Long value) {
        digest.bool(value != null);
        if (value != null) {
            digest.number(value);
        }
    }

    private static void nullableDouble(Digest digest, Double value) {
        digest.bool(value != null);
        if (value != null) {
            digest.floating(value);
        }
    }

    private static <T> List<T> sorted(List<T> values, java.util.function.Function<T, String> key) {
        ArrayList<T> copy = new ArrayList<>(values);
        copy.sort(Comparator.comparing(key));
        return copy;
    }

    private static final class Digest {
        private final MessageDigest digest;

        private Digest() {
            try {
                digest = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException impossible) {
                throw new IllegalStateException("JDK does not provide SHA-256", impossible);
            }
        }

        private void marker(byte value) {
            digest.update(value);
        }

        private void bool(boolean value) {
            marker(value ? (byte) 1 : (byte) 0);
        }

        private void number(long value) {
            digest.update(ByteBuffer.allocate(Long.BYTES).putLong(value).array());
        }

        private void floating(double value) {
            number(Double.doubleToLongBits(value));
        }

        private void string(String value) {
            if (value == null) {
                marker((byte) 0);
                return;
            }
            marker((byte) 1);
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
            digest.update(bytes);
        }

        private String finish() {
            return HexFormat.of().formatHex(digest.digest());
        }
    }
}
