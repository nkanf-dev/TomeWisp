package dev.tomewisp.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tomewisp.TomeWispRuntime;
import dev.tomewisp.devmode.DevelopmentToolInspector;
import dev.tomewisp.guide.ui.GuideDisplayConfig;
import dev.tomewisp.guide.ui.GuideDisplayRuntime;
import dev.tomewisp.knowledge.KnowledgeRegistry;
import dev.tomewisp.model.config.ModelProfileDefinition;
import dev.tomewisp.model.config.ModelProfilesConfig;
import dev.tomewisp.model.config.ModelProtocol;
import dev.tomewisp.model.config.SecretValue;
import dev.tomewisp.platform.PlatformService;
import dev.tomewisp.skill.SkillParser;
import dev.tomewisp.skill.SkillRepository;
import dev.tomewisp.tool.ToolRegistry;
import dev.tomewisp.tool.ToolResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ClientSettingsRuntimeTest {
    @Test
    void missingFilesUseDisabledMemoryDefaultWithoutMaterializingConfiguration(
            @TempDir Path directory) {
        Path profiles = directory.resolve("models.json");
        Path legacy = directory.resolve("model.json");
        Path metadata = directory.resolve("model-metadata.json");

        ToolResult<ClientSettingsRuntime> created = ClientSettingsRuntime.create(
                runtime(),
                profiles,
                legacy,
                metadata,
                Map.of(),
                Runnable::run,
                null,
                Clock.systemUTC(),
                GuideDisplayConfig.defaults());

        if (!(created instanceof ToolResult.Success<ClientSettingsRuntime> success)) {
            throw new AssertionError("expected native settings runtime creation to succeed");
        }
        ClientSettingsRuntime settings = success.value();
        assertEquals("default", settings.settings().snapshot()
                .models().config().defaultProfileId());
        assertFalse(settings.settings().snapshot().models().profiles().getFirst().available());
        assertEquals("model_not_configured", settings.settings().snapshot().notice().code());
        assertFalse(Files.exists(profiles));
        assertFalse(Files.exists(legacy));
        settings.closeAsync().join();
    }

    @Test
    void sharedDisplayRuntimePersistsDebugModeForSettingsAndGuide(@TempDir Path directory)
            throws Exception {
        Path displayPath = directory.resolve("display.json");
        GuideDisplayRuntime display = new GuideDisplayRuntime(displayPath);
        ClientSettingsHistoryBinding history = new ClientSettingsHistoryBinding();
        ToolResult<ClientSettingsRuntime> created = ClientSettingsRuntime.create(
                runtime(),
                directory.resolve("models.json"),
                directory.resolve("model.json"),
                directory.resolve("model-metadata.json"),
                directory.resolve("capabilities.json"),
                directory.resolve("recipes.json"),
                new dev.tomewisp.recipe.config.RecipeClientRuntime(
                        directory.resolve("recipes.json")),
                Map.of(),
                Runnable::run,
                null,
                Clock.systemUTC(),
                display,
                history);
        if (!(created instanceof ToolResult.Success<ClientSettingsRuntime> success)) {
            throw new AssertionError("expected native settings runtime creation to succeed");
        }
        ClientSettingsRuntime settings = success.value();

        assertInstanceOf(ToolResult.Success.class, settings.settings()
                .saveDisplay(new GuideDisplayConfig(
                        GuideDisplayConfig.SCHEMA_VERSION, true, true)).join());

        assertTrue(display.config().debugMode());
        assertTrue(settings.settings().snapshot().display().debugMode());
        assertTrue(Files.exists(displayPath));
        assertTrue(new GuideDisplayRuntime(displayPath).config().debugMode());
        settings.closeAsync().join();
    }

    @Test
    void playerCredentialSaveRotatesLocalSecretAndSurvivesRestart(@TempDir Path directory)
            throws Exception {
        Path profiles = directory.resolve("models.json");
        Path credentials = directory.resolve("credentials.sqlite3");
        ClientSettingsRuntime first = success(ClientSettingsRuntime.create(
                runtime(),
                profiles,
                directory.resolve("model.json"),
                directory.resolve("model-metadata.json"),
                Map.of(),
                Runnable::run,
                null,
                Clock.systemUTC(),
                GuideDisplayConfig.defaults()));
        ModelProfileDefinition definition = new ModelProfileDefinition(
                "main",
                "Main",
                true,
                ModelProtocol.OPENAI_CHAT,
                java.net.URI.create("https://provider.example/v1"),
                "vendor/model",
                "env:UNSET_PLACEHOLDER",
                256_000,
                4_096,
                Duration.ofSeconds(30),
                Duration.ofSeconds(300),
                null);
        ModelProfilesConfig config = new ModelProfilesConfig(
                ModelProfilesConfig.SCHEMA_VERSION, "main", List.of(definition));

        assertInstanceOf(ToolResult.Success.class, first.settings()
                .saveModels(config, "main", SecretValue.of("first-secret-value")).join());
        assertInstanceOf(ToolResult.Success.class, first.settings()
                .saveModels(
                        first.settings().snapshot().models().config(),
                        "main",
                        SecretValue.of("second-secret-value"))
                .join());

        String encoded = Files.readString(profiles);
        assertTrue(encoded.contains("\"schemaVersion\":2"));
        assertTrue(encoded.contains("\"credentialRef\":\"local:"));
        assertFalse(encoded.contains("first-secret-value"));
        assertFalse(encoded.contains("second-secret-value"));
        assertFalse(first.settings().snapshot().toString().contains("second-secret-value"));
        try (var connection = java.sql.DriverManager.getConnection(
                        "jdbc:sqlite:" + credentials);
                var result = connection.createStatement().executeQuery(
                        "select count(*) from credentials")) {
            assertTrue(result.next());
            assertEquals(1, result.getInt(1));
        }
        first.closeAsync().join();

        ClientSettingsRuntime restarted = success(ClientSettingsRuntime.create(
                runtime(),
                profiles,
                directory.resolve("model.json"),
                directory.resolve("model-metadata.json"),
                Map.of(),
                Runnable::run,
                null,
                Clock.systemUTC(),
                GuideDisplayConfig.defaults()));
        assertTrue(restarted.settings().snapshot().models().profiles().getFirst().available());
        assertTrue(restarted.settings().snapshot().models().profiles().getFirst()
                .credentialPresent());
        restarted.closeAsync().join();
    }

    @SuppressWarnings("unchecked")
    private static ClientSettingsRuntime success(ToolResult<ClientSettingsRuntime> result) {
        return ((ToolResult.Success<ClientSettingsRuntime>)
                assertInstanceOf(ToolResult.Success.class, result)).value();
    }

    private static TomeWispRuntime runtime() {
        ToolRegistry tools = new ToolRegistry();
        return new TomeWispRuntime(
                new FakePlatform(),
                tools,
                new KnowledgeRegistry(),
                new dev.tomewisp.integration.patchouli.PatchouliMultiblockStore(),
                new SkillRepository(new SkillParser(), List.of()),
                new DevelopmentToolInspector(tools),
                null);
    }

    private static final class FakePlatform implements PlatformService {
        @Override
        public String platformName() {
            return "test";
        }

        @Override
        public boolean isModLoaded(String modId) {
            return false;
        }

        @Override
        public boolean isDevelopmentEnvironment() {
            return true;
        }
    }
}
