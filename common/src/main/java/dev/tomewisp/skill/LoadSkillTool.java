package dev.tomewisp.skill;

import dev.tomewisp.context.ToolInvocationContext;
import dev.tomewisp.tool.Tool;
import dev.tomewisp.tool.ToolAccess;
import dev.tomewisp.tool.ToolDescriptor;
import dev.tomewisp.tool.ToolResult;
import java.util.List;
import java.util.Map;

public final class LoadSkillTool implements Tool<LoadSkillTool.Input, LoadSkillTool.Output> {
    public record Input(String name) {}

    public record Output(
            String name,
            String instructions,
            Map<String, String> references,
            List<String> allowedTools,
            String provenance) {}

    private static final ToolDescriptor<Input, Output> DESCRIPTOR = new ToolDescriptor<>(
            "tomewisp:load_skill",
            "Load the complete instructions and declared references for one available Skill",
            Input.class,
            Output.class,
            ToolAccess.READ_ONLY);

    private final SkillRepository repository;

    public LoadSkillTool(SkillRepository repository) {
        this.repository = repository;
    }

    @Override
    public ToolDescriptor<Input, Output> descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public ToolResult<Output> invoke(ToolInvocationContext context, Input input) {
        if (input == null || input.name() == null || input.name().isBlank()) {
            return new ToolResult.Failure<>("invalid_skill_name", "Skill name must not be blank");
        }
        return repository.find(input.name())
                .<ToolResult<Output>>map(document -> new ToolResult.Success<>(new Output(
                        document.metadata().name(),
                        document.instructions(),
                        document.references(),
                        document.metadata().allowedTools().stream().sorted().toList(),
                        document.metadata().provenance())))
                .orElseGet(() -> new ToolResult.Failure<>(
                        "skill_not_found", "No available Skill named " + input.name()));
    }
}
