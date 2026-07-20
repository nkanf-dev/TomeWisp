package dev.openallay.tool.resource;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.openallay.agent.tool.ToolDescription;
import dev.openallay.agent.tool.ToolOptional;
import dev.openallay.context.ToolInvocationContext;
import dev.openallay.resource.query.ResourceQueryEngine;
import dev.openallay.resource.query.ResourceQueryPlan;
import dev.openallay.resource.query.ResourceQueryStage;
import dev.openallay.resource.vfs.ResourcePath;
import dev.openallay.resource.vfs.ResourcePresentation;
import dev.openallay.resource.vfs.ResourceValue;
import dev.openallay.resource.vfs.ResourceValues;
import dev.openallay.tool.ToolAccess;
import dev.openallay.tool.ToolDescriptor;
import dev.openallay.tool.ToolResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class ResourceQueryTool extends ResourceToolSupport<ResourceQueryTool.Input> {
    @ToolDescription("One typed stage in a closed VFS analytical pipeline")
    public record Stage(
            @ToolDescription("search, filter, select, expand, sort, group, aggregate, take, or follow") String operation,
            @ToolOptional String query,
            @ToolOptional String field,
            @ToolOptional ResourceQueryStage.Operator operator,
            @ToolOptional JsonElement value,
            @ToolOptional List<String> fields,
            @ToolOptional ResourceQueryStage.Direction direction,
            @ToolOptional ResourceQueryStage.AggregateFunction function,
            @ToolOptional String groupBy,
            @ToolOptional Integer count,
            @ToolOptional String relation,
            @ToolOptional Integer maxDepth) {}

    @ToolDescription("One analytical query over one or more VFS roots")
    public record Plan(List<String> roots, List<Stage> pipeline) {}

    @ToolDescription("Batch of independent typed VFS queries")
    public record Input(List<Plan> plans, @ToolOptional String cursor) {}

    private static final ToolDescriptor<Input, ResourceToolOutput> DESCRIPTOR = new ToolDescriptor<>(
            "openallay:resource_query",
            "Run closed typed search/filter/select/expand/sort/group/aggregate/take/follow pipelines over VFS resources. Batch plans.",
            Input.class, ResourceToolOutput.class, ToolAccess.READ_ONLY, REQUIRED_CONTEXT);
    private final ResourceQueryEngine engine;

    public ResourceQueryTool(RequestResourceContext resources) {
        this(resources, new ResourceQueryEngine());
    }

    ResourceQueryTool(RequestResourceContext resources, ResourceQueryEngine engine) {
        super(resources);
        this.engine = engine;
    }

    @Override public ToolDescriptor<Input, ResourceToolOutput> descriptor() { return DESCRIPTOR; }

    @Override
    protected ToolResult<ResourceToolOutput> execute(
            RequestResourceContext.Session session, ToolInvocationContext context, Input input) {
        if (input != null && input.cursor() != null && !input.cursor().isBlank()) {
            if (input.plans() != null && !input.plans().isEmpty()) {
                return new ToolResult.Failure<>("invalid_arguments", "cursor continuation cannot include new plans");
            }
            return continueCursor(session, context, input.cursor(), GSON.toJson(input));
        }
        if (input == null || input.plans() == null || input.plans().isEmpty()) {
            return new ToolResult.Failure<>("invalid_arguments", "plans must contain at least one initial query");
        }
        ArrayList<ResourceQueryPlan> plans = new ArrayList<>();
        ArrayList<ResourcePath> roots = new ArrayList<>();
        try {
            for (Plan requested : input.plans()) {
                List<ResourcePath> parsedRoots = requested.roots().stream().map(ResourcePath::parse).toList();
                roots.addAll(parsedRoots);
                plans.add(new ResourceQueryPlan(parsedRoots, requested.pipeline().stream().map(ResourceQueryTool::stage).toList()));
            }
        } catch (RuntimeException failure) {
            return new ToolResult.Failure<>("invalid_resource_query", failure.getMessage());
        }
        List<ResourceQueryEngine.BatchResult> results = engine.execute(session.view(), plans);
        ArrayList<ResourceToolOutput.Item> items = new ArrayList<>();
        ArrayList<ResourcePath> primary = new ArrayList<>();
        for (ResourceQueryEngine.BatchResult result : results) {
            String source = String.join(",", input.plans().get(result.inputIndex()).roots());
            if (result.failure() != null) {
                items.add(failure(result.inputIndex(), source, result.failure()));
                continue;
            }
            ResourceQueryEngine.Result query = result.value();
            JsonObject value = new JsonObject();
            value.addProperty("source_rows", query.sourceRows());
            JsonArray stages = new JsonArray();
            query.stages().forEach(stage -> {
                JsonObject encoded = new JsonObject();
                encoded.addProperty("index", stage.index());
                encoded.addProperty("operation", stage.operation());
                encoded.addProperty("input_rows", stage.inputRows());
                encoded.addProperty("output_rows", stage.outputRows());
                stages.add(encoded);
            });
            value.add("stages", stages);
            JsonArray rows = new JsonArray();
            query.rows().forEach(row -> {
                JsonObject encoded = new JsonObject();
                encoded.addProperty("source", row.source().toString());
                encoded.addProperty("ordinal", row.ordinal());
                JsonObject fields = new JsonObject();
                row.fields().forEach((field, fieldValue) -> fields.add(field, json(fieldValue)));
                encoded.add("fields", fields);
                rows.add(encoded);
                primary.add(row.source());
            });
            value.add("rows", rows);
            value.add("source_schema", GSON.toJsonTree(query.sourceSchema()));
            items.add(success(result.inputIndex(), source, value));
        }
        return publish(session, context, "resource_query", GSON.toJson(input), items,
                existingInputs(session, roots), primary.stream().distinct().toList(), ResourcePresentation.Kind.TABLE);
    }

    private static ResourceQueryStage stage(Stage stage) {
        if (stage == null || stage.operation() == null) throw new IllegalArgumentException("query stage operation is required");
        return switch (stage.operation().toLowerCase(Locale.ROOT)) {
            case "search" -> new ResourceQueryStage.Search(stage.query(), stage.field());
            case "filter" -> new ResourceQueryStage.Filter(
                    stage.field(), stage.operator(), stage.operator() == ResourceQueryStage.Operator.EXISTS
                            ? null : scalar(stage.value()));
            case "select" -> new ResourceQueryStage.Select(stage.fields());
            case "sort" -> new ResourceQueryStage.Sort(stage.field(), stage.direction());
            case "group" -> new ResourceQueryStage.Group(stage.field());
            case "aggregate" -> new ResourceQueryStage.Aggregate(stage.field(), stage.function(), stage.groupBy());
            case "expand" -> new ResourceQueryStage.Expand(stage.field());
            case "take" -> new ResourceQueryStage.Take(stage.count() == null ? -1 : stage.count());
            case "follow" -> new ResourceQueryStage.Follow(
                    stage.relation(), stage.maxDepth() == null ? 1 : stage.maxDepth());
            default -> throw new IllegalArgumentException("unknown query stage: " + stage.operation());
        };
    }

    private static ResourceValue.Scalar scalar(JsonElement value) {
        ResourceValue decoded = ResourceValues.fromJson(value);
        if (decoded instanceof ResourceValue.Scalar scalar) return scalar;
        throw new IllegalArgumentException("filter value must be a scalar");
    }
}
