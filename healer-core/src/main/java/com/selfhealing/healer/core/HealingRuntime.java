package com.selfhealing.healer.core;

import java.nio.file.Path;
import java.util.Optional;

/** What a browser adapter (Playwright, Selenium ...) provides to the run-level features: reports, failure analysis. */
public interface HealingRuntime {

    /** The engine of this JVM (one per JVM: fingerprints and the healing cache are shared files). */
    HealingEngine engine();

    /** Screenshot of the running test's page, saved under the report directory; its report-relative path, or null. */
    default String failureScreenshot(String testId) {
        return null;
    }

    /** URL of the running test's page, or null. */
    default String currentUrl() {
        return null;
    }

    /** Prints the HTML report to PDF; empty if this adapter cannot. */
    default Optional<Path> exportPdf(Path html, Path pdf, HealerConfig config) {
        return Optional.empty();
    }
}
