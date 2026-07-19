package dev.tomewisp;

import com.google.gson.Gson;
import dev.tomewisp.capability.CapabilityChildPage;
import dev.tomewisp.capability.CapabilityKind;
import dev.tomewisp.capability.CapabilitySettingsCatalog;
import dev.tomewisp.capability.CapabilitySettingsDescriptor;
import dev.tomewisp.context.minecraft.MinecraftContextCapture;
import dev.tomewisp.devmode.DevelopmentToolInspector;
import dev.tomewisp.knowledge.KnowledgeRegistry;
import dev.tomewisp.integration.patchouli.PatchouliMultiblockStore;
import dev.tomewisp.platform.PlatformService;
import dev.tomewisp.platform.PlatformServices;
import dev.tomewisp.skill.BundledSkillLoader;
import dev.tomewisp.skill.LoadSkillTool;
import dev.tomewisp.skill.SkillParser;
import dev.tomewisp.skill.SkillRepository;
import dev.tomewisp.tool.ToolRegistry;
import dev.tomewisp.tool.Tool;
import dev.tomewisp.tool.builtin.CalculateCraftabilityTool;
import dev.tomewisp.tool.builtin.FindRecipesTool;
import dev.tomewisp.tool.builtin.FindItemUsagesTool;
import dev.tomewisp.tool.builtin.GetRecipeTool;
import dev.tomewisp.tool.builtin.GetKnowledgeDocumentTool;
import dev.tomewisp.tool.builtin.GetPatchouliMultiblockTool;
import dev.tomewisp.tool.builtin.ListKnowledgeSourcesTool;
import dev.tomewisp.tool.builtin.InspectInventoryTool;
import dev.tomewisp.tool.builtin.InspectGameStateTool;
import dev.tomewisp.tool.builtin.ResolveResourceTool;
import dev.tomewisp.tool.builtin.SearchKnowledgeTool;
import dev.tomewisp.tool.builtin.SearchRecipesTool;
import dev.tomewisp.trace.json.TraceParser;
import dev.tomewisp.trace.minecraft.TraceReplayService;
import dev.tomewisp.trace.minecraft.TraceRepository;
import dev.tomewisp.trace.replay.AgentTraceReplayer;
import java.util.ArrayList;
import java.util.List;

public final class TomeWispBootstrap {
    private static TomeWispRuntime runtime;

    private TomeWispBootstrap() {}

    public static synchronized TomeWispRuntime initialize() {
        if (runtime != null) {
            return runtime;
        }

        PlatformService platform = PlatformServices.load();
        ToolRegistry tools = new ToolRegistry();
        tools.register("tomewisp:builtins", builtinTools(platform));
        KnowledgeRegistry knowledge = new KnowledgeRegistry();
        PatchouliMultiblockStore patchouliMultiblocks = new PatchouliMultiblockStore();
        tools.register(
                "tomewisp:knowledge",
                List.of(
                        new ListKnowledgeSourcesTool(knowledge),
                        new SearchKnowledgeTool(knowledge),
                        new GetKnowledgeDocumentTool(knowledge),
                        new GetPatchouliMultiblockTool(patchouliMultiblocks)));
        SkillRepository skills = new SkillRepository(
                new SkillParser(),
                tools.descriptors().stream().map(descriptor -> descriptor.id()).toList());
        java.util.Set<String> installedSkillMods = new java.util.HashSet<>();
        if (platform.isModLoaded("ftbquests")) {
            installedSkillMods.add("ftbquests");
        }
        if (!skills.reload(new BundledSkillLoader().load(), installedSkillMods)) {
            TomeWispConstants.LOGGER.warn("Bundled Skill validation failed: {}", skills.diagnostics());
        }
        tools.register("tomewisp:skills", List.of(new LoadSkillTool(skills)));
        Gson gson = new Gson();
        TraceReplayService traceReplay = new TraceReplayService(
                new TraceRepository(new TraceParser()),
                new MinecraftContextCapture(gson),
                new AgentTraceReplayer(tools, gson));
        CapabilitySettingsCatalog capabilitySettings = capabilitySettings(tools, skills);
        runtime = new TomeWispRuntime(
                platform,
                tools,
                knowledge,
                patchouliMultiblocks,
                skills,
                new DevelopmentToolInspector(tools),
                traceReplay,
                capabilitySettings);
        TomeWispConstants.LOGGER.info(
                "Initialized TomeWisp on {} with {} tool(s)",
                platform.platformName(),
                tools.descriptors().size());
        return runtime;
    }

    static CapabilitySettingsCatalog capabilitySettings(
            ToolRegistry tools, SkillRepository skills) {
        CapabilitySettingsCatalog catalog = new CapabilitySettingsCatalog();
        List<CapabilitySettingsDescriptor> descriptors = new ArrayList<>();
        descriptors.add(descriptor("patchouli", CapabilityKind.KNOWLEDGE_SOURCE, "source", null));
        descriptors.add(descriptor("ftbquests", CapabilityKind.KNOWLEDGE_SOURCE, "source", null));
        descriptors.add(descriptor(
                "tomewisp:recipes",
                CapabilityKind.TOOL,
                "tool",
                new CapabilityChildPage("tomewisp:recipe_settings")));
        tools.registrations().stream()
                .filter(registration -> !registration.tool().descriptor().id()
                        .equals("tomewisp:load_skill"))
                .map(registration -> descriptor(
                        registration.tool().descriptor().id(), CapabilityKind.TOOL, "tool", null))
                .forEach(descriptors::add);
        skills.metadata().stream()
                .map(metadata -> descriptor(
                        metadata.name(), CapabilityKind.SKILL, "skill", null))
                .forEach(descriptors::add);
        catalog.register("tomewisp:core", descriptors);
        return catalog;
    }

    private static CapabilitySettingsDescriptor descriptor(
            String id,
            CapabilityKind kind,
            String keyKind,
            CapabilityChildPage childPage) {
        String keyId = id.replace(':', '_').replace('/', '_').replace('-', '_');
        String prefix = "settings.tomewisp.capability." + keyKind + "." + keyId;
        return new CapabilitySettingsDescriptor(
                id, kind, prefix + ".title", prefix + ".description", childPage);
    }

    static List<Tool<?, ?>> builtinTools(PlatformService platform) {
        return List.of(
                new InspectGameStateTool(),
                new ResolveResourceTool(),
                new SearchRecipesTool(),
                new GetRecipeTool(),
                new FindItemUsagesTool(),
                new InspectInventoryTool(),
                new CalculateCraftabilityTool(),
                new FindRecipesTool());
    }
}
