package dev.openallay.tool.resource;

import dev.openallay.context.EvidenceMetadata;
import dev.openallay.context.ToolInvocationContext;
import dev.openallay.model.CancellationSignal;
import dev.openallay.resource.result.ResourceResultStore;
import dev.openallay.resource.cursor.ResourceCursorStore;
import dev.openallay.resource.vfs.ResourceView;
import dev.openallay.knowledge.online.OnlineKnowledgeAccess;
import java.util.Objects;

/** Resolves the immutable VFS view and live result scope owned by one Agent request. */
@FunctionalInterface
public interface RequestResourceContext {
    Session capture(ToolInvocationContext invocation, CancellationSignal cancellation);

    record Session(
            ResourceView view,
            ResourceResultStore results,
            ResourceResultStore.Scope resultScope,
            EvidenceMetadata operationEvidence,
            ResourceCursorStore cursors,
            int projectionTokenBudget,
            OnlineKnowledgeAccess onlineKnowledge) {
        public Session {
            Objects.requireNonNull(view, "view");
            Objects.requireNonNull(results, "results");
            Objects.requireNonNull(resultScope, "resultScope");
            Objects.requireNonNull(operationEvidence, "operationEvidence");
            Objects.requireNonNull(cursors, "cursors");
            Objects.requireNonNull(onlineKnowledge, "onlineKnowledge");
            if (projectionTokenBudget < 1) {
                throw new IllegalArgumentException("projectionTokenBudget must be positive");
            }
            if (!view.scope().actorId().equals(resultScope.actorId())
                    || !view.scope().sessionId().equals(resultScope.sessionId())
                    || view.scope().connectionGeneration() != resultScope.connectionGeneration()) {
                throw new IllegalArgumentException("Resource view and result scope identities differ");
            }
        }

        public Session(
                ResourceView view,
                ResourceResultStore results,
                ResourceResultStore.Scope resultScope,
                EvidenceMetadata operationEvidence,
                ResourceCursorStore cursors,
                int projectionTokenBudget) {
            this(view, results, resultScope, operationEvidence, cursors, projectionTokenBudget,
                    OnlineKnowledgeAccess.unavailable());
        }

    }
}
