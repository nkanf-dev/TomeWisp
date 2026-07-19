package dev.openallay.guide.e2e;

import com.google.gson.Gson;
import dev.openallay.guide.GuideRequestSnapshot;
import dev.openallay.guide.GuideRequestStatus;
import dev.openallay.guide.GuideService;
import dev.openallay.guide.GuideServiceManager;
import dev.openallay.guide.GuideSnapshot;
import dev.openallay.guide.GuideSubscription;
import dev.openallay.guide.GuideSessionSnapshot;
import dev.openallay.guide.GuideTimelineEntry;
import dev.openallay.guide.semantic.RichComponent;
import dev.openallay.guide.semantic.SemanticBlock;
import dev.openallay.guide.ui.SemanticLayoutCache;
import dev.openallay.client.gui.OpenAllayScreen;
import dev.openallay.client.gui.OpenAllaySettingsScreen;
import dev.openallay.settings.ClientSettingsService;
import dev.openallay.tool.ToolResult;
import dev.openallay.tool.config.ToolFamilyId;
import dev.openallay.recipe.RecipeProviderReadiness;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Development-only real-client probe. Loader tick adapters call {@link #tick(UUID)};
 * the in-game Agent never receives its report-writing or shutdown capabilities.
 */
public final class GuideClientE2EController {
    private final GuideClientE2EConfig config;
    private final String loader;
    private final String gameVersion;
    private final String modVersion;
    private final GuideServiceManager services;
    private final Gson gson;
    private final Runnable shutdown;
    private final Set<String> secrets;
    private final Supplier<RecipeProviderReadiness> recipeReadiness;
    private final ClientSettingsService clientSettings;
    private final List<GuideRequestStatus> transitions = new ArrayList<>();
    private Instant startedAt;
    private UUID requestId;
    private GuideSubscription subscription;
    private RecipeProviderReadiness lastRecipeReadiness;
    private int remainingHistorySeeds;
    private boolean seedingHistory;
    private boolean started;
    private boolean finished;
    private int screenshotStage = -1;
    private int screenshotTicks;
    private int originalWindowWidth;
    private int originalWindowHeight;
    private int activeScreenshotTicks;
    private int activeProgressVisibleTicks;
    private boolean activeScreenshotCaptured;

    public GuideClientE2EController(
            GuideClientE2EConfig config,
            String loader,
            String gameVersion,
            String modVersion,
            GuideServiceManager services,
            Gson gson,
            Runnable shutdown,
            Set<String> secrets) {
        this(config, loader, gameVersion, modVersion, services, gson, shutdown, secrets,
                RecipeProviderReadiness::ready);
    }

    public GuideClientE2EController(
            GuideClientE2EConfig config,
            String loader,
            String gameVersion,
            String modVersion,
            GuideServiceManager services,
            Gson gson,
            Runnable shutdown,
            Set<String> secrets,
            Supplier<RecipeProviderReadiness> recipeReadiness) {
        this(config, loader, gameVersion, modVersion, services, gson, shutdown, secrets,
                recipeReadiness, null);
    }

    public GuideClientE2EController(
            GuideClientE2EConfig config,
            String loader,
            String gameVersion,
            String modVersion,
            GuideServiceManager services,
            Gson gson,
            Runnable shutdown,
            Set<String> secrets,
            Supplier<RecipeProviderReadiness> recipeReadiness,
            ClientSettingsService clientSettings) {
        this.config = java.util.Objects.requireNonNull(config, "config");
        this.loader = require(loader, "loader");
        this.gameVersion = require(gameVersion, "gameVersion");
        this.modVersion = require(modVersion, "modVersion");
        this.services = java.util.Objects.requireNonNull(services, "services");
        this.gson = java.util.Objects.requireNonNull(gson, "gson");
        this.shutdown = java.util.Objects.requireNonNull(shutdown, "shutdown");
        this.secrets = Set.copyOf(secrets);
        this.recipeReadiness = java.util.Objects.requireNonNull(recipeReadiness, "recipeReadiness");
        this.clientSettings = clientSettings;
    }

    /** Starts exactly once after a real client player exists. */
    public void tick(UUID actor) {
        if (finished) {
            tickScreenshotProbe();
            return;
        }
        if (started) {
            tickActiveScreenshotProbe();
            return;
        }
        if (actor == null) return;
        if (!System.getProperty("openallay.e2e.screenshotRoot", "").isBlank()) {
            var client = net.minecraft.client.Minecraft.getInstance();
            if (client.gui.overlay() != null || client.gui.screen() != null) return;
        }
        GuideService service = services.forActor(actor);
        if (service.snapshot().persistence().state()
                == dev.openallay.guide.GuidePersistenceSnapshot.State.LOADING) {
            return;
        }
        RecipeProviderReadiness readiness = recipeReadiness.get();
        if (!readiness.equals(lastRecipeReadiness)) {
            lastRecipeReadiness = readiness;
            System.out.println("OpenAllay E2E recipe readiness: "
                    + readiness.state() + " " + readiness.code() + " " + readiness.message());
        }
        if (readiness.state() == RecipeProviderReadiness.State.WAITING) {
            return;
        }
        if (readiness.state() == RecipeProviderReadiness.State.FAILED) {
            failWithoutRequest(readiness.code(), readiness.message());
            return;
        }
        started = true;
        startedAt = Instant.now();
        subscription = service.subscribe(this::observe);
        service.selectSession(config.sessionId()).thenAccept(selected -> {
            if (selected instanceof ToolResult.Failure<String> failure) {
                failWithoutRequest(failure.code(), failure.message());
            } else {
                selectMode(service);
            }
        });
    }

    public boolean finished() {
        return finished;
    }

    private void selectMode(GuideService service) {
        service.setModelMode(config.modelMode()).thenAccept(mode -> {
            if (mode instanceof ToolResult.Failure<?> failure) {
                failWithoutRequest(failure.code(), failure.message());
            } else {
                remainingHistorySeeds = config.historySeedRequests();
                if (remainingHistorySeeds > 0) {
                    seedingHistory = true;
                    seedHistory(service);
                } else {
                    ask(service);
                }
            }
        });
    }

    private void seedHistory(GuideService service) {
        service.ask("OpenAllay E2E 历史分页种子 " + remainingHistorySeeds).thenAccept(asked -> {
            if (asked instanceof ToolResult.Failure<UUID> failure) {
                failWithoutRequest(failure.code(), failure.message());
                return;
            }
            requestId = ((ToolResult.Success<UUID>) asked).value();
            observe(service.snapshot());
        });
    }

    private void ask(GuideService service) {
        openScreenForScreenshotProbe(service);
        service.ask(config.question()).thenAccept(asked -> {
            if (asked instanceof ToolResult.Failure<UUID> failure) {
                failWithoutRequest(failure.code(), failure.message());
                return;
            }
            requestId = ((ToolResult.Success<UUID>) asked).value();
            observe(service.snapshot());
        });
    }

    private void observe(GuideSnapshot snapshot) {
        if (finished || requestId == null) return;
        GuideRequestSnapshot request = snapshot.sessions().stream()
                .flatMap(value -> value.requests().stream())
                .filter(value -> value.requestId().equals(requestId))
                .findFirst().orElse(null);
        if (request == null) return;
        if (transitions.isEmpty() || transitions.getLast() != request.status()) {
            transitions.add(request.status());
        }
        if (!request.terminal()) return;
        if (seedingHistory) {
            requestId = null;
            remainingHistorySeeds--;
            if (remainingHistorySeeds > 0) {
                seedHistory(services.forActor(snapshot.actorId()));
            } else {
                seedingHistory = false;
                transitions.clear();
                startedAt = Instant.now();
                ask(services.forActor(snapshot.actorId()));
            }
            return;
        }
        LinkedHashMap<String, Long> timings = new LinkedHashMap<>();
        timings.put("total", Duration.between(startedAt, request.terminalAt()).toMillis());
        LinkedHashMap<String, String> hashes = new LinkedHashMap<>();
        hashes.put("assistantTextSha256", sha256(request.assistantText()));
        hashes.put("userMessageSha256", sha256(request.userMessage()));
        GuideSessionSnapshot session = snapshot.sessions().stream()
                .filter(value -> value.sessionId().equals(request.sessionId()))
                .findFirst().orElseThrow();
        SemanticSummary semantic = summarize(request);
        SemanticLayoutCache.Stats cache = SemanticLayoutCache.globalStats();
        LinkedHashMap<String, Long> historyMetrics = new LinkedHashMap<>();
        historyMetrics.put("loadedRequests", (long) session.requests().size());
        historyMetrics.put("totalRequests", session.historyWindow().totalRequests());
        historyMetrics.put("hasEarlier", session.historyWindow().hasEarlier() ? 1L : 0L);
        historyMetrics.put("hasLater", session.historyWindow().hasLater() ? 1L : 0L);
        historyMetrics.put("cacheHits", cache.hits());
        historyMetrics.put("cacheMisses", cache.misses());
        GuideE2EReport report = new GuideE2EReport(
                loader,
                gameVersion,
                modVersion,
                config.scenario(),
                request.topology(),
                request.requestId(),
                request.sessionId(),
                transitions,
                request.tools().stream().map(value -> value.toolId()).toList(),
                request.tools().stream().map(GuideClientE2EController::toolProbe).toList(),
                request.sources().stream().map(value -> value.evidence()).toList(),
                request.timeline().stream().map(value -> switch (value) {
                    case GuideTimelineEntry.Assistant ignored -> "assistant";
                    case GuideTimelineEntry.Tool ignored -> "tool";
                }).toList(),
                semantic.metrics(),
                semantic.diagnosticCodes(),
                semantic.componentTypes(),
                session.historyWindow().state().name(),
                historyMetrics,
                request.status(),
                request.failure() == null ? null : request.failure().code(),
                request.failure() == null ? null : request.failure().message(),
                timings,
                hashes);
        if (!config.shutdownAfterReport()) {
            net.minecraft.client.Minecraft.getInstance().execute(() -> {
                var client = net.minecraft.client.Minecraft.getInstance();
                originalWindowWidth = client.getWindow().getWidth();
                originalWindowHeight = client.getWindow().getHeight();
                client.gui.setScreen(new OpenAllayScreen(services.forActor(snapshot.actorId())));
                if (!System.getProperty("openallay.e2e.screenshotRoot", "").isBlank()) {
                    screenshotStage = 0;
                    screenshotTicks = 0;
                }
            });
        }
        finish(new GuideE2EReportJson(gson).encode(report, secrets));
    }

    private void tickScreenshotProbe() {
        if (screenshotStage < 0 || ++screenshotTicks < 8) return;
        screenshotTicks = 0;
        var client = net.minecraft.client.Minecraft.getInstance();
        if (screenshotStage <= 7
                && !(client.gui.screen() instanceof OpenAllayScreen)) return;
        OpenAllayScreen screen = client.gui.screen() instanceof OpenAllayScreen value
                ? value : null;
        switch (screenshotStage++) {
            case 0 -> screen.positionForDevelopmentProbe(0.0D);
            case 1 -> {
                screenshot(client, "01-wide-top.png");
                screen.positionForDevelopmentProbe(0.5D);
            }
            case 2 -> {
                screenshot(client, "02-wide-middle.png");
                screen.positionForDevelopmentProbe(1.0D);
            }
            case 3 -> {
                screenshot(client, "03-wide-final.png");
                int tools = screen.toolCountForDevelopmentProbe();
                if (tools > 0) {
                    // Durable E2E history may contain older interrupted requests. Select a
                    // terminal card near the end of the current chronology so the retained
                    // screenshot demonstrates a populated result rather than stale progress.
                    screen.selectToolForDevelopmentProbe(Math.max(0, tools - 2));
                }
            }
            case 4 -> {
                screenshot(client, "04-wide-tool-detail.png");
                screen.openModelSelectorForDevelopmentProbe();
            }
            case 5 -> {
                screenshot(client, "05-wide-model-selector.png");
                screen.closeModelSelectorForDevelopmentProbe();
                client.getWindow().setWindowed(640, 480);
            }
            case 6 -> screenshot(client, "06-narrow-tool-detail.png");
            case 7 -> {
                client.getWindow().setWindowed(originalWindowWidth, originalWindowHeight);
                if (clientSettings == null) {
                    finishScreenshotProbe();
                    break;
                }
                OpenAllaySettingsScreen settings = new OpenAllaySettingsScreen(
                        clientSettings, () -> {});
                client.gui.setScreen(settings);
                settings.e2eOpenTools(ToolFamilyId.RESOURCE_RESOLUTION);
            }
            case 8 -> {
                screenshot(client, "07-wide-tool-settings.png");
                if (client.gui.screen() instanceof OpenAllaySettingsScreen settings) {
                    settings.e2eScrollToolDetails(320);
                }
            }
            case 9 -> {
                screenshot(client, "08-wide-tool-settings-lower.png");
                if (client.gui.screen() instanceof OpenAllaySettingsScreen settings) {
                    settings.e2eOpenGeneral("小羽");
                }
            }
            case 10 -> {
                screenshot(client, "09-wide-general-settings.png");
                if (client.gui.screen() instanceof OpenAllaySettingsScreen settings) {
                    settings.e2eOpenAbout();
                }
            }
            case 11 -> {
                screenshot(client, "10-wide-about.png");
                finishScreenshotProbe();
            }
            default -> screenshotStage = -1;
        }
    }

    private void finishScreenshotProbe() {
        screenshotStage = -1;
        if (Boolean.getBoolean("openallay.e2e.shutdownAfterScreenshots")) {
            shutdown.run();
        }
    }

    private void tickActiveScreenshotProbe() {
        if (activeScreenshotCaptured
                || requestId == null
                || System.getProperty("openallay.e2e.screenshotRoot", "").isBlank()
                || ++activeScreenshotTicks < 4) {
            return;
        }
        var client = net.minecraft.client.Minecraft.getInstance();
        if (client.gui.overlay() == null
                && client.gui.screen() instanceof OpenAllayScreen screen
                && screen.hasRenderedActiveProgressForDevelopmentProbe()) {
            // A tick projection becomes visible in the framebuffer only after a later render.
            // Retained evidence must show the strip, not the frame immediately before it.
            if (++activeProgressVisibleTicks >= 3) {
                activeScreenshotCaptured = true;
                screenshot(client, "00-active-progress.png");
            }
        } else {
            activeProgressVisibleTicks = 0;
        }
    }

    private void openScreenForScreenshotProbe(GuideService service) {
        if (config.shutdownAfterReport()
                || System.getProperty("openallay.e2e.screenshotRoot", "").isBlank()) {
            return;
        }
        net.minecraft.client.Minecraft.getInstance().execute(() -> {
            var client = net.minecraft.client.Minecraft.getInstance();
            originalWindowWidth = client.getWindow().getWidth();
            originalWindowHeight = client.getWindow().getHeight();
            client.gui.setScreen(new OpenAllayScreen(service));
        });
    }

    private static void screenshot(net.minecraft.client.Minecraft client, String name) {
        java.io.File root = new java.io.File(
                System.getProperty("openallay.e2e.screenshotRoot"));
        root.mkdirs();
        net.minecraft.client.Screenshot.grab(
                root,
                name,
                client.gameRenderer.mainRenderTarget(),
                1,
                component -> System.out.println("OpenAllay E2E screenshot: "
                        + component.getString()));
    }

    private static SemanticSummary summarize(GuideRequestSnapshot request) {
        long assistants = 0;
        long blocks = 0;
        long components = 0;
        long fallbacks = 0;
        TreeSet<String> diagnostics = new TreeSet<>();
        TreeSet<String> componentTypes = new TreeSet<>();
        for (GuideTimelineEntry entry : request.timeline()) {
            if (!(entry instanceof GuideTimelineEntry.Assistant assistant)) continue;
            assistants++;
            fallbacks += assistant.semantic().diagnostics().size();
            assistant.semantic().diagnostics().forEach(value -> diagnostics.add(value.code()));
            Counter counter = new Counter();
            for (SemanticBlock block : assistant.semantic().blocks()) {
                collect(block, counter, componentTypes);
            }
            blocks += counter.blocks;
            components += counter.components;
        }
        LinkedHashMap<String, Long> metrics = new LinkedHashMap<>();
        metrics.put("assistantSegments", assistants);
        metrics.put("toolInvocations", (long) request.tools().size());
        metrics.put("semanticBlocks", blocks);
        metrics.put("controlledComponents", components);
        metrics.put("semanticFallbacks", fallbacks);
        return new SemanticSummary(
                metrics, List.copyOf(diagnostics), List.copyOf(componentTypes));
    }

    private static GuideE2EReport.ToolProbe toolProbe(dev.openallay.guide.GuideToolActivity activity) {
        var normalized = activity.normalized();
        String section = null;
        String failureCode = null;
        if (normalized != null) {
            if (normalized.has("value") && normalized.get("value").isJsonObject()) {
                var value = normalized.getAsJsonObject("value");
                if (value.has("section") && value.get("section").isJsonPrimitive()) {
                    section = value.get("section").getAsString();
                }
            }
            if (normalized.has("code") && normalized.get("code").isJsonPrimitive()) {
                failureCode = normalized.get("code").getAsString();
            }
        }
        return new GuideE2EReport.ToolProbe(
                activity.toolId(), activity.status(), section, failureCode);
    }

    private static void collect(
            SemanticBlock block, Counter counter, Set<String> componentTypes) {
        counter.blocks++;
        switch (block) {
            case SemanticBlock.ListBlock value -> value.items().forEach(
                    item -> item.forEach(child -> collect(child, counter, componentTypes)));
            case SemanticBlock.Quote value -> value.content().forEach(
                    child -> collect(child, counter, componentTypes));
            case SemanticBlock.Component value -> {
                counter.components++;
                componentTypes.add(componentType(value.component()));
            }
            default -> { }
        }
    }

    private static String componentType(RichComponent component) {
        return switch (component) {
            case RichComponent.ItemRow ignored -> "item_row";
            case RichComponent.RecipeGrid ignored -> "recipe_grid";
            case RichComponent.IngredientCheck ignored -> "ingredient_check";
            case RichComponent.CraftabilitySummary ignored -> "craftability_summary";
            case RichComponent.ProgressSteps ignored -> "progress_steps";
            case RichComponent.SourceSummary ignored -> "source_summary";
            case RichComponent.StatusBadge ignored -> "status_badge";
            case RichComponent.ChoiceGroup ignored -> "choice_group";
        };
    }

    private static final class Counter {
        private long blocks;
        private long components;
    }

    private record SemanticSummary(
            Map<String, Long> metrics,
            List<String> diagnosticCodes,
            List<String> componentTypes) {}

    private void failWithoutRequest(String code, String message) {
        String encoded = gson.toJson(java.util.Map.of(
                "loader", loader,
                "gameVersion", gameVersion,
                "modVersion", modVersion,
                "scenario", config.scenario(),
                "outcome", "HARNESS_FAILED",
                "failureCode", code,
                "failureMessage", message));
        for (String secret : secrets) {
            if (secret != null && !secret.isBlank()) encoded = encoded.replace(secret, "[REDACTED]");
        }
        finish(encoded);
    }

    private void finish(String report) {
        if (finished) return;
        finished = true;
        if (subscription != null) subscription.close();
        try {
            if (config.reportPath().getParent() != null) {
                Files.createDirectories(config.reportPath().getParent());
            }
            Files.writeString(config.reportPath(), report + System.lineSeparator(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            System.err.println("Unable to write OpenAllay E2E report: " + exception.getMessage());
        }
        if (config.shutdownAfterReport()) shutdown.run();
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String require(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value;
    }
}
