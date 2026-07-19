package dev.openallay.guide.history;

import java.util.UUID;

@FunctionalInterface
public interface GuideHistoryScopeProvider {
    GuideHistoryScope resolve(UUID actorId);
}
