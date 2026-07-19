package dev.tomewisp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tomewisp.capability.CapabilityCatalogState;
import dev.tomewisp.capability.CapabilityChildPage;
import dev.tomewisp.capability.CapabilityKind;
import dev.tomewisp.capability.CapabilitySettingsEntry;
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

final class TomeWispBootstrapCatalogTest {
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
        tools.register("tomewisp:skills", List.of(new LoadSkillTool(skills)));

        List<CapabilitySettingsEntry> entries = TomeWispBootstrap
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
                entry.id().equals("tomewisp:load_skill")));
        CapabilitySettingsEntry recipes = entries.stream()
                .filter(entry -> entry.id().equals("tomewisp:recipes"))
                .findFirst()
                .orElseThrow();
        assertEquals(
                new CapabilityChildPage("tomewisp:recipe_settings"), recipes.childPage());
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
