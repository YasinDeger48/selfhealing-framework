package com.selfhealing.healer.core;

import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The test-framework independent part of a run: test lifecycle, WARN blocks, failure analysis, and - once at the
 * end - reports, code fixes and locator quality. The JUnit 5 extension, the TestNG listener and the Cucumber plugin
 * are thin wrappers around it.
 */
public final class HealingRun {

    private final HealingRuntime runtime;
    private final AtomicBoolean finished = new AtomicBoolean();

    public HealingRun(HealingRuntime runtime) {
        this.runtime = runtime;
    }

    public HealingRuntime runtime() {
        return runtime;
    }

    public void testStarted(String testId, String className, String method, String displayName) {
        HealingRecorder.startTest(testId, className, method, displayName);
    }

    /**
     * After the test body: prints the WARN block of a healed test and returns its heals (the caller may publish them).
     * Throws an AssertionError when healer.failOnHeal=true and something needed healing.
     */
    public List<HealingEvent> testBodyFinished(String testId) {
        List<HealingEvent> healed = HealingRecorder.eventsFor(testId).stream().filter(HealingEvent::needsReview).toList();
        if (healed.isEmpty()) return healed;
        double cost = healed.stream().mapToDouble(e -> e.llmCostUsd).sum();
        StringBuilder sb = new StringBuilder()
                .append("\n[healer] WARN ").append(testId).append(" passed with ").append(healed.size())
                .append(" healed locator(s), cost ").append(LlmPricing.format(cost))
                .append(" - review and update the page object:");
        for (HealingEvent e : healed) {
            sb.append("\n   - ").append(e.summaryLine()).append(" · ").append(LlmPricing.format(e.llmCostUsd))
              .append(e.reused ? " (reused)" : "");
        }
        System.out.println(sb);
        if (runtime.engine().config().failOnHeal()) {
            throw new AssertionError("healer.failOnHeal=true and " + healed.size() + " locator(s) needed healing");
        }
        return healed;
    }

    /** Ends the test on this thread (events and steps recorded later belong to no test). */
    public void testEnded() {
        HealingRecorder.endTest();
    }

    /**
     * Classifies a failure. Call it while the page is still open (right after the test body failed) to get a
     * screenshot and the URL; calling it again later is harmless.
     */
    public void analyse(String testId, Throwable error, boolean pageOpen) {
        try {
            HealingRecorder.TestRecord record = HealingRecorder.test(testId);
            if (record == null || record.triage != null
                    || "off".equalsIgnoreCase(runtime.engine().config().get("healer.triage", "on"))) return;
            String url = pageOpen ? runtime.currentUrl() : null;
            FailureTriage.Result result = FailureTriage.classify(error, record, HealingRecorder.eventsFor(testId),
                    url != null ? url : record.lastUrl);
            if (pageOpen) result.screenshot = runtime.failureScreenshot(testId);
            record.triage = result;
        } catch (RuntimeException ignored) {
            // the analysis must never hide the real failure
        }
    }

    public void testPassed(String testId) {
        HealingRecorder.finishTest(testId, "PASSED", null);
    }

    public void testFailed(String testId, Throwable cause) {
        analyse(testId, cause, false);   // failures outside the test body (setup) get the analysis without screenshot
        HealingRecorder.finishTest(testId, "FAILED", cause == null ? null : cause.getClass().getSimpleName() + ": " + cause.getMessage());
        HealingRecorder.TestRecord record = HealingRecorder.test(testId);
        if (record != null && record.triage != null) System.out.println(FailureTriage.consoleLine(testId, record.triage));
    }

    public void testSkipped(String testId, String reason) {
        HealingRecorder.finishTest(testId, "SKIPPED", reason);
    }

    /** Once per JVM at the end of the run: failure explanations, code fixes, locator quality, JSON/HTML/PDF reports. */
    public void finish() {
        if (!finished.compareAndSet(false, true)) return;
        HealerConfig config = runtime.engine().config();
        Path reportDir = config.reportDir();
        List<HealingEvent> events = HealingRecorder.all();
        String runId = config.get("healer.runId", "");
        explainFailures(config);
        List<HealingEvent> runEvents = events;
        List<LocatorQuality.Entry> quality = LocatorQuality.all();
        Map<String, Object> model;
        if (runId.isBlank()) {
            model = ReportWriter.model(events, HealingRecorder.tests(), config);
        } else {
            // Several JVMs (forkCount > 1): every JVM adds its part; the last one writes the complete report.
            ReportParts.Merged merged = ReportParts.writeAndMerge(reportDir, runId, events, HealingRecorder.tests(),
                    quality, HealingRecorder.runStartedAt());
            model = ReportWriter.model(merged.events(), merged.tests(), config, merged.runStartedAt());
            runEvents = merged.events();
            quality = merged.quality();
        }
        codeFixes(config, runEvents, model);
        locatorQuality(config, quality, model);
        ReportWriter.writeJson(reportDir, model);
        Path html = ReportWriter.writeHtml(reportDir, model);
        System.out.println(ReportWriter.consoleSummary(events, html));
        System.out.println("[healer] HTML report: " + html.toAbsolutePath().toUri());
        if (Boolean.parseBoolean(config.get("healer.report.pdf", "true"))) {
            runtime.exportPdf(html, reportDir.resolve("healing-report.pdf"), config)
                    .ifPresent(pdf -> System.out.println("[healer] PDF report:  " + pdf.toAbsolutePath()));
        }
    }

    /** Claude's short explanation for each failed test of this JVM (healer.triage.llm, default = healer.llm.enabled). */
    static void explainFailures(HealerConfig config) {
        List<HealingRecorder.TestRecord> failed = HealingRecorder.tests().stream()
                .filter(t -> "FAILED".equals(t.status) && t.triage != null && t.triage.explanation == null).toList();
        if (failed.isEmpty()) return;
        FailureExplainer explainer = FailureExplainer.discover(config);
        if (explainer == FailureExplainer.NONE) return;
        for (HealingRecorder.TestRecord t : failed) {
            try {
                explainer.explain(t, t.triage, HealingRecorder.eventsFor(t.id)).ifPresent(x -> {
                    t.triage.explanation = x.summary();
                    t.triage.suggestion = x.suggestion();
                    if (x.usage() != null) {
                        t.triage.llmModel = x.usage().model();
                        t.triage.llmInputTokens = x.usage().inputTokens();
                        t.triage.llmOutputTokens = x.usage().outputTokens();
                        t.triage.llmCostUsd = x.usage().costUsd();
                    }
                    System.out.println(FailureTriage.consoleLine(t.id, t.triage));
                });
            } catch (RuntimeException e) {
                System.out.println("[healer] Failure explanation skipped for " + t.id + ": " + e.getMessage());
            }
        }
    }

    /** Source-code fixes for the healed locators: a patch to review, or applied directly (healer.fix=apply). */
    static void codeFixes(HealerConfig config, List<HealingEvent> events, Map<String, Object> model) {
        if (!LocatorFixer.enabled(config) || events.isEmpty()) return;
        try {
            Path project = Path.of("").toAbsolutePath();
            LocatorFixer.Plan plan = LocatorFixer.plan(events, config, project);
            if (plan.fixes().isEmpty()) return;
            Path patch = LocatorFixer.writePatch(config.reportDir(), plan);
            if (LocatorFixer.applyMode(config)) plan = LocatorFixer.apply(plan, events, config, project);
            model.put("fixes", plan.fixes());
            if (patch != null) model.put("fixPatch", project.relativize(patch.toAbsolutePath()).toString().replace('\\', '/'));
            model.put("fixMode", LocatorFixer.applyMode(config) ? "apply" : "patch");

            StringBuilder sb = new StringBuilder("[healer] Code fixes:");
            for (LocatorFixer.Fix f : plan.fixes()) {
                sb.append("\n   ").append(String.format("%-7s", f.status())).append(' ')
                  .append(f.file() == null ? "?" : f.file() + (f.line() > 0 ? ":" + f.line() : ""))
                  .append("  ").append(f.originalSelector()).append("  ->  ").append(f.healedSelector())
                  .append(f.note() == null ? "" : "  (" + f.noteText() + ")");
            }
            if (patch != null && !LocatorFixer.applyMode(config)) {
                sb.append("\n   Review and apply: git apply ").append(project.relativize(patch.toAbsolutePath()).toString().replace('\\', '/'))
                  .append("   (or run once with -Dhealer.fix=apply)");
            }
            System.out.println(sb);
        } catch (RuntimeException e) {
            System.out.println("[healer] Code fixes skipped: " + e.getMessage());   // never fail the run over this
        }
    }

    /** The "Locator quality" section and locator-improvements.patch (suggestions only - never applied automatically). */
    static void locatorQuality(HealerConfig config, List<LocatorQuality.Entry> quality, Map<String, Object> model) {
        if (quality.isEmpty()) return;
        try {
            List<LocatorQuality.Entry> sorted = LocatorQuality.sorted(quality);
            Path project = Path.of("").toAbsolutePath();
            sorted.forEach(e -> e.sourceFile = LocatorFixer.projectPath(e.sourceFile, config, project));
            model.put("locatorQuality", sorted);
            Map<SelectorLint.Risk, Long> counts = new EnumMap<>(SelectorLint.Risk.class);
            sorted.forEach(e -> counts.merge(e.risk, 1L, Long::sum));
            long suggested = sorted.stream().filter(e -> e.suggestion != null && !e.broken).count();

            LocatorFixer.Plan plan = LocatorFixer.plan(LocatorQuality.asImprovements(sorted), config, project);
            Path patch = LocatorFixer.writePatch(config.reportDir(), plan, "locator-improvements.patch");
            String patchPath = patch == null ? null : project.relativize(patch.toAbsolutePath()).toString().replace('\\', '/');
            if (patchPath != null) model.put("improvePatch", patchPath);

            System.out.printf("[healer] Locator quality: %d high, %d medium, %d low risk of %d locators; %d more stable selector(s) suggested%s%n",
                    counts.getOrDefault(SelectorLint.Risk.HIGH, 0L), counts.getOrDefault(SelectorLint.Risk.MEDIUM, 0L),
                    counts.getOrDefault(SelectorLint.Risk.LOW, 0L), sorted.size(), suggested,
                    patchPath == null ? "" : " -> review, then: git apply " + patchPath);
        } catch (RuntimeException e) {
            System.out.println("[healer] Locator quality skipped: " + e.getMessage());
        }
    }
}
