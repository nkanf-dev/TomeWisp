package dev.openallay.integration.ftb.quests;

import dev.openallay.context.DataAuthority;
import dev.openallay.context.DataCompleteness;
import dev.openallay.context.EvidenceMetadata;
import dev.openallay.knowledge.KnowledgeDiagnostic;
import dev.openallay.knowledge.KnowledgeDocument;
import dev.openallay.knowledge.KnowledgeKind;
import dev.openallay.knowledge.KnowledgeLoad;
import dev.openallay.knowledge.KnowledgeSourceProvider;
import java.util.List;
import java.util.Set;

public final class FtbQuestsKnowledgeProvider implements KnowledgeSourceProvider {
    private final FtbQuestsBridge bridge;
    private final Object player;
    private final boolean clientSide;
    private final String gameVersion;
    private final String loader;

    public FtbQuestsKnowledgeProvider(FtbQuestsBridge bridge, Object player, boolean clientSide) {
        this(bridge, player, clientSide, "unknown", "unknown");
    }

    public FtbQuestsKnowledgeProvider(
            FtbQuestsBridge bridge,
            Object player,
            boolean clientSide,
            String gameVersion,
            String loader) {
        this.bridge = bridge;
        this.player = player;
        this.clientSide = clientSide;
        this.gameVersion = gameVersion;
        this.loader = loader;
    }

    @Override public String sourceId() { return "ftbquests"; }

    @Override
    public KnowledgeLoad load() {
        java.time.Instant capturedAt = java.time.Instant.now();
        EvidenceMetadata sourceEvidence = new EvidenceMetadata(
                DataAuthority.INTEGRATION_API,
                DataCompleteness.COMPLETE,
                capturedAt,
                "ftbquests:api",
                "ftbquests:bridge",
                gameVersion,
                loader,
                java.util.Map.of("ftbquests:side", clientSide ? "client" : "server"));
        FtbQuestSnapshot.Result result = bridge.snapshot(player, clientSide);
        if (!result.available()) {
            EvidenceMetadata unavailable = new EvidenceMetadata(
                    sourceEvidence.authority(),
                    DataCompleteness.UNKNOWN,
                    sourceEvidence.capturedAt(),
                    sourceEvidence.sourceId(),
                    sourceEvidence.provenance(),
                    sourceEvidence.gameVersion(),
                    sourceEvidence.loader(),
                    java.util.Map.of(
                            "ftbquests:side", clientSide ? "client" : "server",
                            "ftbquests:availability", "unavailable"));
            return new KnowledgeLoad(List.of(), List.of(new KnowledgeDiagnostic(
                    sourceId(), result.diagnosticCode(), result.diagnosticMessage(), "ftbquests:bridge")),
                    List.of(unavailable));
        }
        List<KnowledgeDocument> documents = result.quests().stream()
                .map(quest -> new KnowledgeDocument(
                        sourceId(),
                        quest.questId(),
                        KnowledgeKind.QUEST,
                        quest.title(),
                        quest.description() + "\nDependencies: " + quest.dependencyIds()
                                + "\nCompleted: " + quest.completed(),
                        "ftbquests",
                        Set.of(),
                        Set.of(),
                        null,
                        true,
                        quest.provenance(),
                        new EvidenceMetadata(
                                sourceEvidence.authority(),
                                sourceEvidence.completeness(),
                                sourceEvidence.capturedAt(),
                                sourceEvidence.sourceId(),
                                sourceEvidence.provenance(),
                                sourceEvidence.gameVersion(),
                                sourceEvidence.loader(),
                                java.util.Map.of(
                                        "ftbquests:side", clientSide ? "client" : "server",
                                        "ftbquests:quest_provenance", quest.provenance()))))
                .toList();
        return new KnowledgeLoad(documents, List.of(), List.of(sourceEvidence));
    }
}
