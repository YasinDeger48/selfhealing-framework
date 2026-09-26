package com.selfhealing.healer.playwright;

import com.selfhealing.healer.core.HealerConfig;
import com.selfhealing.healer.core.HealingEngine;
import com.selfhealing.healer.core.HealingRuntime;
import com.selfhealing.healer.core.junit.AbstractHealingExtension;

import java.nio.file.Path;
import java.util.Optional;

/**
 * JUnit 5 extension for Playwright tests: WARN blocks for healed tests, failure analysis, and the JSON, HTML and PDF
 * reports at the end of the run.
 *
 * <pre>{@code @ExtendWith(HealingExtension.class)}</pre>
 */
public class HealingExtension extends AbstractHealingExtension {

    private static final HealingRuntime RUNTIME = new HealingRuntime() {
        @Override public HealingEngine engine() { return SelfHealingPage.engine(); }
        @Override public String failureScreenshot(String testId) { return SelfHealingPage.failureScreenshot(testId); }
        @Override public String currentUrl() { return SelfHealingPage.currentUrl(); }
        @Override public Optional<Path> exportPdf(Path html, Path pdf, HealerConfig config) {
            return PdfExporter.export(html, pdf, config.get("healer.report.pdfBrowser", "msedge"));
        }
    };

    @Override
    protected HealingRuntime runtime() {
        return RUNTIME;
    }
}
