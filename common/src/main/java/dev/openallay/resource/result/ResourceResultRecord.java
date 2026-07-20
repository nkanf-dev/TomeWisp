package dev.openallay.resource.result;

import dev.openallay.context.EvidenceMetadata;
import dev.openallay.resource.vfs.ResourceKind;
import dev.openallay.resource.vfs.ResourceNode;
import dev.openallay.resource.vfs.ResourcePath;
import java.time.Instant;
import java.util.Objects;

/** Exact immutable record published before any model or UI projection is derived. */
public record ResourceResultRecord(
        ResourceResultId id,
        ResourceResultStore.Scope scope,
        String invocationId,
        ResourceResultLineage lineage,
        String contentDigest,
        ResourceNode node,
        Instant publishedAt) {
    public ResourceResultRecord {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(scope, "scope");
        if (invocationId == null || invocationId.isBlank()) {
            throw new IllegalArgumentException("invocationId is required");
        }
        Objects.requireNonNull(lineage, "lineage");
        if (contentDigest == null || !contentDigest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("contentDigest must be a lowercase SHA-256 digest");
        }
        Objects.requireNonNull(node, "node");
        if (!node.path().equals(id.path())) {
            throw new IllegalArgumentException("Result node path differs from its public ID");
        }
        Objects.requireNonNull(publishedAt, "publishedAt");
    }

    public ResourcePath path() {
        return id.path();
    }

    public ResourceKind contentKind() {
        return node.kind();
    }

    public EvidenceMetadata evidence() {
        return node.evidence();
    }

    /** Safe durable display metadata. It intentionally contains no exact value or resumable cursor. */
    public DisplayReceipt displayReceipt() {
        return new DisplayReceipt(path().toString(), invocationId, contentKind(), contentDigest,
                evidence().authority().name(), evidence().completeness().name(), publishedAt);
    }

    public record DisplayReceipt(
            String expiredPath,
            String invocationId,
            ResourceKind contentKind,
            String contentDigest,
            String authority,
            String completeness,
            Instant publishedAt) {
        public DisplayReceipt {
            if (expiredPath == null || expiredPath.isBlank()
                    || invocationId == null || invocationId.isBlank()
                    || contentDigest == null || contentDigest.isBlank()
                    || authority == null || authority.isBlank()
                    || completeness == null || completeness.isBlank()) {
                throw new IllegalArgumentException("Display receipt fields are required");
            }
            Objects.requireNonNull(contentKind, "contentKind");
            Objects.requireNonNull(publishedAt, "publishedAt");
        }
    }
}
