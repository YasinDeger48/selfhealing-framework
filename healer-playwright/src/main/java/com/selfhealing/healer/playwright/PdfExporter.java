package com.selfhealing.healer.playwright;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.Margin;

import java.nio.file.Path;
import java.util.Optional;

/** Prints the HTML report to PDF with a headless Chromium-based browser (Edge by default). */
final class PdfExporter {

    private PdfExporter() {
    }

    /**
     * @param channel "msedge", "chrome" or "chromium" (Playwright's bundled browser)
     * @return the PDF path, or empty if it could not be produced (the HTML report is still there)
     */
    static Optional<Path> export(Path html, Path pdf, String channel) {
        try (Playwright playwright = Playwright.create()) {
            BrowserType.LaunchOptions options = new BrowserType.LaunchOptions().setHeadless(true);
            if (!"chromium".equals(channel)) options.setChannel(channel);
            try (Browser browser = playwright.chromium().launch(options)) {
                Page page = browser.newPage();
                page.navigate(html.toAbsolutePath().toUri().toString());
                page.waitForSelector("body[data-report-ready]");
                // Printed reports show every test expanded.
                page.evaluate("() => document.querySelectorAll('details').forEach(d => d.open = true)");
                page.pdf(new Page.PdfOptions()
                        .setPath(pdf)
                        .setFormat("A4")
                        .setPrintBackground(true)
                        .setMargin(new Margin().setTop("12mm").setBottom("12mm").setLeft("8mm").setRight("8mm")));
                return Optional.of(pdf);
            }
        } catch (Exception e) {
            System.out.println("[healer] PDF report skipped: " + e.getMessage());
            return Optional.empty();
        }
    }
}
