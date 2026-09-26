package com.selfhealing.healer.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Several test JVMs share the .healer files and the report directory. */
class ConcurrencyTest {

    @Test
    void writersKeepEachOthersEntries(@TempDir Path dir) {
        Path file = dir.resolve("fingerprints.json");
        // Two maps on the same file behave like two JVMs: each loaded the file before the other wrote.
        JsonFileMap<String> jvm1 = new JsonFileMap<>(file, String.class);
        JsonFileMap<String> jvm2 = new JsonFileMap<>(file, String.class);

        jvm1.put("LoginPage.username", "a");
        jvm2.put("CartPage.total", "b");
        jvm1.remove("Nothing.here");

        JsonFileMap<String> fresh = new JsonFileMap<>(file, String.class);
        assertEquals("a", fresh.get("LoginPage.username").orElseThrow());
        assertEquals("b", fresh.get("CartPage.total").orElseThrow());
    }

    @Test
    void reportPartsOfOneRunAreMerged(@TempDir Path dir) throws Exception {
        // A part left by another JVM of the same run (different pid), with event id 1 - like ours.
        HealingEvent other = new HealingEvent();
        other.id = 1;
        other.key = "Other.key";
        other.test = "OtherTest.a";
        HealingRecorder.TestRecord otherTest = new HealingRecorder.TestRecord();
        otherTest.id = "OtherTest.a";
        HealingRecorder.Step step = new HealingRecorder.Step();
        step.healId = 1;
        otherTest.steps.add(step);
        Files.createDirectories(dir.resolve("parts"));
        Json.MAPPER.writeValue(dir.resolve("parts/run42-1.json").toFile(),
                Map.of("runStartedAt", Instant.parse("2026-01-01T10:00:00Z"), "events", List.of(other), "tests", List.of(otherTest)));
        // A part of an older run must be ignored.
        Json.MAPPER.writeValue(dir.resolve("parts/run41-7.json").toFile(), Map.of("events", List.of(other), "tests", List.of()));

        HealingEvent mine = new HealingEvent();
        mine.id = 1;
        mine.key = "Mine.key";
        HealingRecorder.TestRecord myTest = new HealingRecorder.TestRecord();
        myTest.id = "MyTest.b";

        ReportParts.Merged merged = ReportParts.writeAndMerge(dir, "run42", List.of(mine), List.of(myTest),
                Instant.parse("2026-01-01T10:05:00Z"));

        assertEquals(2, merged.events().size());
        assertEquals(2, merged.tests().size());
        assertEquals(2, merged.events().stream().map(e -> e.id).distinct().count(), "event ids must stay unique");
        HealingRecorder.TestRecord mergedOther = merged.tests().stream().filter(t -> t.id.equals("OtherTest.a")).findFirst().orElseThrow();
        int otherId = merged.events().stream().filter(e -> e.key.equals("Other.key")).findFirst().orElseThrow().id;
        assertEquals(otherId, mergedOther.steps.get(0).healId, "steps follow their event's new id");
        assertEquals(Instant.parse("2026-01-01T10:00:00Z"), merged.runStartedAt(), "run starts with the earliest JVM");
        assertTrue(Files.list(dir.resolve("parts")).anyMatch(p -> p.getFileName().toString().startsWith("run42-" + ProcessHandle.current().pid())));
    }
}
