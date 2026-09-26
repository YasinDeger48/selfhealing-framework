package com.selfhealing.healer.core;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.ServiceLoader;

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

    /**
     * The adapter(s) on the classpath (healer-playwright, healer-selenium register themselves). With both, screenshots
     * and URLs come from whichever has a page open for the running test. Used by the TestNG and Cucumber integrations.
     */
    static HealingRuntime discover() {
        List<HealingRuntime> found = ServiceLoader.load(HealingRuntime.class).stream().map(ServiceLoader.Provider::get).toList();
        if (found.isEmpty()) {
            throw new IllegalStateException("No healer adapter on the classpath: add healer-playwright or healer-selenium");
        }
        if (found.size() == 1) return found.get(0);
        return new HealingRuntime() {
            @Override public HealingEngine engine() { return found.get(0).engine(); }
            @Override public String failureScreenshot(String testId) {
                return found.stream().map(r -> r.failureScreenshot(testId)).filter(Objects::nonNull).findFirst().orElse(null);
            }
            @Override public String currentUrl() {
                return found.stream().map(HealingRuntime::currentUrl).filter(Objects::nonNull).findFirst().orElse(null);
            }
            @Override public Optional<Path> exportPdf(Path html, Path pdf, HealerConfig config) {
                return found.stream().map(r -> r.exportPdf(html, pdf, config)).flatMap(Optional::stream).findFirst();
            }
        };
    }
}
