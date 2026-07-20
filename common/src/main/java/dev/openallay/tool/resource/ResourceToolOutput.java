package dev.openallay.tool.resource;

import com.google.gson.JsonElement;
import dev.openallay.agent.tool.AgentToolProjectionCarrier;
import dev.openallay.agent.tool.ModelToolResultView;
import dev.openallay.agent.tool.ToolResultDiagnostics;
import dev.openallay.agent.tool.ToolUiReference;
import dev.openallay.context.EvidenceBearing;
import dev.openallay.context.EvidenceMetadata;
import java.util.List;
import java.util.Objects;

/** Exact normalized envelope plus transient consumer projections. */
public final class ResourceToolOutput implements EvidenceBearing, AgentToolProjectionCarrier {
    private final String operation;
    private final String resultPath;
    private final List<Item> items;
    private final List<EvidenceMetadata> evidence;
    private transient final ModelToolResultView modelView;
    private transient final ToolUiReference uiReference;
    private transient final ToolResultDiagnostics diagnostics;
    private transient final boolean failure;

    ResourceToolOutput(
            String operation,
            String resultPath,
            List<Item> items,
            List<EvidenceMetadata> evidence,
            ModelToolResultView modelView,
            ToolUiReference uiReference,
            ToolResultDiagnostics diagnostics,
            boolean failure) {
        if (operation == null || operation.isBlank() || resultPath == null || resultPath.isBlank()) {
            throw new IllegalArgumentException("Resource Tool operation and result path are required");
        }
        this.operation = operation;
        this.resultPath = resultPath;
        this.items = List.copyOf(Objects.requireNonNull(items, "items"));
        this.evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence"));
        if (this.evidence.isEmpty()) throw new IllegalArgumentException("Resource Tool evidence is required");
        this.modelView = Objects.requireNonNull(modelView, "modelView");
        this.uiReference = Objects.requireNonNull(uiReference, "uiReference");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        this.failure = failure;
    }

    public String operation() { return operation; }
    public String resultPath() { return resultPath; }
    public List<Item> items() { return items; }
    @Override public List<EvidenceMetadata> evidence() { return evidence; }
    @Override public ModelToolResultView modelView() { return modelView; }
    @Override public ToolUiReference uiReference() { return uiReference; }
    @Override public ToolResultDiagnostics diagnostics() { return diagnostics; }
    @Override public boolean failure() { return failure; }

    public record Item(int inputIndex, String input, String status, JsonElement value, Failure failure) {
        public Item {
            if (inputIndex < 0 || input == null || input.isBlank()) {
                throw new IllegalArgumentException("Resource Tool item identity is invalid");
            }
            if (!"success".equals(status) && !"failure".equals(status)) {
                throw new IllegalArgumentException("Resource Tool item status is invalid");
            }
            if ((value == null) == (failure == null)) {
                throw new IllegalArgumentException("Resource Tool item needs exactly one outcome");
            }
            value = value == null ? null : value.deepCopy();
        }

        @Override public JsonElement value() { return value == null ? null : value.deepCopy(); }
    }

    public record Failure(String code, String path, String field, String message) {
        public Failure {
            if (code == null || code.isBlank() || message == null || message.isBlank()) {
                throw new IllegalArgumentException("Resource Tool failure code and message are required");
            }
        }
    }
}
