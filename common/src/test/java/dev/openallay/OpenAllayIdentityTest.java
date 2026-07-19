package dev.openallay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class OpenAllayIdentityTest {
    private static final Path REPOSITORY = Path.of("..");

    @Test
    void currentRuntimeUsesOpenAllayIdentity() throws IOException {
        assertEquals("openallay", OpenAllayConstants.MOD_ID);
        assertEquals("OpenAllay", OpenAllayConstants.MOD_NAME);
        assertEquals("dev.openallay", OpenAllayConstants.class.getPackageName());

        JsonObject fabric = JsonParser.parseString(Files.readString(REPOSITORY.resolve(
                "fabric/src/main/resources/fabric.mod.json"))).getAsJsonObject();
        assertEquals("${mod_id}", fabric.get("id").getAsString());
        assertTrue(fabric.getAsJsonObject("breaks").has("tomewisp"));

        String neoforge = Files.readString(REPOSITORY.resolve(
                "neoforge/src/main/resources/META-INF/neoforge.mods.toml"));
        assertTrue(neoforge.contains("modId = \"${mod_id}\""));
        assertTrue(neoforge.contains("modId = \"tomewisp\"\n"));
        assertTrue(neoforge.contains("type = \"incompatible\""));

        Path resources = Path.of("src/main/resources");
        assertTrue(Files.isDirectory(resources.resolve("assets/openallay")));
        assertTrue(Files.isDirectory(resources.resolve("data/openallay")));
        assertTrue(Files.isDirectory(resources.resolve("assets/openallay/openallay_skills")));
        assertFalse(Files.exists(resources.resolve("assets/tomewisp")));
        assertFalse(Files.exists(resources.resolve("data/tomewisp")));
    }
}
