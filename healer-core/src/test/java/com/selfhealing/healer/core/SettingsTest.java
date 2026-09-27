package com.selfhealing.healer.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Layers: healer.properties < healer-<profile>.properties < healer-local.properties < environment < -D. */
class SettingsTest {

    @Test
    void laterLayersWinAndReferencesAreResolved(@TempDir Path project) throws Exception {
        // src/test/resources/healer.properties and healer-ci.properties of this module are the first two layers
        Files.writeString(project.resolve("healer-local.properties"), "browser.headless=false\n");
        Map<String, String> env = Map.of(
                "HEALER_REPORT_OPEN", "always",
                "HEALER_LLM_MAXCOSTPERRUN", "0.50",
                "SETTINGS_TEST_KEY", "sk-from-env",
                "CI", "true");
        Properties system = new Properties();
        system.setProperty("healer.profile", "ci");
        system.setProperty("browser.slowmo", "100");

        HealerConfig c = HealerConfig.load(env, system, project);

        assertEquals("claude-haiku-4-5", c.get("healer.llm.model", ""), "from healer.properties");
        assertEquals("false", c.get("healer.verbose", ""), "from the ci profile");
        assertFalse(c.getBoolean("browser.headless", true), "healer-local.properties beats healer.properties");
        assertEquals("always", c.get("healer.report.open", ""), "environment beats the files");
        assertEquals("0.50", c.get("healer.llm.maxCostPerRun", ""), "HEALER_LLM_MAXCOSTPERRUN -> camelCase key");
        assertEquals(100, c.getLong("browser.slowmo", 0), "-D wins");
        assertEquals("sk-from-env", c.get("healer.llm.apiKey", ""), "${env:SETTINGS_TEST_KEY}");
        assertEquals("https://default.example", c.get("app.baseUrl", ""), "${env:UNSET:-default}");
        assertTrue(c.ci());
        assertFalse(c.pdfReport(), "pdf=auto is off on CI");
        assertEquals("environment HEALER_REPORT_OPEN", c.source("healer.report.open"));
        assertEquals("healer-local.properties", c.source("browser.headless"));
    }

    @Test
    void switchesAndPolicies() {
        Properties p = new Properties();
        p.setProperty("healer.enabled", "false");
        p.setProperty("healer.llm.enabled", "true");
        p.setProperty("healer.report.screenshots", "failures");
        HealerConfig c = HealerConfig.from(p);
        assertFalse(c.enabled());
        assertFalse(c.llmEnabled(), "a disabled healer never calls Claude");
        assertEquals(HealerConfig.Screenshots.FAILURES, c.screenshots());
        assertTrue(c.pdfReport(), "pdf=auto is on outside CI");

        assertTrue(ReportOpener.wanted("always", false, false));
        assertTrue(ReportOpener.wanted("onWarn", false, true));
        assertFalse(ReportOpener.wanted("onFailure", false, true));
        assertTrue(ReportOpener.wanted("onFailure", true, false));
        assertFalse(ReportOpener.wanted("never", true, true));
    }

    @Test
    void costBudgetStopsFurtherCalls() {
        Properties p = new Properties();
        p.setProperty("healer.llm.maxCostPerRun", "0.01");
        p.setProperty("healer.llm.price.my-model", "2.00,8.00");
        LlmPricing.resetBudget();
        LlmPricing.configure(HealerConfig.from(p));
        assertEquals(0.01, LlmPricing.cost("my-model", 1_000, 1_000), 1e-9, "custom price: 2 + 8 per million");
        assertTrue(LlmPricing.withinBudget());
        LlmPricing.spent(0.02);
        assertFalse(LlmPricing.withinBudget());
        LlmPricing.resetBudget();
        LlmPricing.configure(HealerConfig.from(new Properties()));
        assertTrue(LlmPricing.withinBudget(), "no limit by default");
    }

    @Test
    void historyKeepsTheNewestRuns(@TempDir Path dir) throws Exception {
        Path history = dir.resolve("history");
        for (String run : new String[] {"2026-01-01_10-00-00", "2026-01-02_10-00-00", "2026-01-03_10-00-00"}) {
            Files.createDirectories(history.resolve(run));
            Files.writeString(history.resolve(run).resolve("healing-report.html"), run);
        }
        ReportHistory.prune(history, 2);
        assertFalse(Files.exists(history.resolve("2026-01-01_10-00-00")));
        assertTrue(Files.exists(history.resolve("2026-01-03_10-00-00")));
    }
}
