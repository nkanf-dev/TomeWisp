package dev.tomewisp.integration.patchouli;

import dev.tomewisp.client.resource.ClientResourceAccess;
import dev.tomewisp.knowledge.KnowledgeLoad;
import dev.tomewisp.knowledge.KnowledgeSourceProvider;

public final class PatchouliKnowledgeProvider implements KnowledgeSourceProvider {
    private final ClientResourceAccess resources;
    private final String locale;
    private volatile PatchouliParseResult latest = new PatchouliParseResult(
            java.util.List.of(), java.util.Map.of(), java.util.List.of());

    public PatchouliKnowledgeProvider(ClientResourceAccess resources, String locale) {
        this.resources = resources;
        this.locale = locale;
    }

    @Override
    public String sourceId() {
        return "patchouli";
    }

    @Override
    public KnowledgeLoad load() {
        latest = new PatchouliBookParser().parse(resources, locale);
        return new KnowledgeLoad(latest.documents(), latest.diagnostics());
    }

    public PatchouliParseResult latest() {
        return latest;
    }
}
