package dev.openallay.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import dev.openallay.context.ToolInvocationContext;
import dev.openallay.tool.ToolResult;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class LoadSkillToolTest {
    @Test
    void returnsOnlyAValidatedNamedSkill() {
        SkillRepository repository = new SkillRepository(new SkillParser(), Set.of());
        repository.reload(java.util.List.of(new SkillSource(
                "pack",
                "guide/skill.md",
                Map.of("guide/skill.md", """
                        ---
                        name: guide
                        description: Guide the player
                        required-mods: []
                        allowed-tools: []
                        references: []
                        ---
                        Follow evidence.
                        """))), Set.of());
        LoadSkillTool tool = new LoadSkillTool(repository);

        ToolResult.Success<LoadSkillTool.Output> success = assertInstanceOf(
                ToolResult.Success.class,
                tool.invoke(ToolInvocationContext.developmentConsole("test"),
                        new LoadSkillTool.Input("guide")));
        assertEquals("Follow evidence.", success.value().instructions());
        assertInstanceOf(
                ToolResult.Failure.class,
                tool.invoke(ToolInvocationContext.developmentConsole("test"),
                        new LoadSkillTool.Input("missing")));
    }

    @Test
    void cannotLoadSkillExcludedFromCapturedCatalog() {
        SkillRepository repository = new SkillRepository(new SkillParser(), Set.of());
        repository.reload(java.util.List.of(new SkillSource(
                "pack",
                "guide/skill.md",
                Map.of("guide/skill.md", """
                        ---
                        name: guide
                        description: Guide the player
                        required-mods: []
                        allowed-tools: []
                        references: []
                        ---
                        Follow evidence.
                        """))), Set.of());
        LoadSkillTool tool = new LoadSkillTool(repository.snapshot(Set.of("guide")));

        ToolResult.Failure<LoadSkillTool.Output> failure = assertInstanceOf(
                ToolResult.Failure.class,
                tool.invoke(ToolInvocationContext.developmentConsole("test"),
                        new LoadSkillTool.Input("guide")));

        assertEquals("skill_not_found", failure.code());
        assertInstanceOf(
                ToolResult.Success.class,
                new LoadSkillTool(repository).invoke(
                        ToolInvocationContext.developmentConsole("test"),
                        new LoadSkillTool.Input("guide")));
    }

    @Test
    void progressivelyLoadsOneDeclaredReferenceInsteadOfInjectingAllReferences() {
        SkillRepository repository = new SkillRepository(new SkillParser(), Set.of());
        repository.reload(java.util.List.of(new SkillSource(
                "pack",
                "guide/SKILL.md",
                Map.of(
                        "guide/SKILL.md", """
                                ---
                                name: guide
                                description: Guide the player
                                ---
                                Read the matching reference.
                                """,
                        "guide/references/a.md", "A contents",
                        "guide/references/b.md", "B contents"))), Set.of());
        LoadSkillTool tool = new LoadSkillTool(repository);

        ToolResult.Success<LoadSkillTool.Output> entrySuccess = assertInstanceOf(
                ToolResult.Success.class,
                tool.invoke(ToolInvocationContext.developmentConsole("test"),
                        new LoadSkillTool.Input("guide")));
        LoadSkillTool.Output entry = entrySuccess.value();
        assertEquals("Read the matching reference.", entry.instructions());
        assertEquals(java.util.List.of("references/a.md", "references/b.md"),
                entry.availableReferences());
        assertEquals(Map.of(), entry.references());

        ToolResult.Success<LoadSkillTool.Output> referenceSuccess = assertInstanceOf(
                ToolResult.Success.class,
                tool.invoke(ToolInvocationContext.developmentConsole("test"),
                        new LoadSkillTool.Input("guide", "references/b.md")));
        LoadSkillTool.Output reference = referenceSuccess.value();
        assertEquals("", reference.instructions());
        assertEquals(Map.of("references/b.md", "B contents"), reference.references());

        ToolResult.Failure<LoadSkillTool.Output> missing = assertInstanceOf(
                ToolResult.Failure.class,
                tool.invoke(ToolInvocationContext.developmentConsole("test"),
                        new LoadSkillTool.Input("guide", "references/missing.md")));
        assertEquals("skill_reference_not_found", missing.code());
    }
}
