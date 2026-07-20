package dev.openallay.resource.vfs;

import dev.openallay.context.EvidenceMetadata;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public record ResourceNode(
        ResourcePath path,
        ResourceKind kind,
        ResourceValue truth,
        List<ResourceEntry> children,
        List<ResourceLink> links,
        EvidenceMetadata evidence,
        ResourcePresentation presentation) {
    public ResourceNode {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(truth, "truth");
        ArrayList<ResourceEntry> childCopy = new ArrayList<>(Objects.requireNonNull(children, "children"));
        childCopy.sort(Comparator.naturalOrder());
        children = List.copyOf(childCopy);
        ArrayList<ResourceLink> linkCopy = new ArrayList<>(Objects.requireNonNull(links, "links"));
        linkCopy.sort(Comparator.naturalOrder());
        links = List.copyOf(linkCopy);
        Objects.requireNonNull(evidence, "evidence");
        Objects.requireNonNull(presentation, "presentation");
    }
}
