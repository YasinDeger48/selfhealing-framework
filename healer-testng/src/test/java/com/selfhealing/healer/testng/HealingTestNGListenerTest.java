package com.selfhealing.healer.testng;

import com.fasterxml.jackson.databind.JsonNode;
import com.selfhealing.healer.core.Json;
import org.junit.jupiter.api.Test;
import org.testng.TestNG;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs a TestNG class like a user's suite: the listener is found through META-INF/services, no configuration. */
class HealingTestNGListenerTest {

    @Test
    void reportsHealsAndAnalysesFailures() throws Exception {
        Path report = Path.of(System.getProperty("healer.reportDir"));   // configured in the pom (see there)

        TestNG testng = new TestNG();
        testng.setTestClasses(new Class<?>[] {LoginFlow.class});
        testng.setUseDefaultListeners(false);
        testng.setVerbose(0);
        testng.run();

        JsonNode json = Json.MAPPER.readTree(report.resolve("healing-report.json").toFile());
        assertEquals(2, json.path("tests").size(), json.toString());
        JsonNode healed = find(json, "LoginFlow.healsRenamedButton");
        assertEquals("PASSED", healed.path("status").asText());
        assertTrue(json.path("summary").path("healed").asInt() >= 1, "the renamed button was healed");

        JsonNode failed = find(json, "LoginFlow.wrongExpectation");
        assertEquals("FAILED", failed.path("status").asText());
        assertEquals("ASSERTION", failed.path("triage").path("category").asText());
        assertTrue(failed.path("triage").path("screenshot").asText().startsWith("screenshots/"),
                "captured before @AfterMethod closed the page");
        assertTrue(Files.exists(report.resolve("healing-report.html")));
    }

    private static JsonNode find(JsonNode report, String id) {
        for (JsonNode t : report.path("tests")) if (id.equals(t.path("id").asText())) return t;
        throw new AssertionError("no test " + id + " in " + report.path("tests"));
    }
}
