package com.selfhealing.healer.playwright;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Playwright;
import org.junit.jupiter.api.Test;

import java.net.URISyntaxException;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Accuracy guard: the benchmark on three generic pages (local heuristic only, fixed seed). A change that makes healing
 * pick a wrong element, or drops accuracy below the measured level, fails the build.
 */
class HealingBenchmarkTest {

    /** Minimum accuracy per level (measured values minus a small margin). Wrong elements must always be zero. */
    private static final Map<String, Double> MIN_ACCURACY = Map.of(
            "low", 0.95, "medium", 0.80, "high", 0.40, "removed", 1.0);

    @Test
    void healsAccuratelyAndNeverPicksAWrongElement() throws Exception {
        List<String> pages = List.of(page("login.html"), page("shop.html"), page("checkout.html"));
        try (Playwright pw = Playwright.create()) {
            BrowserType.LaunchOptions launch = new BrowserType.LaunchOptions().setHeadless(true);
            String channel = System.getProperty("browser.channel", "msedge");
            if (!"chromium".equals(channel)) launch.setChannel(channel);
            Browser browser = pw.chromium().launch(launch);

            HealingBenchmark.Options options = new HealingBenchmark.Options(List.of("low", "medium", "high", "extreme", "removed"),
                    false, 42, 40, null, new java.util.Properties());
            List<HealingBenchmark.Result> results = HealingBenchmark.run(browser, pages, options);
            String table = HealingBenchmark.markdown(results, false);
            System.out.println(table);
            // CI shows this file on the run's summary page
            java.nio.file.Path out = java.nio.file.Path.of("target", "healer-benchmark", "benchmark.md");
            java.nio.file.Files.createDirectories(out.getParent());
            java.nio.file.Files.writeString(out, "## Healing accuracy (bundled pages, local heuristic)\n\n" + table);

            HealingBenchmark.totals(results).forEach((level, r) -> {
                assertEquals(0, r.wrong(), level + ": a heal picked a wrong element");
                Double min = MIN_ACCURACY.get(level);
                if (min != null) {
                    assertTrue(r.accuracy() >= min, String.format("%s: accuracy %.1f%% < %.0f%%", level, r.accuracy() * 100, min * 100));
                }
            });
        }
    }

    private static String page(String name) throws URISyntaxException {
        return Objects.requireNonNull(HealingBenchmarkTest.class.getResource("/bench/" + name)).toURI().toString();
    }
}
