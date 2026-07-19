package dev.tomewisp;

import dev.tomewisp.capability.CapabilitySettingsCatalog;
import dev.tomewisp.devmode.DevelopmentToolInspector;
import dev.tomewisp.knowledge.KnowledgeRegistry;
import dev.tomewisp.integration.patchouli.PatchouliMultiblockStore;
import dev.tomewisp.platform.PlatformService;
import dev.tomewisp.skill.SkillRepository;
import dev.tomewisp.tool.ToolRegistry;
import dev.tomewisp.trace.minecraft.TraceReplayService;
import java.util.Objects;

public record TomeWispRuntime(
        PlatformService platform,
        ToolRegistry tools,
        KnowledgeRegistry knowledge,
        PatchouliMultiblockStore patchouliMultiblocks,
        SkillRepository skills,
        DevelopmentToolInspector developmentTools,
        TraceReplayService traceReplay,
        CapabilitySettingsCatalog capabilitySettings) {
    public TomeWispRuntime {
        Objects.requireNonNull(capabilitySettings, "capabilitySettings");
    }

    public TomeWispRuntime(
            PlatformService platform,
            ToolRegistry tools,
            KnowledgeRegistry knowledge,
            PatchouliMultiblockStore patchouliMultiblocks,
            SkillRepository skills,
            DevelopmentToolInspector developmentTools,
            TraceReplayService traceReplay) {
        this(
                platform,
                tools,
                knowledge,
                patchouliMultiblocks,
                skills,
                developmentTools,
                traceReplay,
                new CapabilitySettingsCatalog());
    }
}
