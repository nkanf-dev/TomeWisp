package dev.openallay;

import com.google.gson.Gson;
import dev.openallay.capability.CapabilityChildPage;
import dev.openallay.capability.CapabilityKind;
import dev.openallay.capability.CapabilitySettingsCatalog;
import dev.openallay.capability.CapabilitySettingsDescriptor;
import dev.openallay.context.minecraft.MinecraftContextCapture;
import dev.openallay.devmode.DevelopmentToolInspector;
import dev.openallay.knowledge.KnowledgeRegistry;
import dev.openallay.knowledge.online.McModKnowledgeSource;
import dev.openallay.knowledge.online.MinecraftWikiKnowledgeSource;
import dev.openallay.knowledge.online.OnlineKnowledgeSearchService;
import dev.openallay.integration.patchouli.PatchouliMultiblockStore;
import dev.openallay.platform.PlatformService;
import dev.openallay.platform.PlatformServices;
import dev.openallay.net.HttpTransportPolicy;
import dev.openallay.net.JdkHttpTransport;
import dev.openallay.skill.BundledSkillLoader;
import dev.openallay.skill.LoadSkillTool;
import dev.openallay.skill.SkillParser;
import dev.openallay.skill.SkillRepository;
import dev.openallay.tool.ToolRegistry;
import dev.openallay.tool.Tool;
import dev.openallay.tool.builtin.CalculateCraftabilityTool;
import dev.openallay.tool.builtin.FindRecipesTool;
import dev.openallay.tool.builtin.FindItemUsagesTool;
import dev.openallay.tool.builtin.GetRecipeTool;
import dev.openallay.tool.builtin.GetKnowledgeDocumentTool;
import dev.openallay.tool.builtin.GetPatchouliMultiblockTool;
import dev.openallay.tool.builtin.ListKnowledgeSourcesTool;
import dev.openallay.tool.builtin.InspectInventoryTool;
import dev.openallay.tool.builtin.InspectGameStateTool;
import dev.openallay.tool.builtin.ResolveResourceTool;
import dev.openallay.tool.builtin.SearchKnowledgeTool;
import dev.openallay.tool.builtin.SearchRecipesTool;
import dev.openallay.trace.json.TraceParser;
import dev.openallay.trace.minecraft.TraceReplayService;
import dev.openallay.trace.minecraft.TraceRepository;
import dev.openallay.trace.replay.AgentTraceReplayer;
import java.util.ArrayList;
import java.util.List;

public final class OpenAllayBootstrap {
    private static OpenAllayRuntime runtime;

    private OpenAllayBootstrap() {}

    public static synchronized OpenAllayRuntime initialize() {
        if (runtime != null) {
            return runtime;
        }

        PlatformService platform = PlatformServices.load();
        ToolRegistry tools = new ToolRegistry();
        tools.register("openallay:builtins", builtinTools(platform));
        KnowledgeRegistry knowledge = new KnowledgeRegistry();
        Gson gson = new Gson();
        JdkHttpTransport knowledgeHttp = new JdkHttpTransport(
                new HttpTransportPolicy(java.time.Duration.ofSeconds(10), "openallay-knowledge"));
        OnlineKnowledgeSearchService onlineKnowledge = new OnlineKnowledgeSearchService(List.of(
                new MinecraftWikiKnowledgeSource(knowledgeHttp, gson),
                new McModKnowledgeSource(knowledgeHttp)));
        PatchouliMultiblockStore patchouliMultiblocks = new PatchouliMultiblockStore();
        tools.register(
                "openallay:knowledge",
                List.of(
                        new ListKnowledgeSourcesTool(knowledge),
                        new SearchKnowledgeTool(knowledge, onlineKnowledge),
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
            OpenAllayConstants.LOGGER.warn("Bundled Skill validation failed: {}", skills.diagnostics());
        }
        tools.register("openallay:skills", List.of(new LoadSkillTool(skills)));
        TraceReplayService traceReplay = new TraceReplayService(
                new TraceRepository(new TraceParser()),
                new MinecraftContextCapture(gson),
                new AgentTraceReplayer(tools, gson));
        CapabilitySettingsCatalog capabilitySettings = capabilitySettings(tools, skills);
        runtime = new OpenAllayRuntime(
                platform,
                tools,
                knowledge,
                patchouliMultiblocks,
                skills,
                new DevelopmentToolInspector(tools),
                traceReplay,
                capabilitySettings);
        OpenAllayConstants.LOGGER.info(
                "Initialized OpenAllay on {} with {} tool(s)",
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
                "openallay:recipes",
                CapabilityKind.TOOL,
                "tool",
                new CapabilityChildPage("openallay:recipe_settings")));
        tools.registrations().stream()
                .filter(registration -> !registration.tool().descriptor().id()
                        .equals("openallay:load_skill"))
                .map(registration -> descriptor(
                        registration.tool().descriptor().id(), CapabilityKind.TOOL, "tool", null))
                .forEach(descriptors::add);
        skills.metadata().stream()
                .map(metadata -> descriptor(
                        metadata.name(), CapabilityKind.SKILL, "skill", null))
                .forEach(descriptors::add);
        catalog.register("openallay:core", descriptors);
        return catalog;
    }

    private static CapabilitySettingsDescriptor descriptor(
            String id,
            CapabilityKind kind,
            String keyKind,
            CapabilityChildPage childPage) {
        String keyId = id.replace(':', '_').replace('/', '_').replace('-', '_');
        String prefix = "settings.openallay.capability." + keyKind + "." + keyId;
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
