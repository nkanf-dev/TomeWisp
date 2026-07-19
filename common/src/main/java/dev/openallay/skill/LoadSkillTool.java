package dev.openallay.skill;

import dev.openallay.context.ToolInvocationContext;
import dev.openallay.agent.tool.ToolOptional;
import dev.openallay.tool.Tool;
import dev.openallay.tool.ToolAccess;
import dev.openallay.tool.ToolDescriptor;
import dev.openallay.tool.ToolResult;
import java.util.List;
import java.util.Map;

public final class LoadSkillTool implements Tool<LoadSkillTool.Input, LoadSkillTool.Output> {
    public record Input(String name, @ToolOptional String reference) {
        public Input(String name) {
            this(name, null);
        }
    }

    public record Output(
            String name,
            String instructions,
            List<String> availableReferences,
            Map<String, String> references,
            List<String> allowedTools,
            String provenance) {}

    private static final ToolDescriptor<Input, Output> DESCRIPTOR = new ToolDescriptor<>(
            "openallay:load_skill",
            "Load one available Skill's instructions, or one exact declared reference after the Skill is loaded",
            Input.class,
            Output.class,
            ToolAccess.READ_ONLY);

    private final SkillCatalog catalog;

    public LoadSkillTool(SkillCatalog catalog) {
        this.catalog = java.util.Objects.requireNonNull(catalog, "catalog");
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
        SkillDocument document = catalog.find(input.name()).orElse(null);
        if (document == null) {
            return new ToolResult.Failure<>(
                    "skill_not_found", "No available Skill named " + input.name());
        }
        List<String> availableReferences = document.references().keySet().stream().sorted().toList();
        String reference = input.reference() == null ? "" : input.reference().strip();
        if (reference.isEmpty()) {
            return new ToolResult.Success<>(new Output(
                    document.metadata().name(),
                    document.instructions(),
                    availableReferences,
                    Map.of(),
                    document.metadata().allowedTools().stream().sorted().toList(),
                    document.metadata().provenance()));
        }
        String contents = document.references().get(reference);
        if (contents == null) {
            return new ToolResult.Failure<>(
                    "skill_reference_not_found",
                    "Skill " + input.name() + " has no declared reference " + reference);
        }
        return new ToolResult.Success<>(new Output(
                document.metadata().name(),
                "",
                availableReferences,
                Map.of(reference, contents),
                document.metadata().allowedTools().stream().sorted().toList(),
                document.metadata().provenance()));
    }
}
