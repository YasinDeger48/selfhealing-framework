package com.selfhealing.healer.core;

import com.fasterxml.jackson.core.type.TypeReference;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Combines the results of several test JVMs of one run (Surefire {@code forkCount > 1}) into one report.
 * Each JVM writes {@code parts/<runId>-<pid>.json}; the report is then built from all parts of that run,
 * so the JVM that finishes last writes the complete report. Enabled by setting {@code healer.runId}.
 */
public final class ReportParts {

    public record Merged(List<HealingEvent> events, List<HealingRecorder.TestRecord> tests, Instant runStartedAt) {
    }

    private static final TypeReference<List<HealingEvent>> EVENTS = new TypeReference<>() { };
    private static final TypeReference<List<HealingRecorder.TestRecord>> TESTS = new TypeReference<>() { };
    /** Event ids are unique per JVM only; each part gets its own id range in the merged report. */
    private static final int ID_RANGE = 1_000_000;

    private ReportParts() {
    }

    /** Writes this JVM's part, then merges all parts of the run - under a lock shared by all JVMs. */
    @SuppressWarnings("unchecked")
    public static Merged writeAndMerge(Path reportDir, String runId, List<HealingEvent> events,
                                       List<HealingRecorder.TestRecord> tests, Instant startedAt) {
        Path parts = reportDir.resolve("parts");
        String prefix = safe(runId) + "-";
        try {
            Files.createDirectories(parts);
            try (FileChannel channel = FileChannel.open(parts.resolve(".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock ignored = channel.lock()) {
                Map<String, Object> mine = new LinkedHashMap<>();
                mine.put("runStartedAt", startedAt);
                mine.put("events", events);
                mine.put("tests", tests);
                Json.MAPPER.writeValue(parts.resolve(prefix + ProcessHandle.current().pid() + ".json").toFile(), mine);

                List<HealingEvent> allEvents = new ArrayList<>();
                List<HealingRecorder.TestRecord> allTests = new ArrayList<>();
                Instant start = startedAt;
                List<Path> files;
                try (Stream<Path> list = Files.list(parts)) {
                    files = list.filter(p -> p.getFileName().toString().startsWith(prefix) && p.toString().endsWith(".json"))
                            .sorted().toList();
                }
                int index = 0;
                for (Path file : files) {
                    Map<String, Object> part = Json.MAPPER.readValue(file.toFile(), Map.class);
                    int offset = index++ * ID_RANGE;
                    List<HealingEvent> ev = Json.MAPPER.convertValue(part.getOrDefault("events", List.of()), EVENTS);
                    List<HealingRecorder.TestRecord> ts = Json.MAPPER.convertValue(part.getOrDefault("tests", List.of()), TESTS);
                    ev.forEach(e -> e.id += offset);
                    ts.forEach(t -> t.steps.forEach(s -> { if (s.healId != null) s.healId += offset; }));
                    allEvents.addAll(ev);
                    allTests.addAll(ts);
                    Object st = part.get("runStartedAt");
                    if (st != null) {
                        Instant partStart = Instant.parse(st.toString());
                        if (partStart.isBefore(start)) start = partStart;
                    }
                }
                return new Merged(allEvents, allTests, start);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot merge report parts", e);
        }
    }

    private static String safe(String runId) {
        return runId.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
