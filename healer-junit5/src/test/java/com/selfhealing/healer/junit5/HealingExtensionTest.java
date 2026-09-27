package com.selfhealing.healer.junit5;

import com.fasterxml.jackson.databind.JsonNode;
import com.selfhealing.healer.core.Json;
import org.junit.jupiter.api.Test;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;
import static org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder.request;

/** Runs a JUnit 5 class with the generic extension (the adapter is found on the classpath) and checks the report. */
class HealingExtensionTest {

    @Test
    void reportsHealsAndAnalysesFailures() throws Exception {
        Path report = Path.of(System.getProperty("healer.reportDir"));   // configured in the pom
        LauncherDiscoveryRequest req = request().selectors(selectClass(LoginFlow.class)).build();
        Launcher launcher = LauncherFactory.create();
        SummaryGeneratingListener summary = new SummaryGeneratingListener();
        launcher.execute(req, summary);
        TestExecutionSummary s = summary.getSummary();
        assertEquals(2, s.getTestsStartedCount());
        assertEquals(1, s.getTestsFailedCount(), "wrongExpectation fails on purpose");

        JsonNode json = Json.MAPPER.readTree(report.resolve("healing-report.json").toFile());
        JsonNode healed = find(json, "LoginFlow.healsRenamedButton");
        assertEquals("PASSED", healed.path("status").asText());
        assertTrue(json.path("summary").path("healed").asInt() >= 1);
        JsonNode failed = find(json, "LoginFlow.wrongExpectation");
        assertEquals("FAILED", failed.path("status").asText());
        assertEquals("ASSERTION", failed.path("triage").path("category").asText());
        assertTrue(failed.path("triage").path("screenshot").asText().startsWith("screenshots/"),
                "captured before @AfterEach closed the page");
    }

    private static JsonNode find(JsonNode report, String id) {
        for (JsonNode t : report.path("tests")) if (id.equals(t.path("id").asText())) return t;
        throw new AssertionError("no test " + id + " in " + report.path("tests"));
    }
}
