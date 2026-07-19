package dev.openallay.integration.ftb.quests;

import java.util.List;
import java.util.Set;

public record FtbQuestSnapshot(
        String questId,
        String chapterId,
        String chapterTitle,
        String title,
        String description,
        Set<String> dependencyIds,
        boolean completed,
        String provenance) {
    public FtbQuestSnapshot {
        dependencyIds = Set.copyOf(dependencyIds);
    }

    public record Result(
            boolean available, List<FtbQuestSnapshot> quests, String diagnosticCode, String diagnosticMessage) {
        public Result {
            quests = List.copyOf(quests);
        }

        public static Result unavailable(String code, String message) {
            return new Result(false, List.of(), code, message);
        }

        public static Result available(List<FtbQuestSnapshot> quests) {
            return new Result(true, quests, null, null);
        }
    }
}
