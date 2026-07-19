package dev.openallay.capability;

import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/** Deny-only local policy for stable Tool identities and bundled Skill names. */
public record CapabilityPolicy(
        int schemaVersion,
        Set<String> disabledTools,
        Set<String> disabledSkills) {
    public static final int SCHEMA_VERSION = 1;

    private static final Pattern TOOL_ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
    private static final Pattern SKILL_NAME = Pattern.compile("[a-z0-9][a-z0-9-]*");

    public CapabilityPolicy {
        if (schemaVersion != SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported capability configuration schema");
        }
        disabledTools = canonical(disabledTools, CapabilityPolicy::requireToolId, "disabledTools");
        disabledSkills = canonical(
                disabledSkills, CapabilityPolicy::requireSkillName, "disabledSkills");
    }

    public static CapabilityPolicy defaults() {
        return new CapabilityPolicy(SCHEMA_VERSION, Set.of(), Set.of());
    }

    public static String requireToolId(String value) {
        if (value == null || !TOOL_ID.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid Tool identity: " + value);
        }
        return value;
    }

    public static String requireSkillName(String value) {
        if (value == null || !SKILL_NAME.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid Skill name: " + value);
        }
        return value;
    }

    private static Set<String> canonical(
            Set<String> values,
            java.util.function.UnaryOperator<String> validator,
            String name) {
        Objects.requireNonNull(values, name);
        TreeSet<String> sorted = new TreeSet<>();
        for (String value : values) {
            sorted.add(validator.apply(value));
        }
        return Collections.unmodifiableSet(sorted);
    }
}
