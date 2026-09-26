package com.selfhealing.healer.playwright;

import com.selfhealing.healer.core.HealerConfig;
import com.selfhealing.healer.core.HealingEvent;
import com.selfhealing.healer.core.HealingRecorder;
import com.selfhealing.healer.core.LlmPricing;
import com.selfhealing.healer.core.ReportParts;
import com.selfhealing.healer.core.ReportWriter;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestWatcher;

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
public class HealingExtension implements BeforeEachCallback, AfterEachCallback, TestWatcher {

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

    @Override
    public void testSuccessful(ExtensionContext context) {
        HealingRecorder.finishTest(testId(context), "PASSED", null);
    }

    @Override
    public void testFailed(ExtensionContext context, Throwable cause) {
        HealingRecorder.finishTest(testId(context), "FAILED", cause.getClass().getSimpleName() + ": " + cause.getMessage());
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
            if (runId.isBlank()) {
                model = ReportWriter.model(events, HealingRecorder.tests(), config);
            } else {
                // Several JVMs (forkCount > 1): every JVM adds its part; the last one writes the complete report.
                ReportParts.Merged merged = ReportParts.writeAndMerge(reportDir, runId, events, HealingRecorder.tests(),
                        HealingRecorder.runStartedAt());
                model = ReportWriter.model(merged.events(), merged.tests(), config, merged.runStartedAt());
            }
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
