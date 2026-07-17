package dev.tomewisp.tool.builtin;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import dev.tomewisp.context.EvidenceBearing;
import dev.tomewisp.context.EvidenceMetadata;
import dev.tomewisp.integration.patchouli.PatchouliMultiblock;
import dev.tomewisp.integration.patchouli.PatchouliMultiblockStore;
import dev.tomewisp.knowledge.KnowledgeDocument;
import dev.tomewisp.knowledge.KnowledgeKind;
import dev.tomewisp.knowledge.KnowledgeLoad;
import dev.tomewisp.knowledge.KnowledgeRegistry;
import dev.tomewisp.knowledge.KnowledgeSourceProvider;
import dev.tomewisp.platform.PlatformService;
import dev.tomewisp.testing.GroundedTestFixtures;
import dev.tomewisp.tool.ToolResult;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class BuiltinEvidenceContractTest {
    @Test
    void everyFactualBuiltinSuccessCarriesEvidence() {
        var context = GroundedTestFixtures.fullContext();
        KnowledgeDocument document = new KnowledgeDocument(
                "guide",
                "iron",
                KnowledgeKind.GUIDE_ENTRY,
                "Iron guide",
                "Craft an iron block",
                "minecraft",
                Set.of("minecraft:iron_ingot"),
                Set.of("minecraft:iron_block"),
                null,
                true,
                "fixture");
        KnowledgeRegistry knowledge = new KnowledgeRegistry();
        knowledge.reload(List.of(new KnowledgeSourceProvider() {
            @Override public String sourceId() { return "guide"; }
            @Override public KnowledgeLoad load() { return KnowledgeLoad.of(List.of(document)); }
        }));
        PatchouliMultiblockStore multiblocks = new PatchouliMultiblockStore();
        multiblocks.replace(Map.of("guide:iron", new PatchouliMultiblock(
                "guide:iron",
                List.of(new PatchouliMultiblock.Block(0, 0, 0, "minecraft:iron_block")),
                "fixture",
                document.evidence())));

        List<ToolResult<?>> results = List.of(
                new PlatformInfoTool(testPlatform()).invoke(context, new PlatformInfoTool.Input()),
                new ResolveResourceTool().invoke(
                        context, new ResolveResourceTool.Input("minecraft:stone", null)),
                new FindRecipesTool().invoke(
                        context, new FindRecipesTool.Input("minecraft:iron_block")),
                new SearchRecipesTool().invoke(
                        context, new SearchRecipesTool.Input(null, "minecraft:iron_block", null, null)),
                new GetRecipeTool().invoke(
                        context, new GetRecipeTool.Input("minecraft:recipe_manager", "minecraft:iron_block")),
                new FindItemUsagesTool().invoke(
                        context, new FindItemUsagesTool.Input("minecraft:iron_ingot")),
                new PlayerContextTool().invoke(context, new PlayerContextTool.Input()),
                new InspectInventoryTool().invoke(context, new InspectInventoryTool.Input()),
                new CalculateCraftabilityTool().invoke(
                        context,
                        new CalculateCraftabilityTool.Input(
                                "minecraft:recipe_manager", "minecraft:iron_block", 1)),
                new ListKnowledgeSourcesTool(knowledge).invoke(
                        context, new ListKnowledgeSourcesTool.Input()),
                new SearchKnowledgeTool(knowledge).invoke(
                        context, new SearchKnowledgeTool.Input("iron", null)),
                new GetKnowledgeDocumentTool(knowledge).invoke(
                        context, new GetKnowledgeDocumentTool.Input("guide", "iron")),
                new GetPatchouliMultiblockTool(multiblocks).invoke(
                        context, new GetPatchouliMultiblockTool.Input("guide:iron")));

        results.forEach(BuiltinEvidenceContractTest::assertGrounded);
    }

    private static void assertGrounded(ToolResult<?> result) {
        ToolResult.Success<?> success = assertInstanceOf(ToolResult.Success.class, result);
        EvidenceBearing output = assertInstanceOf(EvidenceBearing.class, success.value());
        assertFalse(output.evidence().isEmpty());
        for (EvidenceMetadata evidence : output.evidence()) {
            assertFalse(evidence.gameVersion().isBlank());
            assertFalse(evidence.loader().isBlank());
        }
    }

    private static PlatformService testPlatform() {
        return new PlatformService() {
            @Override public String platformName() { return "common-test"; }
            @Override public String gameVersion() { return "test"; }
            @Override public boolean isModLoaded(String modId) { return false; }
            @Override public boolean isDevelopmentEnvironment() { return true; }
        };
    }
}
