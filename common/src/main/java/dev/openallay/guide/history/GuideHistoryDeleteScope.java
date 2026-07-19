package dev.openallay.guide.history;

import java.util.Objects;
import java.util.UUID;

/** Typed durable-history deletion scope; no path or foreign actor is supplied by UI text. */
public sealed interface GuideHistoryDeleteScope {
    record Partition(GuideHistoryScope scope) implements GuideHistoryDeleteScope {
        public Partition {
            Objects.requireNonNull(scope, "scope");
        }
    }

    record Actor(UUID actorId) implements GuideHistoryDeleteScope {
        public Actor {
            Objects.requireNonNull(actorId, "actorId");
        }
    }

    static Partition partition(GuideHistoryScope scope) {
        return new Partition(scope);
    }

    static Actor actor(UUID actorId) {
        return new Actor(actorId);
    }
}
