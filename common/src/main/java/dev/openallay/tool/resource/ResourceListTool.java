package dev.openallay.tool.resource;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.openallay.agent.tool.ToolDescription;
import dev.openallay.agent.tool.ToolOptional;
import dev.openallay.context.ToolInvocationContext;
import dev.openallay.resource.vfs.ResourceDirectoryPage;
import dev.openallay.resource.vfs.ResourceFileSystem;
import dev.openallay.resource.vfs.ResourcePath;
import dev.openallay.resource.vfs.ResourcePresentation;
import dev.openallay.tool.ToolAccess;
import dev.openallay.tool.ToolDescriptor;
import dev.openallay.tool.ToolResult;
import java.util.ArrayList;
import java.util.List;

public final class ResourceListTool extends ResourceToolSupport<ResourceListTool.Input> {
    @ToolDescription("Batch direct-child listings; never recursively dumps a resource tree")
    public record Input(
            @ToolDescription("Absolute virtual paths to list in one call") List<String> paths,
            @ToolDescription("Include child kind and label metadata") @ToolOptional Boolean includeMetadata,
            @ToolDescription("Semantic continuation cursor from a previous result") @ToolOptional String cursor) {
        public Input(List<String> paths, Boolean includeMetadata) {
            this(paths, includeMetadata, null);
        }
    }

    private static final ToolDescriptor<Input, ResourceToolOutput> DESCRIPTOR = new ToolDescriptor<>(
            "openallay:resource_list",
            "List direct children of one or more OpenAllay virtual resource paths. Batch independent paths in one call.",
            Input.class, ResourceToolOutput.class, ToolAccess.READ_ONLY, REQUIRED_CONTEXT);
    private final ResourceFileSystem fileSystem;

    public ResourceListTool(RequestResourceContext resources) {
        this(resources, new ResourceFileSystem());
    }

    ResourceListTool(RequestResourceContext resources, ResourceFileSystem fileSystem) {
        super(resources);
        this.fileSystem = fileSystem;
    }

    @Override public ToolDescriptor<Input, ResourceToolOutput> descriptor() { return DESCRIPTOR; }

    @Override
    protected ToolResult<ResourceToolOutput> execute(
            RequestResourceContext.Session session, ToolInvocationContext context, Input input) {
        if (input != null && input.cursor() != null && !input.cursor().isBlank()) {
            if (input.paths() != null && !input.paths().isEmpty()) {
                return new ToolResult.Failure<>("invalid_arguments", "cursor continuation cannot include new paths");
            }
            return continueCursor(session, context, input.cursor(), GSON.toJson(input));
        }
        if (input == null || input.paths() == null || input.paths().isEmpty()) {
            return new ToolResult.Failure<>("invalid_arguments", "paths must contain at least one virtual path");
        }
        List<ResourcePath> paths;
        try {
            paths = input.paths().stream().map(ResourcePath::parse).toList();
        } catch (RuntimeException failure) {
            return new ToolResult.Failure<>("invalid_resource_path", failure.getMessage());
        }
        boolean metadata = !Boolean.FALSE.equals(input.includeMetadata());
        List<ResourceFileSystem.OperationResult<ResourceDirectoryPage>> results = fileSystem.list(session.view(), paths);
        ArrayList<ResourceToolOutput.Item> items = new ArrayList<>();
        for (ResourceFileSystem.OperationResult<ResourceDirectoryPage> result : results) {
            String source = input.paths().get(result.inputIndex());
            if (!result.succeeded()) {
                items.add(failure(result.inputIndex(), source, result.failure()));
                continue;
            }
            ResourceDirectoryPage page = result.value();
            JsonObject value = new JsonObject();
            value.addProperty("path", page.path().toString());
            value.addProperty("generation", page.generationId());
            JsonArray children = new JsonArray();
            page.entries().forEach(entry -> {
                if (metadata) {
                    JsonObject child = new JsonObject();
                    child.addProperty("path", entry.path().toString());
                    child.addProperty("kind", entry.kind().name().toLowerCase());
                    child.addProperty("label", entry.label());
                    children.add(child);
                } else {
                    children.add(entry.path().toString());
                }
            });
            value.add("children", children);
            items.add(success(result.inputIndex(), source, value));
        }
        List<ResourcePath> existing = existingInputs(session, paths);
        return publish(session, context, "resource_list", GSON.toJson(input), items, existing, existing,
                ResourcePresentation.Kind.TABLE);
    }
}
