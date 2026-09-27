package com.selfhealing.healer.junit4;

import com.fasterxml.jackson.databind.JsonNode;
import com.selfhealing.healer.core.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.runner.JUnitCore;
import org.junit.runner.Result;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs a JUnit 4 class like a user's suite and checks the report the rule produced. */
class HealingRuleTest {

    @Test
    void reportsHealsAndFailures(@TempDir Path dir) throws Exception {
        System.setProperty("healer.storeDir", dir.resolve("store").toString());
        System.setProperty("healer.reportDir", dir.resolve("report").toString());

        Result result = JUnitCore.runClasses(LoginFlow.class);
        assertEquals(2, result.getRunCount());
        assertEquals(1, result.getFailureCount(), "wrongExpectation fails on purpose");
        HealingRule.finishRun();

        JsonNode report = Json.MAPPER.readTree(dir.resolve("report/healing-report.json").toFile());
        JsonNode healed = find(report, "LoginFlow.healsRenamedButton");
        assertEquals("PASSED", healed.path("status").asText());
        assertTrue(healed.path("steps").size() >= 2, "steps recorded");
        assertTrue(report.path("summary").path("healed").asInt() >= 1);
        JsonNode failed = find(report, "LoginFlow.wrongExpectation");
        assertEquals("FAILED", failed.path("status").asText());
        assertEquals("ASSERTION", failed.path("triage").path("category").asText());
    }

    private static JsonNode find(JsonNode report, String id) {
        for (JsonNode t : report.path("tests")) if (id.equals(t.path("id").asText())) return t;
        throw new AssertionError("no test " + id + " in " + report.path("tests"));
    }
}
