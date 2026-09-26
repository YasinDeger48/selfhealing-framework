package com.selfhealing.healer.selenium;

import com.selfhealing.healer.core.HealerConfig;
import com.selfhealing.healer.core.HealingEngine;
import com.selfhealing.healer.core.HealingRuntime;
import com.selfhealing.healer.core.junit.AbstractHealingExtension;
import org.openqa.selenium.PrintsPage;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.edge.EdgeDriver;
import org.openqa.selenium.edge.EdgeOptions;
import org.openqa.selenium.print.PageMargin;
import org.openqa.selenium.print.PrintOptions;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;

/**
 * JUnit 5 extension for Selenium tests: WARN blocks for healed tests, failure analysis, and the JSON, HTML and PDF
 * reports at the end of the run.
 *
 * <pre>{@code @ExtendWith(SeleniumHealingExtension.class)}</pre>
 */
public class SeleniumHealingExtension extends AbstractHealingExtension {

    private static final HealingRuntime RUNTIME = new HealingRuntime() {
        @Override public HealingEngine engine() { return SelfHealingDriver.engine(); }
        @Override public String failureScreenshot(String testId) { return SelfHealingDriver.failureScreenshot(testId); }
        @Override public String currentUrl() { return SelfHealingDriver.currentUrl(); }
        @Override public Optional<Path> exportPdf(Path html, Path pdf, HealerConfig config) {
            return printToPdf(html, pdf, config.get("healer.report.pdfBrowser", "msedge"));
        }
    };

    @Override
    protected HealingRuntime runtime() {
        return RUNTIME;
    }

    /** Prints the HTML report with a headless Edge or Chrome (Selenium Manager provides the driver). */
    static Optional<Path> printToPdf(Path html, Path pdf, String browser) {
        WebDriver driver = null;
        try {
            if (browser.startsWith("chrome")) {
                driver = new ChromeDriver(new ChromeOptions().addArguments("--headless=new"));
            } else {
                driver = new EdgeDriver(new EdgeOptions().addArguments("--headless=new"));
            }
            driver.get(html.toAbsolutePath().toUri().toString());
            long end = System.currentTimeMillis() + Duration.ofSeconds(20).toMillis();
            while (driver.findElements(org.openqa.selenium.By.cssSelector("body[data-report-ready]")).isEmpty()
                    && System.currentTimeMillis() < end) {
                Thread.sleep(100);
            }
            ((org.openqa.selenium.JavascriptExecutor) driver).executeScript(
                    "document.querySelectorAll('details').forEach(d => d.open = true)");
            PrintOptions options = new PrintOptions();
            options.setBackground(true);
            options.setPageMargin(new PageMargin(1.2, 1.2, 0.8, 0.8));
            String base64 = ((PrintsPage) driver).print(options).getContent();
            Files.write(pdf, Base64.getDecoder().decode(base64));
            return Optional.of(pdf);
        } catch (Exception e) {
            System.out.println("[healer] PDF report skipped: " + e.getMessage());
            return Optional.empty();
        } finally {
            if (driver != null) driver.quit();
        }
    }
}
