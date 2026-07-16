package dev.tomewisp.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import dev.tomewisp.context.ToolInvocationContext;
import dev.tomewisp.tool.ToolResult;
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
}
