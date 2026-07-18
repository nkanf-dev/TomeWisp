package dev.tomewisp.capability;

import dev.tomewisp.agent.tool.ToolRuntimeCatalog;
import dev.tomewisp.context.ContextCapability;
import dev.tomewisp.skill.LoadSkillTool;
import dev.tomewisp.skill.SkillCatalogSnapshot;
import dev.tomewisp.skill.SkillMetadata;
import dev.tomewisp.skill.SkillRepository;
import dev.tomewisp.tool.RegisteredTool;
import dev.tomewisp.tool.ToolResult;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Resolves deny-only policy into a complete immutable client request authority snapshot. */
public final class ClientCapabilityResolver {
    public static final String LOAD_SKILL_ID = "tomewisp:load_skill";

    public ToolResult<ClientCapabilitySnapshot> resolve(
            CapabilityPolicy policy,
            List<RegisteredTool> registrations,
            SkillRepository skills) {
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(registrations, "registrations");
        Objects.requireNonNull(skills, "skills");
        if (policy.disabledTools().contains(LOAD_SKILL_ID)) {
            return failed(
                    "invalid_capability_config",
                    "load_skill is derived from enabled Skills and cannot be configured directly");
        }

        try {
            SkillCatalogSnapshot skillSnapshot = skills.snapshot(policy.disabledSkills());
            List<RegisteredTool> candidate = new ArrayList<>();
            RegisteredTool loadSkillRegistration = null;
            for (RegisteredTool registration : List.copyOf(registrations)) {
                if (registration.tool().descriptor().id().equals(LOAD_SKILL_ID)) {
                    loadSkillRegistration = registration;
                } else {
                    candidate.add(registration);
                }
            }

            Set<String> disabled = new HashSet<>(policy.disabledTools());
            if (skillSnapshot.metadata().isEmpty()) {
                if (loadSkillRegistration != null) {
                    candidate.add(loadSkillRegistration);
                    disabled.add(LOAD_SKILL_ID);
                }
            } else {
                if (loadSkillRegistration == null) {
                    return failed(
                            "capability_dependency_conflict",
                            "Enabled Skills require the registered load_skill Tool");
                }
                candidate.add(new RegisteredTool(
                        loadSkillRegistration.providerId(), new LoadSkillTool(skillSnapshot)));
            }

            ToolRuntimeCatalog localTools = ToolRuntimeCatalog.from(candidate, disabled);
            for (SkillMetadata metadata : skillSnapshot.metadata()) {
                Set<String> missing = metadata.allowedTools().stream()
                        .filter(toolId -> localTools.find(toolId).isEmpty())
                        .collect(Collectors.toCollection(java.util.TreeSet::new));
                if (!missing.isEmpty()) {
                    return failed(
                            "capability_dependency_conflict",
                            "Enabled Skill " + metadata.name()
                                    + " requires disabled or unavailable Tools " + missing);
                }
            }
            Set<ContextCapability> requiredContext = localTools.descriptors().stream()
                    .flatMap(descriptor -> descriptor.requiredContext().stream())
                    .collect(Collectors.toUnmodifiableSet());
            return new ToolResult.Success<>(new ClientCapabilitySnapshot(
                    policy, localTools, skillSnapshot, requiredContext));
        } catch (RuntimeException failure) {
            return failed(
                    "invalid_capability_config",
                    failure.getMessage() == null
                            ? "Unable to resolve client capabilities"
                            : failure.getMessage());
        }
    }

    private static ToolResult.Failure<ClientCapabilitySnapshot> failed(
            String code, String message) {
        return new ToolResult.Failure<>(code, message);
    }
}
