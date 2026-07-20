package dev.openallay.tool.resource;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.openallay.agent.tool.ToolDescription;
import dev.openallay.agent.tool.ToolOptional;
import dev.openallay.context.ToolInvocationContext;
import dev.openallay.resource.vfs.ResourceFileSystem;
import dev.openallay.resource.vfs.ResourceGlobPattern;
import dev.openallay.resource.vfs.ResourceKind;
import dev.openallay.resource.vfs.ResourcePath;
import dev.openallay.resource.vfs.ResourcePresentation;
import dev.openallay.tool.ToolAccess;
import dev.openallay.tool.ToolDescriptor;
import dev.openallay.tool.ToolResult;
import java.util.ArrayList;
import java.util.List;

public final class ResourceGlobTool extends ResourceToolSupport<ResourceGlobTool.Input> {
    @ToolDescription("One bounded glob under a literal VFS mount")
    public record Pattern(
            @ToolDescription("Absolute pattern using segment-local *, ?, or recursive **") String pattern,
            @ToolDescription("Optional exact Resource kind") @ToolOptional ResourceKind kind) {}

    @ToolDescription("Batch of independent virtual path globs")
    public record Input(List<Pattern> patterns, @ToolOptional String cursor) {}

    private static final ToolDescriptor<Input, ResourceToolOutput> DESCRIPTOR = new ToolDescriptor<>(
            "openallay:resource_glob",
            "Discover canonical OpenAllay VFS paths with bounded *, ?, and ** patterns. Batch patterns in one call.",
            Input.class, ResourceToolOutput.class, ToolAccess.READ_ONLY, REQUIRED_CONTEXT);
    private final ResourceFileSystem fileSystem;

    public ResourceGlobTool(RequestResourceContext resources) {
        this(resources, new ResourceFileSystem());
    }

    ResourceGlobTool(RequestResourceContext resources, ResourceFileSystem fileSystem) {
        super(resources);
        this.fileSystem = fileSystem;
    }

    @Override public ToolDescriptor<Input, ResourceToolOutput> descriptor() { return DESCRIPTOR; }

    @Override
    protected ToolResult<ResourceToolOutput> execute(
            RequestResourceContext.Session session, ToolInvocationContext context, Input input) {
        if (input != null && input.cursor() != null && !input.cursor().isBlank()) {
            if (input.patterns() != null && !input.patterns().isEmpty()) {
                return new ToolResult.Failure<>("invalid_arguments", "cursor continuation cannot include new patterns");
            }
            return continueCursor(session, context, input.cursor(), GSON.toJson(input));
        }
        if (input == null || input.patterns() == null || input.patterns().isEmpty()) {
            return new ToolResult.Failure<>("invalid_arguments", "patterns must contain at least one initial glob");
        }
        List<ResourceGlobPattern> patterns;
        try {
            patterns = input.patterns().stream().map(pattern -> ResourceGlobPattern.compile(pattern.pattern())).toList();
        } catch (RuntimeException failure) {
            return new ToolResult.Failure<>("invalid_glob", failure.getMessage());
        }
        var results = fileSystem.glob(session.view(), patterns);
        ArrayList<ResourceToolOutput.Item> items = new ArrayList<>();
        ArrayList<ResourcePath> matched = new ArrayList<>();
        for (ResourceFileSystem.OperationResult<List<ResourcePath>> result : results) {
            Pattern requested = input.patterns().get(result.inputIndex());
            if (!result.succeeded()) {
                items.add(failure(result.inputIndex(), requested.pattern(), result.failure()));
                continue;
            }
            JsonArray paths = new JsonArray();
            result.value().stream()
                    .filter(path -> requested.kind() == null || session.view().require(path).kind() == requested.kind())
                    .forEach(path -> {
                        JsonObject encoded = new JsonObject();
                        encoded.addProperty("path", path.toString());
                        encoded.addProperty("kind", session.view().require(path).kind().name().toLowerCase());
                        paths.add(encoded);
                        matched.add(path);
                    });
            JsonObject value = new JsonObject();
            value.add("matches", paths);
            items.add(success(result.inputIndex(), requested.pattern(), value));
        }
        List<ResourcePath> roots = patterns.stream().map(pattern -> ResourcePath.of(pattern.mount())).toList();
        return publish(session, context, "resource_glob", GSON.toJson(input), items,
                existingInputs(session, roots), matched, ResourcePresentation.Kind.TABLE);
    }
}
