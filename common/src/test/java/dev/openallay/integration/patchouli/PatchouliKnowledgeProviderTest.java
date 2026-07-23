package dev.openallay.integration.patchouli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.openallay.client.resource.ClientResource;
import dev.openallay.client.resource.ClientResourceAccess;
import dev.openallay.knowledge.KnowledgeDocument;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class PatchouliKnowledgeProviderTest {
    @Test
    void selectsLocaleNormalizesPagesAndExtractsDenseMultiblock() {
        List<ClientResource> fixture = new ArrayList<>();
        fixture.add(resource("en_us", "English", "[]"));
        fixture.add(resource("zh_cn", "中文标题", """
                [
                  {"type":"patchouli:text","text":"$(item)机械动力$()$(br)第二行"},
                  {"type":"patchouli:crafting","recipe":"example:machine","item":"example:gear"},
                  {"type":"patchouli:multiblock","multiblock":{
                    "pattern":[["AB"]],
                    "mapping":{"A":"minecraft:stone","B":{"block":"example:casing"}}
                  }}
                ]
                """));
        ClientResourceAccess access = prefix -> fixture;

        PatchouliParseResult result = new PatchouliBookParser().parse(access, "zh_cn");
        KnowledgeDocument document = result.documents().getFirst();
        assertEquals("中文标题", document.title());
        assertTrue(document.body().contains("机械动力\n第二行"));
        assertTrue(document.itemIds().contains("example:gear"));
        assertTrue(document.recipeIds().contains("example:machine"));
        assertEquals(2, result.multiblocks().get(document.structureRef()).blocks().size());
    }

    @Test
    void supportsSparseBlocksAndExcludesUnknownVisibility() {
        ClientResource sparse = new ClientResource(
                "example:patchouli_books/machines/zh_cn/entries/sparse.json",
                "fixture", 0, true, """
                {"name":"Sparse","pages":[{"type":"patchouli:multiblock","multiblock":{
                  "blocks":[{"x":-1,"y":2,"z":3,"state":"example:block[facing=north]"}]
                }}]}
                """);
        ClientResource gated = new ClientResource(
                "example:patchouli_books/machines/zh_cn/entries/gated.json",
                "fixture", 0, true,
                "{\"name\":\"Secret\",\"advancement\":\"example:hidden\",\"pages\":[]}");
        PatchouliParseResult result = new PatchouliBookParser()
                .parse(prefix -> List.of(sparse, gated), "zh_cn");

        assertEquals(1, result.documents().size());
        PatchouliMultiblock.Block block = result.multiblocks().values().iterator().next().blocks().getFirst();
        assertEquals(-1, block.x());
        assertFalse(result.diagnostics().isEmpty());
        assertEquals("visibility_unresolved", result.diagnostics().getFirst().code());
    }

    @Test
    void publishesParsedCoordinatesIntoTheMultiblockStore() {
        PatchouliMultiblockStore store = new PatchouliMultiblockStore();
        ClientResource resource = resource("zh_cn", "Structure", """
                [{"type":"patchouli:multiblock","multiblock":{
                  "blocks":[{"x":1,"y":2,"z":3,"state":"example:casing"}]
                }}]
                """);
        PatchouliKnowledgeProvider provider = new PatchouliKnowledgeProvider(
                prefix -> List.of(resource), "zh_cn", store);
        String structureRef = provider.load().documents().getFirst().structureRef();

        assertEquals(
                "example:casing",
                store.find(structureRef).orElseThrow().blocks().getFirst().state());
    }

    private static ClientResource resource(String locale, String name, String pages) {
        return new ClientResource(
                "example:patchouli_books/machines/" + locale + "/entries/automation/press.json",
                "fixture", 0, true,
                "{\"name\":\"" + name + "\",\"pages\":" + pages + "}");
    }
}
