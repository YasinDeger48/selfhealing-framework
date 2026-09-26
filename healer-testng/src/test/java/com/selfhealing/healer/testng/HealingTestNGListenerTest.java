package com.selfhealing.healer.testng;

import com.fasterxml.jackson.databind.JsonNode;
import com.selfhealing.healer.core.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testng.TestNG;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs a TestNG class like a user's suite: the listener is found through META-INF/services, no configuration. */
class HealingTestNGListenerTest {

    @Test
    void reportsHealsAndAnalysesFailures(@TempDir Path dir) throws Exception {
        System.setProperty("healer.storeDir", dir.resolve("store").toString());
        System.setProperty("healer.reportDir", dir.resolve("report").toString());

        TestNG testng = new TestNG();
        testng.setTestClasses(new Class<?>[] {LoginFlow.class});
        testng.setUseDefaultListeners(false);
        testng.setVerbose(0);
        testng.run();

        JsonNode report = Json.MAPPER.readTree(dir.resolve("report/healing-report.json").toFile());
        assertEquals(2, report.path("tests").size(), report.toString());
        JsonNode healed = find(report, "LoginFlow.healsRenamedButton");
        assertEquals("PASSED", healed.path("status").asText());
        assertTrue(report.path("summary").path("healed").asInt() >= 1, "the renamed button was healed");

        JsonNode failed = find(report, "LoginFlow.wrongExpectation");
        assertEquals("FAILED", failed.path("status").asText());
        assertEquals("ASSERTION", failed.path("triage").path("category").asText());
        assertTrue(failed.path("triage").path("screenshot").asText().startsWith("screenshots/"),
                "captured before @AfterMethod closed the page");
        assertTrue(Files.exists(dir.resolve("report/healing-report.html")));
    }

    private static JsonNode find(JsonNode report, String id) {
        for (JsonNode t : report.path("tests")) if (id.equals(t.path("id").asText())) return t;
        throw new AssertionError("no test " + id + " in " + report.path("tests"));
    }
}
