package dev.tomewisp.integration.ftb.quests;

import dev.tomewisp.knowledge.KnowledgeDiagnostic;
import dev.tomewisp.knowledge.KnowledgeDocument;
import dev.tomewisp.knowledge.KnowledgeKind;
import dev.tomewisp.knowledge.KnowledgeLoad;
import dev.tomewisp.knowledge.KnowledgeSourceProvider;
import java.util.List;
import java.util.Set;

public final class FtbQuestsKnowledgeProvider implements KnowledgeSourceProvider {
    private final FtbQuestsBridge bridge;
    private final Object player;
    private final boolean clientSide;

    public FtbQuestsKnowledgeProvider(FtbQuestsBridge bridge, Object player, boolean clientSide) {
        this.bridge = bridge;
        this.player = player;
        this.clientSide = clientSide;
    }

    @Override public String sourceId() { return "ftbquests"; }

    @Override
    public KnowledgeLoad load() {
        FtbQuestSnapshot.Result result = bridge.snapshot(player, clientSide);
        if (!result.available()) {
            return new KnowledgeLoad(List.of(), List.of(new KnowledgeDiagnostic(
                    sourceId(), result.diagnosticCode(), result.diagnosticMessage(), "ftbquests:bridge")));
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
                        quest.provenance()))
                .toList();
        return KnowledgeLoad.of(documents);
    }
}
