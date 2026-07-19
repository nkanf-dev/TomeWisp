package dev.tomewisp.guide.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tomewisp.guide.GuideModelMode;
import java.util.Properties;
import org.junit.jupiter.api.Test;

final class GuideClientE2EConfigTest {
    @Test
    void remainsInertUnlessExplicitlyEnabled() {
        assertTrue(GuideClientE2EConfig.from(new Properties()).isEmpty());
    }

    @Test
    void parsesExplicitHarnessContract() {
        Properties properties = new Properties();
        properties.setProperty(GuideClientE2EConfig.ENABLED, "true");
        properties.setProperty("tomewisp.e2e.question", "Can I craft this?");
        properties.setProperty("tomewisp.e2e.report", "build/e2e/report.json");
        properties.setProperty("tomewisp.e2e.modelMode", "server");
        properties.setProperty("tomewisp.e2e.shutdown", "false");
        properties.setProperty("tomewisp.e2e.historySeedRequests", "12");

        GuideClientE2EConfig config = GuideClientE2EConfig.from(properties).orElseThrow();

        assertEquals(GuideModelMode.SERVER, config.modelMode());
        assertEquals("e2e", config.sessionId());
        assertEquals(12, config.historySeedRequests());
        assertTrue(!config.shutdownAfterReport());
    }

    @Test
    void enabledHarnessFailsFastWhenRequiredInputIsMissing() {
        Properties properties = new Properties();
        properties.setProperty(GuideClientE2EConfig.ENABLED, "true");
        assertThrows(IllegalArgumentException.class, () -> GuideClientE2EConfig.from(properties));
    }
}
