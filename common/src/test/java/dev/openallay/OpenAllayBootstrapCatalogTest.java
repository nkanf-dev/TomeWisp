package dev.openallay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.openallay.capability.CapabilityCatalogState;
import dev.openallay.capability.CapabilityChildPage;
import dev.openallay.capability.CapabilityKind;
import dev.openallay.capability.CapabilitySettingsEntry;
import dev.openallay.context.ToolInvocationContext;
import dev.openallay.skill.LoadSkillTool;
import dev.openallay.skill.SkillParser;
import dev.openallay.skill.SkillRepository;
import dev.openallay.skill.SkillSource;
import dev.openallay.tool.Tool;
import dev.openallay.tool.ToolAccess;
import dev.openallay.tool.ToolDescriptor;
import dev.openallay.tool.ToolRegistry;
import dev.openallay.tool.ToolResult;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class OpenAllayBootstrapCatalogTest {
    record Input() {}
    record Output(String value) {}

    @Test
    void registersEveryPublicToolAndSkillWithRecipeChildSettings() {
        ToolRegistry tools = new ToolRegistry();
        tools.register("test:provider", List.of(tool()));
        SkillRepository skills = new SkillRepository(new SkillParser(), Set.of("test:fact"));
        assertTrue(skills.reload(List.of(new SkillSource(
                "test-pack",
                "fact-guide/skill.md",
                Map.of("fact-guide/skill.md", """
                        ---
                        name: fact-guide
                        description: Use facts
                        required-mods: []
                        allowed-tools: [test:fact]
                        references: []
                        ---
                        Use the fact.
                        """))), Set.of()));
        tools.register("openallay:skills", List.of(new LoadSkillTool(skills)));

        List<CapabilitySettingsEntry> entries = OpenAllayBootstrap
                .capabilitySettings(tools, skills)
                .snapshot(CapabilityCatalogState.defaults())
                .entries();

        assertTrue(entries.stream().anyMatch(entry ->
                entry.id().equals("patchouli")
                        && entry.kind() == CapabilityKind.KNOWLEDGE_SOURCE));
        assertTrue(entries.stream().anyMatch(entry ->
                entry.id().equals("test:fact") && entry.kind() == CapabilityKind.TOOL));
        assertTrue(entries.stream().anyMatch(entry ->
                entry.id().equals("fact-guide") && entry.kind() == CapabilityKind.SKILL));
        assertTrue(entries.stream().noneMatch(entry ->
                entry.id().equals("openallay:load_skill")));
        CapabilitySettingsEntry recipes = entries.stream()
                .filter(entry -> entry.id().equals("openallay:recipes"))
                .findFirst()
                .orElseThrow();
        assertEquals(
                new CapabilityChildPage("openallay:recipe_settings"), recipes.childPage());
    }

    private static Tool<Input, Output> tool() {
        return new Tool<>() {
            private final ToolDescriptor<Input, Output> descriptor = new ToolDescriptor<>(
                    "test:fact",
                    "Return a fact",
                    Input.class,
                    Output.class,
                    ToolAccess.READ_ONLY);

            @Override public ToolDescriptor<Input, Output> descriptor() { return descriptor; }

            @Override
            public ToolResult<Output> invoke(ToolInvocationContext context, Input input) {
                return new ToolResult.Success<>(new Output("fact"));
            }
        };
    }
}
