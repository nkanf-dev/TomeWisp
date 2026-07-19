package dev.openallay.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.openallay.OpenAllayRuntime;
import dev.openallay.devmode.DevelopmentToolInspector;
import dev.openallay.guide.ui.GuideDisplayConfig;
import dev.openallay.guide.ui.GuideDisplayRuntime;
import dev.openallay.knowledge.KnowledgeRegistry;
import dev.openallay.model.config.ModelProfileDefinition;
import dev.openallay.model.config.ModelProfilesConfig;
import dev.openallay.model.config.ModelProtocol;
import dev.openallay.model.config.SecretValue;
import dev.openallay.model.catalog.ModelCatalog;
import dev.openallay.model.catalog.ModelCatalogRequest;
import dev.openallay.platform.PlatformService;
import dev.openallay.skill.SkillParser;
import dev.openallay.skill.SkillRepository;
import dev.openallay.tool.ToolRegistry;
import dev.openallay.tool.ToolResult;
import dev.openallay.tool.config.ToolFamilyConfig;
import dev.openallay.tool.config.ToolFamilyId;
import dev.openallay.tool.config.ToolSourceDefinition;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
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
                new dev.openallay.recipe.config.RecipeClientRuntime(
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

    @Test
    void catalogUsesTypedKeyBeforeSavedCredentialAndReportsActualStoredPresence(
            @TempDir Path directory) throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = "{\"data\":[{\"id\":\"mimo-v2.5-pro\"}]}"
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        ClientSettingsRuntime settings = success(ClientSettingsRuntime.create(
                runtime(),
                directory.resolve("models.json"),
                directory.resolve("model.json"),
                directory.resolve("model-metadata.json"),
                Map.of(),
                Runnable::run,
                null,
                Clock.systemUTC(),
                GuideDisplayConfig.defaults()));
        try {
            URI baseUri = URI.create(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
            ModelProfileDefinition profile = new ModelProfileDefinition(
                    "main",
                    "Main",
                    true,
                    ModelProtocol.OPENAI_CHAT,
                    baseUri,
                    "typed-model",
                    "env:UNSET_PLACEHOLDER",
                    256_000,
                    4_096,
                    Duration.ofSeconds(5),
                    Duration.ofSeconds(5),
                    null);
            ModelProfilesConfig config = new ModelProfilesConfig(
                    ModelProfilesConfig.SCHEMA_VERSION, "main", List.of(profile));
            assertInstanceOf(ToolResult.Success.class, settings.settings()
                    .saveModels(config, "main", SecretValue.of("saved-catalog-key")).join());
            ModelProfileDefinition saved = settings.settings().snapshot()
                    .models().config().profiles().getFirst();
            assertTrue(settings.settings().snapshot().models().profiles().getFirst()
                    .credentialPresent());
            ModelCatalogRequest request = new ModelCatalogRequest(
                    saved.id(),
                    saved.protocol(),
                    saved.baseUri(),
                    saved.credentialRef(),
                    saved.connectTimeout(),
                    saved.requestTimeout());

            ToolResult<ModelCatalog> stored = settings.settings()
                    .fetchModelCatalog(request, null).join();
            assertEquals(List.of("mimo-v2.5-pro"), successValue(stored).modelIds());
            assertEquals("Bearer saved-catalog-key", authorization.get());

            ToolResult<ModelCatalog> typed = settings.settings()
                    .fetchModelCatalog(request, SecretValue.of("typed-catalog-key")).join();
            assertEquals(List.of("mimo-v2.5-pro"), successValue(typed).modelIds());
            assertEquals("Bearer typed-catalog-key", authorization.get());
            assertFalse(typed.toString().contains("typed-catalog-key"));

            ModelProfileDefinition missing = new ModelProfileDefinition(
                    saved.id(),
                    saved.displayName(),
                    saved.enabled(),
                    saved.protocol(),
                    saved.baseUri(),
                    saved.model(),
                    dev.openallay.model.config.CredentialReference.local(UUID.randomUUID()).encoded(),
                    saved.contextWindowTokens(),
                    saved.maxOutputTokens(),
                    saved.connectTimeout(),
                    saved.requestTimeout(),
                    saved.metadata());
            assertInstanceOf(ToolResult.Success.class, settings.settings()
                    .saveModels(new ModelProfilesConfig(
                            ModelProfilesConfig.SCHEMA_VERSION, "main", List.of(missing)))
                    .join());
            assertFalse(settings.settings().snapshot().models().profiles().getFirst()
                    .credentialPresent());
            authorization.set(null);
            ToolResult<ModelCatalog> absent = settings.settings().fetchModelCatalog(
                    new ModelCatalogRequest(
                            missing.id(), missing.protocol(), missing.baseUri(),
                            missing.credentialRef(), missing.connectTimeout(), missing.requestTimeout()),
                    null).join();
            assertEquals("model_catalog_credential_missing",
                    assertInstanceOf(ToolResult.Failure.class, absent).code());
            assertEquals(null, authorization.get());
        } finally {
            settings.closeAsync().join();
            server.stop(0);
        }
    }

    @Test
    void toolOwnedLocalDocumentsPersistAndReloadIntoKnowledge(@TempDir Path directory)
            throws Exception {
        OpenAllayRuntime product = runtime();
        ClientSettingsRuntime settings = success(ClientSettingsRuntime.create(
                product,
                directory.resolve("models.json"),
                directory.resolve("model.json"),
                directory.resolve("model-metadata.json"),
                Map.of(),
                Runnable::run,
                null,
                Clock.systemUTC(),
                GuideDisplayConfig.defaults()));
        var guides = settings.settings().snapshot().tools()
                .find(ToolFamilyId.GUIDES)
                .orElseThrow();
        com.google.gson.JsonObject config = new com.google.gson.JsonObject();
        config.addProperty("directory", "notes");
        config.addProperty("locale", "en_us");
        java.util.ArrayList<ToolSourceDefinition> sources = new java.util.ArrayList<>();
        guides.sources().forEach(source -> sources.add(new ToolSourceDefinition(
                source.id(),
                source.kind(),
                source.displayName(),
                source.enabled(),
                source.config(),
                source.lifecycle())));
        sources.add(new ToolSourceDefinition(
                "user:notes",
                "local_markdown",
                "Notes",
                true,
                config,
                ToolSourceDefinition.Lifecycle.USER));

        ToolResult<Boolean> savedTool = settings.settings()
                .saveToolSettings(new ToolFamilyConfig(
                        ToolFamilyConfig.SCHEMA_VERSION,
                        ToolFamilyId.GUIDES,
                        true,
                        sources))
                .join();
        assertTrue(savedTool instanceof ToolResult.Success<Boolean>, savedTool.toString());
        Path document = directory.resolve("knowledge/notes/iron.md");
        Files.writeString(document, "# Iron\nUse a furnace.\n");
        assertInstanceOf(ToolResult.Success.class, settings.settings()
                .reloadToolSettings(ToolFamilyId.GUIDES, true)
                .join());

        assertTrue(Files.exists(directory.resolve("tools/guides.json")));
        assertEquals("Iron", product.knowledge().snapshot().documents().getFirst().title());
        assertEquals(3, settings.settings().snapshot().tools()
                .find(ToolFamilyId.GUIDES).orElseThrow().sources().size());
        settings.closeAsync().join();
    }

    @SuppressWarnings("unchecked")
    private static ClientSettingsRuntime success(ToolResult<ClientSettingsRuntime> result) {
        return ((ToolResult.Success<ClientSettingsRuntime>)
                assertInstanceOf(ToolResult.Success.class, result)).value();
    }

    @SuppressWarnings("unchecked")
    private static <T> T successValue(ToolResult<T> result) {
        return ((ToolResult.Success<T>) assertInstanceOf(ToolResult.Success.class, result)).value();
    }

    private static OpenAllayRuntime runtime() {
        ToolRegistry tools = new ToolRegistry();
        return new OpenAllayRuntime(
                new FakePlatform(),
                tools,
                new KnowledgeRegistry(),
                new dev.openallay.integration.patchouli.PatchouliMultiblockStore(),
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

        @Override
        public String gameVersion() {
            return "test";
        }
    }
}
