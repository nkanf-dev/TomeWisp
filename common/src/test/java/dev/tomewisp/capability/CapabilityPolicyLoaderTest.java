package dev.tomewisp.capability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import dev.tomewisp.tool.ToolResult;
import java.io.StringReader;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class CapabilityPolicyLoaderTest {
    @TempDir Path temporary;

    private final CapabilityPolicyLoader loader = new CapabilityPolicyLoader();
    private final CapabilityPolicyWriter writer = new CapabilityPolicyWriter();

    @Test
    void retainsUnknownDisabledIdentitiesAndCanonicalizesOrder() {
        CapabilityPolicy policy = success(loader.load(new StringReader("""
                {
                  "schemaVersion": 1,
                  "disabledTools": ["tomewisp:get_recipe", "future:tool"],
                  "disabledSkills": ["future-skill", "recipe-helper"]
                }
                """))).value();

        assertEquals(Set.of("future:tool", "tomewisp:get_recipe"), policy.disabledTools());
        assertEquals(Set.of("future-skill", "recipe-helper"), policy.disabledSkills());
        String encoded = writer.encode(policy);
        assertEquals(policy, success(loader.load(new StringReader(encoded))).value());
        assertFalse(encoded.indexOf("future:tool") > encoded.indexOf("tomewisp:get_recipe"));
        assertFalse(encoded.indexOf("future-skill") > encoded.indexOf("recipe-helper"));
    }

    @Test
    void missingFileReturnsDefaultsWithoutWriting() {
        Path missing = temporary.resolve("capabilities.json");

        assertEquals(CapabilityPolicy.defaults(), success(loader.load(missing)).value());
        assertFalse(java.nio.file.Files.exists(missing));
    }

    @Test
    void rejectsMissingExtraAndUnsupportedSchema() {
        assertFailure("""
                {"schemaVersion":1,"disabledTools":[]}
                """);
        assertFailure("""
                {"schemaVersion":1,"disabledTools":[],"disabledSkills":[],"extra":true}
                """);
        assertFailure("""
                {"schemaVersion":2,"disabledTools":[],"disabledSkills":[]}
                """);
    }

    @Test
    void rejectsDuplicatesAndInvalidToolIdentities() {
        assertFailure("""
                {"schemaVersion":1,"disabledTools":["future:tool","future:tool"],"disabledSkills":[]}
                """);
        assertFailure("""
                {"schemaVersion":1,"disabledTools":["missing_namespace"],"disabledSkills":[]}
                """);
        assertFailure("""
                {"schemaVersion":1,"disabledTools":["Future:tool"],"disabledSkills":[]}
                """);
        assertFailure("""
                {"schemaVersion":1,"disabledTools":["future:","other:ok"],"disabledSkills":[]}
                """);
    }

    @Test
    void rejectsDuplicatesAndInvalidSkillNames() {
        assertFailure("""
                {"schemaVersion":1,"disabledTools":[],"disabledSkills":["future-skill","future-skill"]}
                """);
        assertFailure("""
                {"schemaVersion":1,"disabledTools":[],"disabledSkills":[""]}
                """);
        assertFailure("""
                {"schemaVersion":1,"disabledTools":[],"disabledSkills":["Future-Skill"]}
                """);
        assertFailure("""
                {"schemaVersion":1,"disabledTools":[],"disabledSkills":["future_skill"]}
                """);
    }

    @Test
    void rejectsNonStringArraysAndFractionalSchema() {
        assertFailure("""
                {"schemaVersion":1,"disabledTools":[1],"disabledSkills":[]}
                """);
        assertFailure("""
                {"schemaVersion":1,"disabledTools":[],"disabledSkills":{}}
                """);
        assertFailure("""
                {"schemaVersion":1.5,"disabledTools":[],"disabledSkills":[]}
                """);
    }

    private void assertFailure(String json) {
        ToolResult.Failure<CapabilityPolicy> failure = failure(loader.load(new StringReader(json)));
        assertEquals("invalid_capability_config", failure.code());
    }

    @SuppressWarnings("unchecked")
    private static ToolResult.Success<CapabilityPolicy> success(ToolResult<CapabilityPolicy> result) {
        return (ToolResult.Success<CapabilityPolicy>)
                assertInstanceOf(ToolResult.Success.class, result);
    }

    @SuppressWarnings("unchecked")
    private static ToolResult.Failure<CapabilityPolicy> failure(ToolResult<CapabilityPolicy> result) {
        return (ToolResult.Failure<CapabilityPolicy>)
                assertInstanceOf(ToolResult.Failure.class, result);
    }
}
