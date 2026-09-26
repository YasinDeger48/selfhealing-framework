package com.selfhealing.healer.selenium;

import com.selfhealing.healer.core.CallSite;
import com.selfhealing.healer.core.HealerConfig;
import com.selfhealing.healer.core.HealingEngine;
import com.selfhealing.healer.core.HealingEvent;
import com.selfhealing.healer.core.HealingRecorder;
import com.selfhealing.healer.core.HealingSuggestion;
import com.selfhealing.healer.core.HealingTrace;
import com.selfhealing.healer.core.HeuristicMatcher;
import com.selfhealing.healer.core.LocatorHealerProvider;
import com.selfhealing.healer.core.LocatorQuality;
import com.selfhealing.healer.core.Messages;
import com.selfhealing.healer.core.SelectorLint;
import org.openqa.selenium.By;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.TakesScreenshot;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebDriverException;
import org.openqa.selenium.WebElement;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Entry point for Selenium tests: wraps a {@link WebDriver} and hands out self-healing elements.
 *
 * <pre>{@code
 * SelfHealingDriver healer = SelfHealingDriver.wrap(driver);
 * healer.element("LoginPage.username", By.id("login-username")).sendKeys("standard_user");
 * }</pre>
 */
public class SelfHealingDriver {

    private static volatile HealingEngine engine;
    private static final Set<String> CAPTURED = ConcurrentHashMap.newKeySet();
    private static final Map<String, HealingEvent> RUN_HEALS = new ConcurrentHashMap<>();
    private static final Map<String, CallSite> ORIGINS = new ConcurrentHashMap<>();
    /** The driver of the running test, for the screenshot at the moment of a failure. */
    private static final ThreadLocal<WebDriver> CURRENT = new ThreadLocal<>();

    private final WebDriver driver;
    private final SeleniumPageAdapter adapter;
    private final boolean verbose;
    private final boolean screenshots;

    private SelfHealingDriver(WebDriver driver) {
        this.driver = driver;
        this.adapter = new SeleniumPageAdapter(driver);
        HealerConfig config = engine().config();
        this.verbose = Boolean.parseBoolean(config.get("healer.verbose", "true"));
        this.screenshots = Boolean.parseBoolean(config.get("healer.screenshots", "true"));
    }

    public static SelfHealingDriver wrap(WebDriver driver) {
        CURRENT.set(driver);
        return new SelfHealingDriver(driver);
    }

    /** One engine per JVM: fingerprints and the healing cache are shared files. */
    public static HealingEngine engine() {
        if (engine == null) {
            synchronized (SelfHealingDriver.class) {
                if (engine == null) {
                    HealerConfig config = HealerConfig.load();
                    engine = new HealingEngine(config, LocatorHealerProvider.discover(config));
                }
            }
        }
        return engine;
    }

    /** Forgets the engine and the per-run state (tests of the framework itself). */
    static synchronized void resetForTests() {
        engine = null;
        CAPTURED.clear();
        RUN_HEALS.clear();
    }

    public WebDriver driver() {
        return driver;
    }

    /**
     * @param key stable logical name of the element, e.g. {@code LoginPage.username}
     * @param by  the locator as written in the page object (By.id, By.cssSelector, By.xpath, By.name ...)
     */
    public HealingElement element(String key, By by) {
        String origin = key + "\u0000" + by;
        if (!ORIGINS.containsKey(origin)) {
            CallSite caller = CallSite.find();
            if (caller != null) ORIGINS.putIfAbsent(origin, caller);
        }
        return new HealingElement(this, key, by);
    }

    /**
     * A plain-language step instead of a locator: {@code healer.find("Login.submit", "the Sign in button").click()}.
     * Matched locally (free) or by Claude; the selector and fingerprint are saved for later runs. Look-alikes the
     * description cannot tell apart fail the step instead of guessing.
     */
    public HealingElement find(String key, String description) {
        return element(key, new IntentBy(description));
    }

    /** Navigates and records it as a test step. */
    public void navigate(String url) {
        HealingRecorder.Step step = HealingRecorder.step(Messages.get("step.page"), "navigate", url);
        try {
            driver.get(url);
            HealingRecorder.pageUrl(url);
        } catch (RuntimeException e) {
            step.status = "FAILED";
            step.error = e.getMessage();
            throw e;
        }
    }

    /** Finds the element, healing the locator if it no longer matches. */
    WebElement resolve(String key, By by) {
        HealingEngine eng = engine();
        HealerConfig config = eng.config();
        if (by instanceof IntentBy intent) return resolveIntent(key, intent, eng, config);
        if (config.mode() == HealerConfig.Mode.OFF) return driver.findElement(by);
        SeleniumSelectors.Parsed parsed = SeleniumSelectors.parse(by);
        String selector = parsed.selector();
        LocatorQuality.Entry quality = observeQuality(key, by, parsed, config);

        HealingEvent previous = RUN_HEALS.get(key);
        String runHeal = previous != null && selector.equals(previous.originalSelector) ? previous.healedSelector : null;
        String cachedHeal = eng.cachedHeal(key, selector).map(c -> c.healedSelector).orElse(null);

        // One wait for whichever appears first: the original, a heal from this run, or one from an earlier run.
        long started = System.currentTimeMillis();
        Duration implicit = driver.manage().timeouts().getImplicitWaitTimeout();
        driver.manage().timeouts().implicitlyWait(Duration.ZERO);   // probing must not wait per findElements call
        try {
            long end = started + config.probeTimeoutMs();
            while (true) {
                List<WebElement> found = findQuietly(by);
                if (!found.isEmpty()) {
                    if (CAPTURED.add(key) && found.size() == 1) eng.remember(key, selector, adapter.url(), adapter.snapshot(found.get(0)));
                    suggestStableSelector(quality, found);
                    return found.get(0);
                }
                if (runHeal != null) {
                    List<WebElement> healed = findQuietly(SeleniumSelectors.toBy(runHeal));
                    if (!healed.isEmpty()) {
                        recordOnce(previous);
                        return healed.get(0);
                    }
                }
                if (cachedHeal != null && !findQuietly(SeleniumSelectors.toBy(cachedHeal)).isEmpty()) break;
                if (System.currentTimeMillis() >= end) break;
                sleep(100);
            }
        } finally {
            driver.manage().timeouts().implicitlyWait(implicit);
        }
        if (quality != null) LocatorQuality.markBroken(quality);

        long probeWait = System.currentTimeMillis() - started;
        HealingTrace trace = new HealingTrace(verbose ? HealingTrace.console() : null);
        trace.broken(key, selector, probeWait);
        long healStart = System.currentTimeMillis();
        HealingEngine.Result result = eng.heal(key, selector, adapter, trace);

        HealingEvent event = toEvent(key, selector, parsed.literal(), by, result, eng);
        event.trace = trace.lines();
        event.candidates = trace.topCandidates().stream()
                .map(c -> new HealingEvent.Candidate(c.score(), c.candidate().describe(), c.candidate().getSelector()))
                .toList();
        event.probeWaitMs = probeWait;
        event.healDurationMs = System.currentTimeMillis() - healStart;
        if (!result.healed()) {
            HealingRecorder.record(event);
            throw new HealingFailedException(event.summaryLine());
        }
        if (config.mode() == HealerConfig.Mode.SUGGEST) event.status = HealingEvent.Status.SUGGESTED;
        HealingRecorder.record(event);
        if (screenshots) event.screenshot = capture(event);
        if (config.mode() == HealerConfig.Mode.SUGGEST) {
            throw new HealingFailedException("Locator broken (suggest mode): " + event.summaryLine());
        }
        RUN_HEALS.put(key, event);
        return driver.findElement(SeleniumSelectors.toBy(result.suggestion().selector()));
    }

    /** Resolves a plain-language step; reported as an info step, never as a WARN. */
    private WebElement resolveIntent(String key, IntentBy intent, HealingEngine eng, HealerConfig config) {
        String selectorKey = HealingEngine.INTENT + intent.description;
        Duration implicit = driver.manage().timeouts().getImplicitWaitTimeout();
        driver.manage().timeouts().implicitlyWait(Duration.ZERO);
        try {
            var known = eng.cachedHeal(key, selectorKey);
            if (known.isPresent()) {
                long end = System.currentTimeMillis() + config.probeTimeoutMs();
                while (true) {
                    List<WebElement> found = findQuietly(SeleniumSelectors.toBy(known.get().healedSelector));
                    if (found.size() == 1) return found.get(0);
                    if (System.currentTimeMillis() >= end) break;
                    sleep(100);
                }
            }
            HealingTrace trace = new HealingTrace(verbose ? HealingTrace.console() : null).forIntent();
            trace.title(key);
            trace.note("info", "trace.intent.start", intent.description);
            long started = System.currentTimeMillis();
            HealingEngine.Result result = eng.resolveIntent(key, intent.description, adapter, trace);
            HealingEvent event = toEvent(key, selectorKey, null, intent, result, eng);
            event.kind = "intent";
            event.healDurationMs = System.currentTimeMillis() - started;
            if (result.healed()) {
                trace.note("ok", "trace.intent.found", Messages.get("source." + result.suggestion().source()),
                        result.suggestion().confidence(), result.suggestion().selector(),
                        com.selfhealing.healer.core.LlmPricing.format(event.llmCostUsd));
            } else {
                trace.note("error", "trace.intent.notFound", result.failureReason());
            }
            event.trace = trace.lines();
            if (!result.healed()) {
                HealingRecorder.record(event);
                throw new HealingFailedException("Step \"" + intent.description + "\" (" + key + "): " + result.failureReason());
            }
            if (result.suggestion().source() != HealingSuggestion.Source.CACHE) HealingRecorder.record(event);
            return driver.findElement(SeleniumSelectors.toBy(result.suggestion().selector()));
        } finally {
            driver.manage().timeouts().implicitlyWait(implicit);
        }
    }

    /** Before an interaction: closes a layer (cookie banner, modal) that covers the element - see {@link SeleniumPopups}. */
    void clearObstruction(String key, By by, WebElement target) {
        HealerConfig config = engine().config();
        if (config.mode() == HealerConfig.Mode.OFF || "off".equalsIgnoreCase(config.get("healer.popups", "auto"))) return;
        SeleniumPopups.clear(adapter, key, SeleniumSelectors.parse(by).selector(), target, verbose);
    }

    private LocatorQuality.Entry observeQuality(String key, By by, SeleniumSelectors.Parsed parsed, HealerConfig config) {
        if ("off".equalsIgnoreCase(config.get("healer.lint", "on"))) return null;
        CallSite origin = ORIGINS.get(key + "\u0000" + by);
        LocatorQuality.Entry e = LocatorQuality.observe(key, parsed.selector(), origin == null ? null : origin.file(),
                origin == null ? 0 : origin.line());
        if (e.sourceLiteral == null && parsed.literal() != null && !parsed.literal().equals(parsed.selector())) e.sourceLiteral = parsed.literal();
        return e;
    }

    private void suggestStableSelector(LocatorQuality.Entry quality, List<WebElement> found) {
        if (quality == null || quality.suggestionChecked || quality.risk == SelectorLint.Risk.OK) return;
        try {
            LocatorQuality.suggest(quality, found.size() == 1 ? adapter.uniqueSelector(found.get(0)) : null);
        } catch (RuntimeException e) {
            LocatorQuality.suggest(quality, null);
        }
    }

    private HealingEvent toEvent(String key, String selector, String literal, By by, HealingEngine.Result result, HealingEngine eng) {
        HealingEvent e = new HealingEvent();
        e.key = key;
        e.originalSelector = selector;
        if (literal != null && !literal.equals(selector)) e.sourceLiteral = literal;
        e.pageUrl = adapter.url();
        CallSite origin = ORIGINS.get(key + "\u0000" + by);
        if (origin != null) {
            e.sourceFile = origin.file();
            e.sourceLine = origin.line();
        }
        eng.fingerprint(key).map(f -> f.getElement().describe()).ifPresent(d -> e.originalElement = d);
        if (result.healed()) {
            HealingSuggestion s = result.suggestion();
            e.status = HealingEvent.Status.HEALED;
            e.healedSelector = s.selector();
            e.source = s.source();
            e.confidence = s.confidence();
            e.reasoning = s.reasoning();
            e.changes = s.changes();
            if (s.element() != null) e.healedElement = s.element().describe();
            if (s.usage() != null) {
                e.llmModel = s.usage().model();
                e.llmInputTokens = s.usage().inputTokens();
                e.llmOutputTokens = s.usage().outputTokens();
                e.llmCostUsd = s.usage().costUsd();
            }
        } else {
            e.status = HealingEvent.Status.FAILED;
            e.reasoning = result.failureReason();
            if (result.llmUsage() != null) {
                e.llmModel = result.llmUsage().model();
                e.llmInputTokens = result.llmUsage().inputTokens();
                e.llmOutputTokens = result.llmUsage().outputTokens();
                e.llmCostUsd = result.llmUsage().costUsd();
            }
            List<HeuristicMatcher.Scored> top = result.ranked().stream().limit(3).toList();
            e.topCandidates = top.stream()
                    .map(s -> String.format("%.2f %s -> %s", s.score(), s.candidate().describe(), s.candidate().getSelector()))
                    .toList();
        }
        return e;
    }

    /** A heal made earlier in this run, reported once per test as reused (no new work, no cost). */
    private static void recordOnce(HealingEvent template) {
        String test = HealingRecorder.currentTest();
        boolean seen = HealingRecorder.eventsFor(test).stream().anyMatch(e -> e.kind == null && e.key.equals(template.key));
        if (seen) return;
        HealingEvent e = new HealingEvent();
        e.key = template.key;
        e.status = template.status;
        e.originalSelector = template.originalSelector;
        e.healedSelector = template.healedSelector;
        e.sourceFile = template.sourceFile;
        e.sourceLine = template.sourceLine;
        e.sourceLiteral = template.sourceLiteral;
        e.source = template.source;
        e.confidence = template.confidence;
        e.reasoning = template.reasoning;
        e.pageUrl = template.pageUrl;
        e.originalElement = template.originalElement;
        e.healedElement = template.healedElement;
        e.changes = template.changes;
        e.screenshot = template.screenshot;
        e.llmModel = template.llmModel;
        e.test = test;
        e.reused = true;
        HealingRecorder.record(e);
    }

    /** Screenshot with the healed element outlined in green; stored next to the report. */
    private String capture(HealingEvent event) {
        try {
            Path dir = engine().config().reportDir().resolve("screenshots");
            Files.createDirectories(dir);
            WebElement el = driver.findElement(SeleniumSelectors.toBy(event.healedSelector));
            adapter.script("arguments[0].scrollIntoView({block:'center'});"
                    + "arguments[0].dataset.healerOutline = arguments[0].style.outline;"
                    + "arguments[0].style.outline = '3px solid #2f9e44'; arguments[0].style.outlineOffset = '2px';", el);
            byte[] png = ((TakesScreenshot) driver).getScreenshotAs(OutputType.BYTES);
            adapter.script("arguments[0].style.outline = arguments[0].dataset.healerOutline || '';"
                    + "arguments[0].style.outlineOffset = ''; delete arguments[0].dataset.healerOutline;", el);
            String name = "heal-" + ProcessHandle.current().pid() + "-" + event.id + ".png";
            Files.write(dir.resolve(name), png);
            return "screenshots/" + name;
        } catch (Exception e) {
            return null;   // best effort
        }
    }

    /** Screenshot of the running test's browser, for the failure analysis; report-relative path. */
    static String failureScreenshot(String testId) {
        WebDriver d = CURRENT.get();
        if (!(d instanceof TakesScreenshot shooter)) return null;
        try {
            Path dir = engine().config().reportDir().resolve("screenshots");
            Files.createDirectories(dir);
            String name = "fail-" + ProcessHandle.current().pid() + "-" + testId.replaceAll("[^A-Za-z0-9._-]", "_") + ".png";
            Files.write(dir.resolve(name), shooter.getScreenshotAs(OutputType.BYTES));
            return "screenshots/" + name;
        } catch (Exception e) {
            return null;
        }
    }

    static String currentUrl() {
        WebDriver d = CURRENT.get();
        try {
            return d == null ? null : d.getCurrentUrl();
        } catch (WebDriverException e) {
            return null;
        }
    }

    private List<WebElement> findQuietly(By by) {
        try {
            return driver.findElements(by);
        } catch (WebDriverException e) {
            return List.of();   // invalid selector counts as broken
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
