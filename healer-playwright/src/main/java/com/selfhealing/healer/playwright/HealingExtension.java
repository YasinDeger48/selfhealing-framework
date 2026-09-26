package com.selfhealing.healer.playwright;

import com.selfhealing.healer.core.HealerConfig;
import com.selfhealing.healer.core.HealingEvent;
import com.selfhealing.healer.core.HealingRecorder;
import com.selfhealing.healer.core.LlmPricing;
import com.selfhealing.healer.core.LocatorFixer;
import com.selfhealing.healer.core.LocatorQuality;
import com.selfhealing.healer.core.SelectorLint;
import com.selfhealing.healer.core.ReportParts;
import com.selfhealing.healer.core.ReportWriter;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestExecutionExceptionHandler;
import org.junit.jupiter.api.extension.TestWatcher;
import com.selfhealing.healer.core.FailureExplainer;
import com.selfhealing.healer.core.FailureTriage;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * JUnit 5 extension: tags healing events and steps with the running test, prints a WARN block
 * after each healed test and writes the JSON, HTML and PDF reports once at the end of the run.
 *
 * <pre>{@code @ExtendWith(HealingExtension.class)}</pre>
 */
public class HealingExtension implements BeforeEachCallback, AfterEachCallback, TestWatcher, TestExecutionExceptionHandler {

    private static final ExtensionContext.Namespace NS = ExtensionContext.Namespace.create(HealingExtension.class);

    @Override
    public void beforeEach(ExtensionContext context) {
        context.getRoot().getStore(NS).getOrComputeIfAbsent(ReportFlusher.class, k -> new ReportFlusher());
        HealingRecorder.startTest(testId(context), context.getRequiredTestClass().getSimpleName(),
                context.getRequiredTestMethod().getName(), context.getDisplayName());
    }

    @Override
    public void afterEach(ExtensionContext context) {
        String test = testId(context);
        try {
            List<HealingEvent> healed = HealingRecorder.eventsFor(test).stream()
                    .filter(e -> e.status == HealingEvent.Status.HEALED)
                    .toList();
            if (healed.isEmpty()) return;
            double cost = healed.stream().mapToDouble(e -> e.llmCostUsd).sum();
            StringBuilder sb = new StringBuilder()
                    .append("\n[healer] WARN ").append(test).append(" passed with ").append(healed.size())
                    .append(" healed locator(s), cost ").append(LlmPricing.format(cost))
                    .append(" - review and update the page object:");
            for (HealingEvent e : healed) {
                sb.append("\n   - ").append(e.summaryLine()).append(" · ").append(LlmPricing.format(e.llmCostUsd))
                  .append(e.reused ? " (reused)" : "");
                context.publishReportEntry("healer.warn." + e.key, e.originalSelector + " -> " + e.healedSelector);
            }
            System.out.println(sb);
            if (SelfHealingPage.engine().config().failOnHeal()) {
                throw new AssertionError("healer.failOnHeal=true and " + healed.size() + " locator(s) needed healing");
            }
        } finally {
            HealingRecorder.endTest();
        }
    }

    /** Runs while the page is still open: the failure is classified and the page captured before @AfterEach closes it. */
    @Override
    public void handleTestExecutionException(ExtensionContext context, Throwable error) throws Throwable {
        analyse(testId(context), error, true);
        throw error;
    }

    private static void analyse(String test, Throwable error, boolean pageOpen) {
        try {
            HealingRecorder.TestRecord record = HealingRecorder.test(test);
            if (record == null || record.triage != null || "off".equalsIgnoreCase(SelfHealingPage.engine().config().get("healer.triage", "on"))) return;
            String url = pageOpen ? SelfHealingPage.currentUrl() : null;
            FailureTriage.Result result = FailureTriage.classify(error, record, HealingRecorder.eventsFor(test), url != null ? url : record.lastUrl);
            if (pageOpen) result.screenshot = SelfHealingPage.failureScreenshot(test);
            record.triage = result;
        } catch (RuntimeException ignored) {
            // the analysis must never hide the real failure
        }
    }

    @Override
    public void testSuccessful(ExtensionContext context) {
        HealingRecorder.finishTest(testId(context), "PASSED", null);
    }

    @Override
    public void testFailed(ExtensionContext context, Throwable cause) {
        analyse(testId(context), cause, false);   // failures outside the test method (e.g. @BeforeEach)
        HealingRecorder.finishTest(testId(context), "FAILED", cause.getClass().getSimpleName() + ": " + cause.getMessage());
        HealingRecorder.TestRecord record = HealingRecorder.test(testId(context));
        if (record != null && record.triage != null) System.out.println(FailureTriage.consoleLine(testId(context), record.triage));
    }

    @Override
    public void testAborted(ExtensionContext context, Throwable cause) {
        HealingRecorder.finishTest(testId(context), "SKIPPED", cause == null ? null : cause.getMessage());
    }

    @Override
    public void testDisabled(ExtensionContext context, Optional<String> reason) {
        HealingRecorder.startTest(testId(context), context.getRequiredTestClass().getSimpleName(),
                context.getRequiredTestMethod().getName(), context.getDisplayName());
        HealingRecorder.finishTest(testId(context), "SKIPPED", reason.orElse(null));
        HealingRecorder.endTest();
    }

    static String testId(ExtensionContext context) {
        return context.getRequiredTestClass().getSimpleName() + "." + context.getRequiredTestMethod().getName();
    }

    /** Claude's short explanation for each failed test of this JVM (healer.triage.llm, default = healer.llm.enabled). */
    static void explainFailures(HealerConfig config, List<HealingEvent> events) {
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
            Map<SelectorLint.Risk, Long> counts = new java.util.EnumMap<>(SelectorLint.Risk.class);
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

    /** Closed by JUnit when the root context ends, i.e. once after all tests. */
    @SuppressWarnings("deprecation")
    static final class ReportFlusher implements ExtensionContext.Store.CloseableResource, AutoCloseable {
        private boolean closed;

        @Override
        public synchronized void close() {
            if (closed) return;
            closed = true;
            HealerConfig config = SelfHealingPage.engine().config();
            Path reportDir = config.reportDir();
            List<HealingEvent> events = HealingRecorder.all();
            String runId = config.get("healer.runId", "");
            Map<String, Object> model;
            explainFailures(config, events);
            List<HealingEvent> runEvents = events;
            List<LocatorQuality.Entry> quality = LocatorQuality.all();
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
                PdfExporter.export(html, reportDir.resolve("healing-report.pdf"), config.get("healer.report.pdfBrowser", "msedge"))
                        .ifPresent(pdf -> System.out.println("[healer] PDF report:  " + pdf.toAbsolutePath()));
            }
        }
    }
}
