package dev.openallay.resource.mount;

import dev.openallay.knowledge.KnowledgeKind;
import dev.openallay.knowledge.KnowledgeSnapshot;
import dev.openallay.integration.patchouli.PatchouliMultiblockStore;
import dev.openallay.resource.vfs.ResourceMount;
import dev.openallay.resource.vfs.ResourcePath;
import dev.openallay.resource.vfs.ResourceSnapshot;
import java.util.Objects;
import java.util.function.Supplier;

public final class GuideResourceMount implements ResourceMount {
    private final KnowledgeResourceMount delegate;

    public GuideResourceMount(Supplier<KnowledgeSnapshot> source) {
        this(source, null);
    }

    public GuideResourceMount(
            Supplier<KnowledgeSnapshot> source, PatchouliMultiblockStore multiblocks) {
        delegate = new KnowledgeResourceMount(
                "guide",
                Objects.requireNonNull(source, "source"),
                document -> document.kind() == KnowledgeKind.GUIDE_ENTRY
                        || document.kind() == KnowledgeKind.QUEST
                        || document.kind() == KnowledgeKind.STRUCTURE,
                multiblocks);
    }

    @Override
    public ResourcePath root() {
        return delegate.root();
    }

    @Override
    public ResourceSnapshot snapshot() {
        return delegate.snapshot();
    }
}
