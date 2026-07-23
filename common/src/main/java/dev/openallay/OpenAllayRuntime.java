package dev.openallay;

import dev.openallay.capability.CapabilitySettingsCatalog;
import dev.openallay.devmode.DevelopmentToolInspector;
import dev.openallay.knowledge.KnowledgeRegistry;
import dev.openallay.integration.patchouli.PatchouliMultiblockStore;
import dev.openallay.platform.PlatformService;
import dev.openallay.skill.SkillRepository;
import dev.openallay.tool.ToolRegistry;
import dev.openallay.trace.minecraft.TraceReplayService;
import dev.openallay.resource.runtime.ResourceRequestRegistry;
import java.util.Objects;

public record OpenAllayRuntime(
        PlatformService platform,
        ToolRegistry tools,
        KnowledgeRegistry knowledge,
        PatchouliMultiblockStore patchouliMultiblocks,
        SkillRepository skills,
        DevelopmentToolInspector developmentTools,
        TraceReplayService traceReplay,
        CapabilitySettingsCatalog capabilitySettings,
        ResourceRequestRegistry resources) {
    public OpenAllayRuntime {
        Objects.requireNonNull(capabilitySettings, "capabilitySettings");
        Objects.requireNonNull(resources, "resources");
    }

    public OpenAllayRuntime(
            PlatformService platform,
            ToolRegistry tools,
            KnowledgeRegistry knowledge,
            PatchouliMultiblockStore patchouliMultiblocks,
            SkillRepository skills,
            DevelopmentToolInspector developmentTools,
            TraceReplayService traceReplay,
            CapabilitySettingsCatalog capabilitySettings) {
        this(
                platform,
                tools,
                knowledge,
                patchouliMultiblocks,
                skills,
                developmentTools,
                traceReplay,
                capabilitySettings,
                new ResourceRequestRegistry(
                        platform, knowledge, null, skills, patchouliMultiblocks));
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
                new CapabilitySettingsCatalog(),
                new ResourceRequestRegistry(
                        platform, knowledge, null, skills, patchouliMultiblocks));
    }
}
