package dev.tomewisp.capability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tomewisp.context.ContextCapability;
import dev.tomewisp.context.ToolInvocationContext;
import dev.tomewisp.skill.LoadSkillTool;
import dev.tomewisp.skill.SkillParser;
import dev.tomewisp.skill.SkillRepository;
import dev.tomewisp.skill.SkillSource;
import dev.tomewisp.tool.Tool;
import dev.tomewisp.tool.ToolAccess;
import dev.tomewisp.tool.ToolDescriptor;
import dev.tomewisp.tool.ToolRegistry;
import dev.tomewisp.tool.ToolResult;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class ClientCapabilityResolverTest {
    record Input(int value) {}
    record Output(int value) {}

    private final ClientCapabilityResolver resolver = new ClientCapabilityResolver();

    @Test
    void enabledSkillDependingOnDisabledToolRejectsCandidate() {
        Fixture fixture = fixture(true);
        CapabilityPolicy policy = policy(Set.of("test:fact"), Set.of());

        ToolResult.Failure<ClientCapabilitySnapshot> failure = failure(resolver.resolve(
                policy, fixture.tools().registrations(), fixture.skills()));

        assertEquals("capability_dependency_conflict", failure.code());
    }

    @Test
    void disablingDependentSkillAllowsToolToBeDisabled() {
        Fixture fixture = fixture(true);
        CapabilityPolicy policy = policy(Set.of("test:fact"), Set.of("fact-guide"));

        ClientCapabilitySnapshot snapshot = success(resolver.resolve(
                policy, fixture.tools().registrations(), fixture.skills())).value();

        assertTrue(snapshot.localTools().find("test:fact").isEmpty());
        assertTrue(snapshot.localTools().find(ClientCapabilityResolver.LOAD_SKILL_ID).isEmpty());
        assertTrue(snapshot.skills().metadata().isEmpty());
        assertTrue(snapshot.requiredContext().isEmpty());
        assertEquals(policy, snapshot.policy());
    }

    @Test
    void derivesLoadSkillOnlyWhenCapturedSkillViewIsNonEmpty() {
        Fixture fixture = fixture(true);

        ClientCapabilitySnapshot enabled = success(resolver.resolve(
                CapabilityPolicy.defaults(), fixture.tools().registrations(), fixture.skills())).value();
        ClientCapabilitySnapshot disabled = success(resolver.resolve(
                policy(Set.of(), Set.of("fact-guide")),
                fixture.tools().registrations(),
                fixture.skills())).value();

        assertTrue(enabled.localTools().find(ClientCapabilityResolver.LOAD_SKILL_ID).isPresent());
        Tool<?, ?> derived = enabled.localTools()
                .find(ClientCapabilityResolver.LOAD_SKILL_ID).orElseThrow();
        assertInstanceOf(LoadSkillTool.class, derived);
        assertFalse(disabled.localTools().find(ClientCapabilityResolver.LOAD_SKILL_ID).isPresent());
        assertFalse(enabled.policy().disabledTools().contains(ClientCapabilityResolver.LOAD_SKILL_ID));
    }

    @Test
    void rejectsPersistedToggleForDerivedLoadSkillTool() {
        Fixture fixture = fixture(true);

        ToolResult.Failure<ClientCapabilitySnapshot> failure = failure(resolver.resolve(
                policy(Set.of(ClientCapabilityResolver.LOAD_SKILL_ID), Set.of()),
                fixture.tools().registrations(),
                fixture.skills()));

        assertEquals("invalid_capability_config", failure.code());
    }

    @Test
    void missingRegisteredLoadSkillFailsClosedWhenSkillsExist() {
        Fixture fixture = fixture(false);

        ToolResult.Failure<ClientCapabilitySnapshot> failure = failure(resolver.resolve(
                CapabilityPolicy.defaults(), fixture.tools().registrations(), fixture.skills()));

        assertEquals("capability_dependency_conflict", failure.code());
    }

    private static Fixture fixture(boolean registerLoadSkill) {
        ToolRegistry tools = new ToolRegistry();
        tools.register("test-provider", List.of(factTool()));
        SkillRepository skills = new SkillRepository(new SkillParser(), Set.of("test:fact"));
        assertTrue(skills.reload(List.of(new SkillSource(
                "test-pack",
                "fact-guide/skill.md",
                Map.of("fact-guide/skill.md", """
                        ---
                        name: fact-guide
                        description: Use the fact tool
                        required-mods: []
                        allowed-tools: [test:fact]
                        references: []
                        ---
                        Call the fact tool.
                        """))), Set.of()));
        if (registerLoadSkill) {
            tools.register("tomewisp:skills", List.of(new LoadSkillTool(skills)));
        }
        return new Fixture(tools, skills);
    }

    private static Tool<Input, Output> factTool() {
        return new Tool<>() {
            private final ToolDescriptor<Input, Output> descriptor = new ToolDescriptor<>(
                    "test:fact",
                    "Return a fact",
                    Input.class,
                    Output.class,
                    ToolAccess.READ_ONLY,
                    Set.of(ContextCapability.RECIPES));

            @Override public ToolDescriptor<Input, Output> descriptor() { return descriptor; }

            @Override
            public ToolResult<Output> invoke(ToolInvocationContext context, Input input) {
                return new ToolResult.Success<>(new Output(input.value()));
            }
        };
    }

    private static CapabilityPolicy policy(Set<String> tools, Set<String> skills) {
        return new CapabilityPolicy(CapabilityPolicy.SCHEMA_VERSION, tools, skills);
    }

    private record Fixture(ToolRegistry tools, SkillRepository skills) {}

    @SuppressWarnings("unchecked")
    private static ToolResult.Success<ClientCapabilitySnapshot> success(
            ToolResult<ClientCapabilitySnapshot> result) {
        return (ToolResult.Success<ClientCapabilitySnapshot>)
                assertInstanceOf(ToolResult.Success.class, result);
    }

    @SuppressWarnings("unchecked")
    private static ToolResult.Failure<ClientCapabilitySnapshot> failure(
            ToolResult<ClientCapabilitySnapshot> result) {
        return (ToolResult.Failure<ClientCapabilitySnapshot>)
                assertInstanceOf(ToolResult.Failure.class, result);
    }
}
