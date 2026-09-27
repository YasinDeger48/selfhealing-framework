package com.selfhealing.healer.selenium;

import com.selfhealing.healer.core.HealerConfig;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.edge.EdgeDriver;
import org.openqa.selenium.edge.EdgeOptions;
import org.openqa.selenium.firefox.FirefoxDriver;
import org.openqa.selenium.firefox.FirefoxOptions;
import org.openqa.selenium.safari.SafariDriver;

import java.time.Duration;
import java.util.Locale;

/**
 * WebDriver setup from the settings, so test projects do not hard-code it (see {@link HealerConfig}):
 * <pre>
 * browser.name=msedge          # msedge | chrome | chromium | firefox | safari
 * browser.headless=true
 * browser.viewport=1280x900
 * browser.timeoutMs=15000      # page load and script timeout
 * app.baseUrl=https://my.app
 * </pre>
 * {@code browser.slowmo}, {@code browser.video} and {@code browser.trace} are Playwright features and are ignored here.
 * Selenium Manager provides the matching driver.
 * <pre>{@code
 * WebDriver driver = HealerDriver.create();
 * driver.get(HealerDriver.url("/login"));
 * }</pre>
 */
public final class HealerDriver {

    private static volatile boolean warned;

    private HealerDriver() {
    }

    private static HealerConfig config() {
        return SelfHealingDriver.engine().config();
    }

    public static WebDriver create() {
        HealerConfig c = config();
        String name = c.get("browser.name", "msedge").toLowerCase(Locale.ROOT);
        boolean headless = c.getBoolean("browser.headless", true);
        String[] size = c.get("browser.viewport", "1280x900").toLowerCase(Locale.ROOT).split("x");
        String window = "--window-size=" + size[0].trim() + "," + (size.length > 1 ? size[1].trim() : "900");
        if (!warned && (!"off".equalsIgnoreCase(c.get("browser.video", "off")) || !"off".equalsIgnoreCase(c.get("browser.trace", "off"))
                || c.getLong("browser.slowmo", 0) > 0)) {
            warned = true;
            System.out.println("[healer] browser.video / browser.trace / browser.slowmo are Playwright features - ignored with Selenium");
        }
        WebDriver driver = switch (name) {
            case "chrome", "chromium" -> {
                ChromeOptions o = new ChromeOptions().addArguments(window);
                if (headless) o.addArguments("--headless=new");
                yield new ChromeDriver(o);
            }
            case "firefox" -> {
                FirefoxOptions o = new FirefoxOptions()
                        .addArguments("--width=" + size[0].trim(), "--height=" + (size.length > 1 ? size[1].trim() : "900"));
                if (headless) o.addArguments("-headless");
                yield new FirefoxDriver(o);
            }
            case "safari", "webkit" -> new SafariDriver();
            default -> {
                EdgeOptions o = new EdgeOptions().addArguments(window);
                if (headless) o.addArguments("--headless=new");
                yield new EdgeDriver(o);
            }
        };
        Duration timeout = Duration.ofMillis(c.getLong("browser.timeoutMs", 15_000));
        driver.manage().timeouts().pageLoadTimeout(timeout).scriptTimeout(timeout);
        return driver;
    }

    /** app.baseUrl */
    public static String baseUrl() {
        return config().get("app.baseUrl", "");
    }

    /** app.baseUrl + path, with exactly one slash between them. */
    public static String url(String path) {
        String base = baseUrl();
        if (base.endsWith("/") && path.startsWith("/")) return base + path.substring(1);
        if (!base.endsWith("/") && !path.startsWith("/") && !base.isEmpty()) return base + "/" + path;
        return base + path;
    }
}
