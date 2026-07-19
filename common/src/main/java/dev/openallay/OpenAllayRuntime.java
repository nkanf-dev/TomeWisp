package dev.openallay;

import dev.openallay.capability.CapabilitySettingsCatalog;
import dev.openallay.devmode.DevelopmentToolInspector;
import dev.openallay.knowledge.KnowledgeRegistry;
import dev.openallay.integration.patchouli.PatchouliMultiblockStore;
import dev.openallay.platform.PlatformService;
import dev.openallay.skill.SkillRepository;
import dev.openallay.tool.ToolRegistry;
import dev.openallay.trace.minecraft.TraceReplayService;
import java.util.Objects;

public record OpenAllayRuntime(
        PlatformService platform,
        ToolRegistry tools,
        KnowledgeRegistry knowledge,
        PatchouliMultiblockStore patchouliMultiblocks,
        SkillRepository skills,
        DevelopmentToolInspector developmentTools,
        TraceReplayService traceReplay,
        CapabilitySettingsCatalog capabilitySettings) {
    public OpenAllayRuntime {
        Objects.requireNonNull(capabilitySettings, "capabilitySettings");
    }

    public OpenAllayRuntime(
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
