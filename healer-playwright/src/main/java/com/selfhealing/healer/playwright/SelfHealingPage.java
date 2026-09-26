package com.selfhealing.healer.playwright;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.TimeoutError;
import com.microsoft.playwright.options.WaitForSelectorState;
import com.selfhealing.healer.core.ElementSnapshot;
import com.selfhealing.healer.core.HealerConfig;
import com.selfhealing.healer.core.HealingEngine;
import com.selfhealing.healer.core.HealingEvent;
import com.selfhealing.healer.core.HealingListener;
import com.selfhealing.healer.core.HealingRecorder;
import com.selfhealing.healer.core.HealingSuggestion;
import com.selfhealing.healer.core.HealingTrace;
import com.selfhealing.healer.core.HeuristicMatcher;
import com.selfhealing.healer.core.LocatorHealerProvider;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Entry point for tests: wraps a Playwright {@link Page} and hands out self-healing locators.
 *
 * <pre>{@code
 * SelfHealingPage healer = SelfHealingPage.wrap(page);
 * healer.locator("LoginPage.username", "#login-username").fill("standard_user");
 * }</pre>
 */
public class SelfHealingPage {

    private static volatile HealingEngine engine;
    /** Keys whose fingerprint was refreshed during this run. */
    private static final Set<String> CAPTURED = ConcurrentHashMap.newKeySet();
    /** Heals made during this run, reused without re-probing the broken selector. */
    private static final Map<String, HealingEvent> RUN_HEALS = new ConcurrentHashMap<>();

    private final Page page;
    private final PlaywrightPageAdapter adapter;
    private final PageVisualizer visualizer;
    private final boolean verbose;
    private final boolean screenshots;

    private SelfHealingPage(Page page, String frameSelector) {
        this.page = page;
        this.adapter = new PlaywrightPageAdapter(page, frameSelector);
        HealerConfig config = engine().config();
        this.verbose = Boolean.parseBoolean(config.get("healer.verbose", "true"));
        this.screenshots = Boolean.parseBoolean(config.get("healer.screenshots", "true"));
        this.visualizer = Boolean.parseBoolean(config.get("healer.visual", "false"))
                ? new PageVisualizer(adapter, Long.parseLong(config.get("healer.visual.pauseMs", "1200")))
                : null;
    }

    public static SelfHealingPage wrap(Page page) {
        return new SelfHealingPage(page, null);
    }

    /**
     * Self-healing locators inside an iframe of this page:
     * {@code healer.frame("iframe#payment").locator("Payment.cardNumber", "#card")}.
     * The iframe selector itself is a plain Playwright selector (not healed).
     */
    public SelfHealingPage frame(String iframeSelector) {
        return new SelfHealingPage(page, iframeSelector);
    }

    /** Forgets the engine and the per-run state (tests of the framework itself). */
    static synchronized void resetForTests() {
        engine = null;
        CAPTURED.clear();
        RUN_HEALS.clear();
    }

    /** One engine per JVM: fingerprints and the healing cache are shared files. */
    public static HealingEngine engine() {
        if (engine == null) {
            synchronized (SelfHealingPage.class) {
                if (engine == null) {
                    HealerConfig config = HealerConfig.load();
                    engine = new HealingEngine(config, LocatorHealerProvider.discover(config));
                }
            }
        }
        return engine;
    }

    public Page page() {
        return page;
    }

    /**
     * @param key      stable logical name of the element, e.g. {@code LoginPage.username};
     *                 fingerprints and heals are stored under it
     * @param selector the Playwright selector as written in the page object
     */
    public HealingLocator locator(String key, String selector) {
        return new HealingLocator(this, key, selector);
    }

    /** Navigates and records it as a test step. */
    public void navigate(String url) {
        HealingRecorder.Step step = HealingRecorder.step(com.selfhealing.healer.core.Messages.get("step.page"), "navigate", url);
        try {
            page.navigate(url);
        } catch (RuntimeException e) {
            step.status = "FAILED";
            step.error = e.getMessage();
            throw e;
        }
    }

    /** Returns a Playwright locator for the element, healing the selector if it no longer matches. */
    Locator resolve(String key, String selector) {
        long started = System.currentTimeMillis();
        HealingEngine eng = engine();
        HealerConfig config = eng.config();
        Locator original = adapter.locator(selector);
        if (config.mode() == HealerConfig.Mode.OFF) return original;

        // Known replacements: a heal from earlier in this run, and one cached by an earlier run.
        HealingEvent previous = RUN_HEALS.get(key);
        String runHeal = previous != null && selector.equals(previous.originalSelector) ? previous.healedSelector : null;
        String cachedHeal = eng.cachedHeal(key, selector).map(c -> c.healedSelector).orElse(null);

        // One wait for whichever renders first - the original or a known replacement - so a broken selector
        // costs a single probe timeout. If the original matches again (site fixed), it wins and warnings stop.
        Locator any = original;
        if (runHeal != null) any = any.or(adapter.locator(runHeal));
        if (cachedHeal != null && !cachedHeal.equals(runHeal)) any = any.or(adapter.locator(cachedHeal));
        boolean rendered = isAttached(any, config.probeTimeoutMs());

        if (rendered && safeCount(original) > 0) {
            if (CAPTURED.add(key) && original.count() == 1) {
                eng.remember(key, selector, adapter.url(), PlaywrightPageAdapter.snapshot(original));
            }
            return original;
        }
        if (rendered && runHeal != null && safeCount(adapter.locator(runHeal)) > 0) {
            recordOnce(previous);
            return adapter.locator(runHeal);
        }
        // Otherwise heal: the engine uses the cached heal if it matches, else scans the page.

        long probeWait = System.currentTimeMillis() - started;
        HealingTrace trace = new HealingTrace(verbose ? HealingTrace.console() : null);
        HealingListener listener = visualizer == null ? trace : HealingListener.of(List.of(trace, visualizer));
        listener.broken(key, selector, probeWait);
        long healStart = System.currentTimeMillis();
        HealingEngine.Result result = eng.heal(key, selector, adapter, listener);

        HealingEvent event = toEvent(key, selector, result, eng);
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
        return adapter.locator(result.suggestion().selector());
    }

    /** Screenshot with the healed element framed in green; stored next to the report. */
    private String capture(HealingEvent event) {
        try {
            Path dir = engine().config().reportDir().resolve("screenshots");
            Files.createDirectories(dir);
            String name = "heal-" + ProcessHandle.current().pid() + "-" + event.id + ".jpg";
            String label = "HEALED (" + com.selfhealing.healer.core.Messages.get("source." + event.source)
                    + String.format(java.util.Locale.ROOT, " %.2f)", event.confidence);
            PageVisualizer.highlight(adapter, event.healedSelector, label);
            Page.ScreenshotOptions options = new Page.ScreenshotOptions().setPath(dir.resolve(name))
                    .setType(com.microsoft.playwright.options.ScreenshotType.JPEG).setQuality(75);
            // Crop around the element so it is readable in the report.
            com.microsoft.playwright.options.BoundingBox box = adapter.locator(event.healedSelector).first().boundingBox();
            com.microsoft.playwright.options.ViewportSize vp = page.viewportSize();
            if (box != null && vp != null) {
                double x = Math.max(0, box.x - 300);
                double y = Math.max(0, box.y - 190);
                double w = Math.min(vp.width - x, box.width + 600);
                double h = Math.min(vp.height - y, box.height + 380);
                if (w > 50 && h > 50) options.setClip(x, y, w, h);
            }
            page.screenshot(options);
            PageVisualizer.clear(adapter);
            return "screenshots/" + name;
        } catch (Exception e) {
            return null; // best effort: never fail a test because of a screenshot
        }
    }

    private static boolean isAttached(Locator locator, long timeoutMs) {
        try {
            locator.first().waitFor(new Locator.WaitForOptions()
                    .setState(WaitForSelectorState.ATTACHED).setTimeout(timeoutMs));
            return true;
        } catch (TimeoutError e) {
            return false;
        } catch (com.microsoft.playwright.PlaywrightException e) {
            return false; // invalid selector syntax counts as broken
        }
    }

    private static int safeCount(Locator locator) {
        try {
            return locator.count();
        } catch (com.microsoft.playwright.PlaywrightException e) {
            return 0;
        }
    }

    /** A heal made earlier in this run, reported once per test as reused (no new work, no cost). */
    private static void recordOnce(HealingEvent template) {
        String test = HealingRecorder.currentTest();
        boolean seen = HealingRecorder.eventsFor(test).stream().anyMatch(e -> e.key.equals(template.key));
        if (seen) return;
        HealingEvent e = copy(template);
        e.test = test;
        e.reused = true;
        e.llmCostUsd = 0;
        e.llmInputTokens = 0;
        e.llmOutputTokens = 0;
        HealingRecorder.record(e);
    }

    private HealingEvent toEvent(String key, String selector, HealingEngine.Result result, HealingEngine eng) {
        HealingEvent e = new HealingEvent();
        e.key = key;
        e.originalSelector = selector;
        e.pageUrl = adapter.url();
        eng.fingerprint(key).map(f -> f.getElement().describe()).ifPresent(d -> e.originalElement = d);
        if (result.healed()) {
            HealingSuggestion s = result.suggestion();
            e.status = HealingEvent.Status.HEALED;
            e.healedSelector = s.selector();
            e.source = s.source();
            e.confidence = s.confidence();
            e.reasoning = s.reasoning();
            e.changes = s.changes();
            ElementSnapshot el = s.element();
            if (el != null) e.healedElement = el.describe();
            if (s.usage() != null) {
                e.llmModel = s.usage().model();
                e.llmInputTokens = s.usage().inputTokens();
                e.llmOutputTokens = s.usage().outputTokens();
                e.llmCostUsd = s.usage().costUsd();
            }
        } else {
            e.status = HealingEvent.Status.FAILED;
            e.reasoning = result.failureReason();
            if (result.llmUsage() != null) {   // Claude was asked and found nothing - still paid for
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

    private static HealingEvent copy(HealingEvent t) {
        HealingEvent e = new HealingEvent();
        e.key = t.key;
        e.status = t.status;
        e.originalSelector = t.originalSelector;
        e.healedSelector = t.healedSelector;
        e.source = t.source;
        e.confidence = t.confidence;
        e.reasoning = t.reasoning;
        e.pageUrl = t.pageUrl;
        e.originalElement = t.originalElement;
        e.healedElement = t.healedElement;
        e.changes = t.changes;
        e.topCandidates = t.topCandidates;
        e.screenshot = t.screenshot;
        e.llmModel = t.llmModel;
        e.llmInputTokens = t.llmInputTokens;
        e.llmOutputTokens = t.llmOutputTokens;
        e.llmCostUsd = t.llmCostUsd;
        return e;
    }
}
