package dev.tomewisp.capability;

import dev.tomewisp.agent.tool.ToolRuntimeCatalog;
import dev.tomewisp.context.ContextCapability;
import dev.tomewisp.skill.SkillCatalogSnapshot;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** One immutable local Tool/Skill authority view captured by future client requests. */
public record ClientCapabilitySnapshot(
        CapabilityPolicy policy,
        ToolRuntimeCatalog localTools,
        SkillCatalogSnapshot skills,
        Set<ContextCapability> requiredContext) {
    public ClientCapabilitySnapshot {
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(localTools, "localTools");
        Objects.requireNonNull(skills, "skills");
        requiredContext = Set.copyOf(requiredContext);
        Set<ContextCapability> derived = localTools.descriptors().stream()
                .flatMap(descriptor -> descriptor.requiredContext().stream())
                .collect(Collectors.toUnmodifiableSet());
        if (!requiredContext.equals(derived)) {
            throw new IllegalArgumentException("requiredContext must match the Tool catalog");
        }
    }
}
