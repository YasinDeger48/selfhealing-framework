package com.selfhealing.healer.cucumber;

import com.fasterxml.jackson.databind.JsonNode;
import com.selfhealing.healer.core.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs a feature file through the Cucumber CLI with the plugin, as a user's runner would. */
class HealingCucumberPluginTest {

    @Test
    void scenariosAndGherkinStepsAreReported(@TempDir Path dir) throws Exception {
        System.setProperty("healer.storeDir", dir.resolve("store").toString());
        System.setProperty("healer.reportDir", dir.resolve("report").toString());

        byte status = io.cucumber.core.cli.Main.run(new String[] {
                "--glue", "com.selfhealing.healer.cucumber.glue",
                "--plugin", "com.selfhealing.healer.cucumber.HealingCucumberPlugin",
                "--monochrome", "classpath:features"}, Thread.currentThread().getContextClassLoader());
        assertEquals(1, status, "one scenario fails on purpose");

        JsonNode report = Json.MAPPER.readTree(dir.resolve("report/healing-report.json").toFile());
        JsonNode saved = find(report, "form.Save after a rename");
        assertEquals("PASSED", saved.path("status").asText());
        assertEquals("Given", saved.path("steps").get(0).path("element").asText(), "Gherkin steps are report steps");
        assertEquals("step", saved.path("steps").get(0).path("action").asText());
        assertTrue(report.path("summary").path("healed").asInt() >= 1);

        JsonNode wrong = find(report, "form.Wrong expectation");
        assertEquals("FAILED", wrong.path("status").asText());
        assertEquals("ASSERTION", wrong.path("triage").path("category").asText());
        assertTrue(wrong.path("triage").path("screenshot").asText().startsWith("screenshots/"),
                "captured before the After hook closed the page");
    }

    private static JsonNode find(JsonNode report, String id) {
        for (JsonNode t : report.path("tests")) if (id.equals(t.path("id").asText())) return t;
        throw new AssertionError("no test " + id + " in " + report.path("tests"));
    }
}
