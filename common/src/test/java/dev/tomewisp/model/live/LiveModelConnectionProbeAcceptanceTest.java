package dev.tomewisp.model.live;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import dev.tomewisp.model.CancellationSignal;
import dev.tomewisp.model.ProviderModelClients;
import dev.tomewisp.model.config.ModelProfilesConfigLoader;
import dev.tomewisp.model.config.ResolvedModelProfile;
import dev.tomewisp.settings.model.ModelConnectionProbe;
import dev.tomewisp.settings.model.ModelConnectionResult;
import dev.tomewisp.tool.ToolResult;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

final class LiveModelConnectionProbeAcceptanceTest {
    @Test
    void probesOneProfileWithoutRetainingProviderContent() throws Exception {
        Map<String, String> environment = System.getenv();
        Assumptions.assumeTrue(Boolean.parseBoolean(
                environment.get("TOMEWISP_LIVE_SETTINGS_PROBE")));
        String configPath = required(environment, "TOMEWISP_SETTINGS_PROBE_CONFIG");
        ToolResult<ModelProfilesConfigLoader.Load> loaded = new ModelProfilesConfigLoader().load(
                Path.of(configPath),
                Path.of(configPath + ".no-legacy"),
                environment);
        assertTrue(loaded instanceof ToolResult.Success<ModelProfilesConfigLoader.Load>,
                "settings probe configuration is invalid");
        ModelProfilesConfigLoader.Load profiles =
                ((ToolResult.Success<ModelProfilesConfigLoader.Load>) loaded).value();
        String selectedId = environment.getOrDefault(
                "TOMEWISP_SETTINGS_PROBE_PROFILE",
                profiles.config().defaultProfileId());
        ResolvedModelProfile profile = profiles.profiles().stream()
                .filter(candidate -> candidate.definition().id().equals(selectedId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("settings probe profile does not exist"));
        assertTrue(profile.available(), "settings probe profile is unavailable");

        Gson gson = new Gson();
        ModelConnectionProbe probe = new ModelConnectionProbe(
                config -> ProviderModelClients.create(config, gson),
                Clock.systemUTC(),
                System::nanoTime);
        ModelConnectionResult result = probe.test(profile, new CancellationSignal())
                .get(6, TimeUnit.MINUTES);
        if (result instanceof ModelConnectionResult.Success success) {
            System.out.println("TOMEWISP_SETTINGS_PROBE code=success profile="
                    + success.profileId()
                    + " protocol=" + success.protocol()
                    + " authority=" + success.authority()
                    + " latencyMs=" + success.latencyMillis());
            return;
        }
        ModelConnectionResult.Failure failure = (ModelConnectionResult.Failure) result;
        System.out.println("TOMEWISP_SETTINGS_PROBE code=" + failure.code());
        throw new AssertionError("settings connection probe failed with " + failure.code());
    }

    private static String required(Map<String, String> environment, String name) {
        String value = environment.get(name);
        Assumptions.assumeTrue(value != null && !value.isBlank(), name + " is required");
        return value;
    }
}
