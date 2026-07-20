package dev.openallay.agent.tool;

import dev.openallay.resource.vfs.ResourcePath;
import dev.openallay.resource.vfs.ResourcePresentation;
import java.util.List;

public record ToolUiReference(
        ResourcePath resultPath,
        List<ResourcePath> primaryResources,
        ResourcePresentation.Kind presentationKind,
        boolean continuationAvailable,
        ToolUiSummary summary) {
    public ToolUiReference {
        primaryResources = List.copyOf(primaryResources);
        presentationKind = presentationKind == null ? ResourcePresentation.Kind.NONE : presentationKind;
        summary = summary == null ? ToolUiSummary.none() : summary;
    }

    public ToolUiReference(
            ResourcePath resultPath,
            List<ResourcePath> primaryResources,
            ResourcePresentation.Kind presentationKind) {
        this(resultPath, primaryResources, presentationKind, false, ToolUiSummary.none());
    }

    public ToolUiReference(
            ResourcePath resultPath,
            List<ResourcePath> primaryResources,
            ResourcePresentation.Kind presentationKind,
            boolean continuationAvailable) {
        this(
                resultPath,
                primaryResources,
                presentationKind,
                continuationAvailable,
                ToolUiSummary.none());
    }

    public static ToolUiReference none() {
        return new ToolUiReference(
                null, List.of(), ResourcePresentation.Kind.NONE, false, ToolUiSummary.none());
    }
}
