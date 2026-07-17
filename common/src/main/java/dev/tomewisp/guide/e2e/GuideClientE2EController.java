package dev.tomewisp.guide.e2e;

import com.google.gson.Gson;
import dev.tomewisp.guide.GuideRequestSnapshot;
import dev.tomewisp.guide.GuideRequestStatus;
import dev.tomewisp.guide.GuideService;
import dev.tomewisp.guide.GuideServiceManager;
import dev.tomewisp.guide.GuideSnapshot;
import dev.tomewisp.guide.GuideSubscription;
import dev.tomewisp.tool.ToolResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;

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
    private final List<GuideRequestStatus> transitions = new ArrayList<>();
    private Instant startedAt;
    private UUID requestId;
    private GuideSubscription subscription;
    private boolean started;
    private boolean finished;

    public GuideClientE2EController(
            GuideClientE2EConfig config,
            String loader,
            String gameVersion,
            String modVersion,
            GuideServiceManager services,
            Gson gson,
            Runnable shutdown,
            Set<String> secrets) {
        this.config = java.util.Objects.requireNonNull(config, "config");
        this.loader = require(loader, "loader");
        this.gameVersion = require(gameVersion, "gameVersion");
        this.modVersion = require(modVersion, "modVersion");
        this.services = java.util.Objects.requireNonNull(services, "services");
        this.gson = java.util.Objects.requireNonNull(gson, "gson");
        this.shutdown = java.util.Objects.requireNonNull(shutdown, "shutdown");
        this.secrets = Set.copyOf(secrets);
    }

    /** Starts exactly once after a real client player exists. */
    public void tick(UUID actor) {
        if (started || finished || actor == null) return;
        started = true;
        startedAt = Instant.now();
        GuideService service = services.forActor(actor);
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
                ask(service);
            }
        });
    }

    private void ask(GuideService service) {
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
        LinkedHashMap<String, Long> timings = new LinkedHashMap<>();
        timings.put("total", Duration.between(startedAt, request.terminalAt()).toMillis());
        LinkedHashMap<String, String> hashes = new LinkedHashMap<>();
        hashes.put("assistantTextSha256", sha256(request.assistantText()));
        hashes.put("userMessageSha256", sha256(request.userMessage()));
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
                request.sources().stream().map(value -> value.evidence()).toList(),
                request.status(),
                timings,
                hashes);
        finish(new GuideE2EReportJson(gson).encode(report, secrets));
    }

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
            System.err.println("Unable to write TomeWisp E2E report: " + exception.getMessage());
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
