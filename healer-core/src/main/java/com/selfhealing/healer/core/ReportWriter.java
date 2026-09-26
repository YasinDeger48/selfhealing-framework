package com.selfhealing.healer.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Writes the run report: {@code healing-report.json} (machine readable) and
 * {@code healing-report.html} (single self-contained file, screenshots inlined, printable to PDF).
 */
public final class ReportWriter {

    public static final String JSON_FILE = "healing-report.json";
    public static final String HTML_FILE = "healing-report.html";

    private ReportWriter() {
    }

    /** Builds the report model shared by the JSON and HTML outputs. */
    public static Map<String, Object> model(List<HealingEvent> events, List<HealingRecorder.TestRecord> tests,
                                            HealerConfig config) {
        return model(events, tests, config, HealingRecorder.runStartedAt());
    }

    public static Map<String, Object> model(List<HealingEvent> events, List<HealingRecorder.TestRecord> tests,
                                            HealerConfig config, Instant runStartedAt) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("tests", tests.size());
        summary.put("passed", tests.stream().filter(t -> "PASSED".equals(t.status)).count());
        summary.put("failed", tests.stream().filter(t -> "FAILED".equals(t.status)).count());
        summary.put("testsWithWarn", events.stream().filter(e -> e.status == HealingEvent.Status.HEALED)
                .map(e -> e.test).distinct().count());
        summary.put("healed", count(events, HealingEvent.Status.HEALED));
        summary.put("suggested", count(events, HealingEvent.Status.SUGGESTED));
        summary.put("healFailed", count(events, HealingEvent.Status.FAILED));
        summary.put("uniqueElements", events.stream().map(e -> e.key).distinct().count());
        summary.put("popups", events.stream().filter(e -> "popup".equals(e.kind) && e.status == HealingEvent.Status.HEALED).count());
        summary.put("bySource", events.stream().filter(e -> e.source != null && !e.reused && e.kind == null)
                .collect(Collectors.groupingBy(e -> e.source.name(), LinkedHashMap::new, Collectors.counting())));
        summary.put("llmCalls", events.stream().filter(e -> e.llmModel != null && !e.reused).count());
        summary.put("llmInputTokens", events.stream().filter(e -> !e.reused).mapToLong(e -> e.llmInputTokens).sum());
        summary.put("llmOutputTokens", events.stream().filter(e -> !e.reused).mapToLong(e -> e.llmOutputTokens).sum());
        summary.put("llmCostUsd", llmCost(events));

        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("language", Messages.language());
        settings.put("mode", config.mode().name());
        settings.put("minConfidence", config.minConfidence());
        settings.put("minMargin", config.minMargin());
        settings.put("llmEnabled", config.llmEnabled());
        settings.put("llmModel", config.llmModel());
        settings.put("llmEscalateTo", config.get("healer.llm.escalateTo", ""));

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("generatedAt", Instant.now());
        report.put("runStartedAt", runStartedAt);
        report.put("runDurationMs", Duration.between(runStartedAt, Instant.now()).toMillis());
        report.put("settings", settings);
        report.put("summary", summary);
        report.put("tests", tests);
        report.put("events", events);
        return report;
    }

    public static Path writeJson(Path reportDir, Map<String, Object> model) {
        try {
            Files.createDirectories(reportDir);
            Path file = reportDir.resolve(JSON_FILE);
            Json.MAPPER.writeValue(file.toFile(), model);
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write healing report", e);
        }
    }

    /** Self-contained HTML: the template renders the embedded JSON; screenshots become data URIs. */
    @SuppressWarnings("unchecked")
    public static Path writeHtml(Path reportDir, Map<String, Object> model) {
        try (InputStream in = ReportWriter.class.getResourceAsStream("report-template.html")) {
            String template = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            Map<String, Object> copy = Json.MAPPER.convertValue(model, Map.class);
            Map<String, Object> i18n = new LinkedHashMap<>();
            i18n.put("default", Messages.language());
            i18n.put("languages", Messages.languages());
            i18n.put("labels", Messages.reportLabels());
            copy.put("i18n", i18n);
            for (Map<String, Object> e : (List<Map<String, Object>>) copy.getOrDefault("events", List.of())) {
                Object shot = e.get("screenshot");
                if (shot instanceof String path && !path.startsWith("data:")) {
                    Path img = reportDir.resolve(path);
                    if (Files.exists(img)) {
                        e.put("screenshot", "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(Files.readAllBytes(img)));
                    }
                }
            }
            String json = Json.MAPPER.writer().without(com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT)
                    .writeValueAsString(copy).replace("</", "<\\/");
            Files.createDirectories(reportDir);
            Path file = reportDir.resolve(HTML_FILE);
            Files.writeString(file, template.replace("/*__REPORT_DATA__*/null", json), StandardCharsets.UTF_8);
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write HTML report", e);
        }
    }

    public static String consoleSummary(List<HealingEvent> events, Path reportFile) {
        if (events.isEmpty()) return "[healer] No locators needed healing. Report: " + reportFile.toAbsolutePath();
        StringBuilder sb = new StringBuilder("\n")
                .append("================ SELF-HEALING SUMMARY ================\n")
                .append(String.format("healed: %d | suggested: %d | failed: %d%n",
                        count(events, HealingEvent.Status.HEALED),
                        count(events, HealingEvent.Status.SUGGESTED),
                        count(events, HealingEvent.Status.FAILED)));
        // One line per element, with the tests it affected and what it cost.
        Map<String, List<HealingEvent>> byKey = new LinkedHashMap<>();
        events.forEach(e -> byKey.computeIfAbsent(e.status + " " + e.key, k -> new ArrayList<>()).add(e));
        for (List<HealingEvent> group : byKey.values()) {
            HealingEvent e = group.get(0);
            double cost = group.stream().mapToDouble(g -> g.llmCostUsd).sum();
            sb.append(e.status == HealingEvent.Status.FAILED ? " [FAIL] " : " [WARN] ").append(e.summaryLine())
              .append(" · cost ").append(LlmPricing.format(cost)).append('\n')
              .append("        tests: ")
              .append(group.stream().map(g -> g.test).distinct().collect(Collectors.joining(", ")))
              .append('\n');
        }
        sb.append(String.format("LLM usage: %,d input / %,d output tokens, total cost %s%n",
                events.stream().mapToLong(e -> e.llmInputTokens).sum(),
                events.stream().mapToLong(e -> e.llmOutputTokens).sum(), LlmPricing.format(llmCost(events))));
        sb.append("Report: ").append(reportFile.toAbsolutePath()).append('\n')
          .append("=======================================================");
        return sb.toString();
    }

    /** Reused events carry no cost, so a plain sum counts each Claude call once. */
    static double llmCost(List<HealingEvent> events) {
        return events.stream().mapToDouble(e -> e.llmCostUsd).sum();
    }

    private static long count(List<HealingEvent> events, HealingEvent.Status status) {
        return events.stream().filter(e -> e.status == status).count();
    }
}
