package dev.tomewisp;

import com.google.gson.Gson;
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
import dev.tomewisp.tool.builtin.PlatformInfoTool;
import dev.tomewisp.tool.builtin.PlayerContextTool;
import dev.tomewisp.tool.builtin.ResolveResourceTool;
import dev.tomewisp.tool.builtin.SearchKnowledgeTool;
import dev.tomewisp.tool.builtin.SearchRecipesTool;
import dev.tomewisp.trace.json.TraceParser;
import dev.tomewisp.trace.minecraft.TraceReplayService;
import dev.tomewisp.trace.minecraft.TraceRepository;
import dev.tomewisp.trace.replay.AgentTraceReplayer;
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
        runtime = new TomeWispRuntime(
                platform,
                tools,
                knowledge,
                patchouliMultiblocks,
                skills,
                new DevelopmentToolInspector(tools),
                traceReplay);
        TomeWispConstants.LOGGER.info(
                "Initialized TomeWisp on {} with {} tool(s)",
                platform.platformName(),
                tools.descriptors().size());
        return runtime;
    }

    static List<Tool<?, ?>> builtinTools(PlatformService platform) {
        return List.of(
                new PlatformInfoTool(platform),
                new ResolveResourceTool(),
                new SearchRecipesTool(),
                new GetRecipeTool(),
                new FindItemUsagesTool(),
                new InspectInventoryTool(),
                new CalculateCraftabilityTool(),
                new FindRecipesTool(),
                new PlayerContextTool());
    }
}
