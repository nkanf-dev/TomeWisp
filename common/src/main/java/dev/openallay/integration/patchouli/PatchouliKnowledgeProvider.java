package dev.openallay.integration.patchouli;

import dev.openallay.client.resource.ClientResourceAccess;
import dev.openallay.context.DataAuthority;
import dev.openallay.context.DataCompleteness;
import dev.openallay.context.EvidenceMetadata;
import dev.openallay.knowledge.KnowledgeLoad;
import dev.openallay.knowledge.KnowledgeSourceProvider;

public final class PatchouliKnowledgeProvider implements KnowledgeSourceProvider {
    private final ClientResourceAccess resources;
    private final String locale;
    private final PatchouliMultiblockStore store;
    private final String gameVersion;
    private final String loader;
    private volatile PatchouliParseResult latest = new PatchouliParseResult(
            java.util.List.of(), java.util.Map.of(), java.util.List.of());

    public PatchouliKnowledgeProvider(ClientResourceAccess resources, String locale) {
        this(resources, locale, null, "unknown", "unknown");
    }

    public PatchouliKnowledgeProvider(
            ClientResourceAccess resources, String locale, PatchouliMultiblockStore store) {
        this(resources, locale, store, "unknown", "unknown");
    }

    public PatchouliKnowledgeProvider(
            ClientResourceAccess resources,
            String locale,
            PatchouliMultiblockStore store,
            String gameVersion,
            String loader) {
        this.resources = resources;
        this.locale = locale;
        this.store = store;
        this.gameVersion = gameVersion;
        this.loader = loader;
    }

    @Override
    public String sourceId() {
        return "patchouli";
    }

    @Override
    public KnowledgeLoad load() {
        EvidenceMetadata evidence = new EvidenceMetadata(
                DataAuthority.RESOURCE_ASSET,
                DataCompleteness.COMPLETE,
                java.time.Instant.now(),
                "patchouli:resources",
                "patchouli:book_parser",
                gameVersion,
                loader,
                java.util.Map.of("patchouli:scope", "selected_resource_stack"));
        latest = new PatchouliBookParser().parse(resources, locale, evidence);
        if (store != null) {
            store.replace(latest.multiblocks());
        }
        return new KnowledgeLoad(latest.documents(), latest.diagnostics(), java.util.List.of(evidence));
    }

    public PatchouliParseResult latest() {
        return latest;
    }
}
