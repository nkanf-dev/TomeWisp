package dev.openallay.guide.e2e;

import dev.openallay.context.EvidenceMetadata;
import dev.openallay.guide.GuideRequestStatus;
import dev.openallay.guide.GuideTopology;
import dev.openallay.guide.GuideToolStatus;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record GuideE2EReport(
        String loader,
        String gameVersion,
        String modVersion,
        String scenario,
        GuideTopology topology,
        UUID requestId,
        String sessionId,
        List<GuideRequestStatus> transitions,
        List<String> toolIds,
        List<ToolProbe> toolProbes,
        List<EvidenceMetadata> evidence,
        List<String> timelineKinds,
        Map<String, Long> semanticMetrics,
        List<String> semanticDiagnosticCodes,
        List<String> controlledComponentTypes,
        String historyPageState,
        Map<String, Long> historyMetrics,
        GuideRequestStatus outcome,
        String failureCode,
        String failureMessage,
        Map<String, Long> timingsMillis,
        Map<String, String> payloadHashes) {
    public GuideE2EReport {
        transitions = List.copyOf(transitions);
        toolIds = List.copyOf(toolIds);
        toolProbes = List.copyOf(toolProbes);
        evidence = List.copyOf(evidence);
        timelineKinds = List.copyOf(timelineKinds);
        semanticMetrics = Map.copyOf(semanticMetrics);
        semanticDiagnosticCodes = List.copyOf(semanticDiagnosticCodes);
        controlledComponentTypes = List.copyOf(controlledComponentTypes);
        if (historyPageState == null || historyPageState.isBlank()) {
            throw new IllegalArgumentException("historyPageState is required");
        }
        historyMetrics = Map.copyOf(historyMetrics);
        timingsMillis = Map.copyOf(timingsMillis);
        payloadHashes = Map.copyOf(payloadHashes);
    }

    /** Redacted E2E-only proof that each intended call completed with the expected section. */
    public record ToolProbe(
            String toolId, GuideToolStatus status, String section, String failureCode) {}
}
