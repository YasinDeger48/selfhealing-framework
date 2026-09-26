package com.selfhealing.healer.playwright;

import com.selfhealing.healer.core.HealerConfig;
import com.selfhealing.healer.core.HealingEngine;
import com.selfhealing.healer.core.HealingRuntime;

import java.nio.file.Path;
import java.util.Optional;

/** What the Playwright adapter offers the run-level features; registered for {@link HealingRuntime#discover()}. */
public class PlaywrightRuntime implements HealingRuntime {

    @Override
    public HealingEngine engine() {
        return SelfHealingPage.engine();
    }

    @Override
    public String failureScreenshot(String testId) {
        return SelfHealingPage.failureScreenshot(testId);
    }

    @Override
    public String currentUrl() {
        return SelfHealingPage.currentUrl();
    }

    @Override
    public Optional<Path> exportPdf(Path html, Path pdf, HealerConfig config) {
        return PdfExporter.export(html, pdf, config.get("healer.report.pdfBrowser", "msedge"));
    }
}
