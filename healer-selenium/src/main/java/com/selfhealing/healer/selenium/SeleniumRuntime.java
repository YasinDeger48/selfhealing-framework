package com.selfhealing.healer.selenium;

import com.selfhealing.healer.core.HealerConfig;
import com.selfhealing.healer.core.HealingEngine;
import com.selfhealing.healer.core.HealingRuntime;

import java.nio.file.Path;
import java.util.Optional;

/** What the Selenium adapter offers the run-level features; registered for {@link HealingRuntime#discover()}. */
public class SeleniumRuntime implements HealingRuntime {

    @Override
    public HealingEngine engine() {
        return SelfHealingDriver.engine();
    }

    @Override
    public String failureScreenshot(String testId) {
        return SelfHealingDriver.failureScreenshot(testId);
    }

    @Override
    public String currentUrl() {
        return SelfHealingDriver.currentUrl();
    }

    @Override
    public Optional<Path> exportPdf(Path html, Path pdf, HealerConfig config) {
        return SeleniumPdf.print(html, pdf, config.get("healer.report.pdfBrowser", "msedge"));
    }
}
