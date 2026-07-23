package dev.openallay.tool.builtin;

import com.google.gson.JsonElement;
import dev.openallay.agent.tool.ToolDescription;
import dev.openallay.agent.tool.ToolOptional;
import dev.openallay.context.ContextCapability;
import dev.openallay.context.EvidenceBearing;
import dev.openallay.context.EvidenceMetadata;
import dev.openallay.context.ToolInvocationContext;
import dev.openallay.model.CancellationSignal;
import dev.openallay.model.ModelClientException;
import dev.openallay.script.JavascriptExecution;
import dev.openallay.script.JavascriptExecutionException;
import dev.openallay.script.RhinoJavascriptRuntime;
import dev.openallay.script.data.MinecraftAgentDataProjector;
import dev.openallay.script.workspace.AgentResultWorkspace;
import dev.openallay.script.workspace.AgentResultWorkspaceRegistry;
import dev.openallay.script.workspace.JavascriptResultPresenter;
import dev.openallay.script.workspace.WorkspaceException;
import dev.openallay.tool.Tool;
import dev.openallay.tool.ToolAccess;
import dev.openallay.tool.ToolDescriptor;
import dev.openallay.tool.ToolResult;
import dev.openallay.tool.RequestScopeParticipant;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public final class RunJavascriptTool
        implements Tool<RunJavascriptTool.Input, RunJavascriptTool.Output>, RequestScopeParticipant {
    @ToolDescription("A JavaScript program body. End with an explicit return statement.")
    public record Input(
            String source,
            @ToolDescription("Opaque result handles this script needs to reopen.")
                    @ToolOptional List<String> handles) {
        public Input {
            handles = handles == null ? List.of() : List.copyOf(handles);
        }
    }

    public record Output(
            String handle,
            String resultType,
            long cardinality,
            List<String> fields,
            String modelText,
            boolean complete,
            int omittedRows,
            long elapsedMillis,
            List<EvidenceMetadata> evidence)
            implements EvidenceBearing {
        public Output {
            fields = List.copyOf(fields);
            evidence = List.copyOf(evidence);
        }
    }

    private static final ToolDescriptor<Input, Output> DESCRIPTOR = new ToolDescriptor<>(
            "openallay:run_javascript",
            "Analyze the current detached Minecraft data with isolated JavaScript. "
                    + "Use mc arrays with filter/map/reduce/sort/grouping; large results stay in a request workspace "
                    + "and can be reopened by an opaque handle. This runtime cannot access Java, files, network, "
                    + "commands, live game objects, or perform writes.",
            Input.class,
            Output.class,
            ToolAccess.READ_ONLY,
            Set.of(
                    ContextCapability.REGISTRIES,
                    ContextCapability.RECIPES,
                    ContextCapability.PLAYER,
                    ContextCapability.OBSERVABLE_GAME_STATE));

    private final RhinoJavascriptRuntime runtime;
    private final MinecraftAgentDataProjector projector;
    private final AgentResultWorkspaceRegistry workspaces;
    private final JavascriptResultPresenter presenter;

    public RunJavascriptTool(
            RhinoJavascriptRuntime runtime,
            MinecraftAgentDataProjector projector,
            AgentResultWorkspaceRegistry workspaces,
            JavascriptResultPresenter presenter) {
        this.runtime = runtime;
        this.projector = projector;
        this.workspaces = workspaces;
        this.presenter = presenter;
    }

    @Override
    public ToolDescriptor<Input, Output> descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public ToolResult<Output> invoke(ToolInvocationContext context, Input input) {
        throw new UnsupportedOperationException("run_javascript is asynchronous");
    }

    @Override
    public CompletableFuture<ToolResult<Output>> invokeAsync(
            ToolInvocationContext context, Input input, CancellationSignal cancellation) {
        if (input == null || input.source() == null || input.source().isBlank()) {
            return CompletableFuture.completedFuture(
                    new ToolResult.Failure<>("invalid_tool_arguments", "source must not be blank"));
        }
        CompletableFuture<ToolResult<Output>> future = new CompletableFuture<>();
        Thread worker = Thread.ofVirtual()
                .name("openallay-javascript-" + context.correlationId())
                .start(() -> execute(context, input, cancellation, future));
        cancellation.onCancel(worker::interrupt);
        return future;
    }

    private void execute(
            ToolInvocationContext context,
            Input input,
            CancellationSignal cancellation,
            CompletableFuture<ToolResult<Output>> future) {
        try {
            cancellation.throwIfCancelled();
            var projection = projector.project(context);
            AgentResultWorkspace workspace = workspaces.open(context.correlationId());
            JavascriptExecution execution = runtime.execute(
                    input.source(),
                    projection.data(),
                    workspace.select(input.handles()),
                    cancellation);
            JsonElement canonical = execution.value();
            String handle = workspace.store(canonical);
            var presentation = presenter.present(handle, canonical);
            if (projection.evidence().isEmpty()) {
                future.complete(new ToolResult.Failure<>(
                        "context_evidence_unavailable",
                        "No evidence-bearing Minecraft data was captured for this request"));
                return;
            }
            future.complete(new ToolResult.Success<>(new Output(
                    handle,
                    presentation.type(),
                    presentation.cardinality(),
                    presentation.fields(),
                    presentation.modelText(),
                    presentation.complete(),
                    presentation.omittedRows(),
                    execution.elapsed().toMillis(),
                    projection.evidence())));
        } catch (ModelClientException cancelled) {
            future.completeExceptionally(cancelled);
        } catch (JavascriptExecutionException failure) {
            future.complete(new ToolResult.Failure<>(failure.code(), failure.getMessage()));
        } catch (WorkspaceException failure) {
            future.complete(new ToolResult.Failure<>(failure.code(), failure.getMessage()));
        } catch (RuntimeException failure) {
            String message = failure.getMessage();
            future.complete(new ToolResult.Failure<>(
                    "javascript_failure",
                    message == null || message.isBlank()
                            ? "JavaScript execution failed"
                            : message));
        }
    }

    @Override
    public void closeRequestScope(String correlationId) {
        workspaces.close(correlationId);
    }
}
