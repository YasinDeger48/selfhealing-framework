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
import com.selfhealing.healer.core.LocatorQuality;
import com.selfhealing.healer.core.SelectorLint;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
    /** Where each locator was declared (key + selector -> source path and line), for the code-fix suggestions. */
    private static final Map<String, com.selfhealing.healer.core.CallSite> ORIGINS = new ConcurrentHashMap<>();

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
        this.screenshots = config.screenshots() == HealerConfig.Screenshots.ALL;   // heal screenshots
        this.visualizer = Boolean.parseBoolean(config.get("healer.visual", "false"))
                ? new PageVisualizer(adapter, Long.parseLong(config.get("healer.visual.pauseMs", "1200")))
                : null;
    }

    public static SelfHealingPage wrap(Page page) {
        watch(page);
        return new SelfHealingPage(page, null);
    }

    /** Pages whose console and network errors are collected for the failure analysis. */
    private static final Set<Page> WATCHED = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
    /** The page of the running test, for the screenshot at the moment of a failure. */
    private static final ThreadLocal<Page> CURRENT_PAGE = new ThreadLocal<>();

    private static void watch(Page page) {
        CURRENT_PAGE.set(page);
        synchronized (WATCHED) {
            if (!WATCHED.add(page)) return;
        }
        page.onConsoleMessage(m -> {
            if ("error".equals(m.type())) HealingRecorder.consoleError(truncate(m.text(), 300));
        });
        page.onPageError(err -> HealingRecorder.consoleError("Uncaught: " + truncate(err, 300)));
        page.onResponse(r -> {
            String type = r.request().resourceType();
            if (r.status() >= 500 || (r.status() >= 400 && ("fetch".equals(type) || "xhr".equals(type) || "document".equals(type)))) {
                HealingRecorder.networkError("HTTP " + r.status() + " " + r.request().method() + " " + truncate(r.url(), 200));
            }
        });
        page.onRequestFailed(r -> {
            String failure = r.failure();
            if (failure != null && failure.contains("ERR_ABORTED")) return;   // cancelled by a navigation - normal
            HealingRecorder.networkError("FAILED " + r.method() + " " + truncate(r.url(), 200) + " - " + failure);
        });
        page.onFrameNavigated(f -> {
            if (f == page.mainFrame()) HealingRecorder.pageUrl(f.url());
        });
    }

    /** Screenshot of the running test's page (still open when the test method has just failed); report-relative path. */
    static String failureScreenshot(String testId) {
        Page page = CURRENT_PAGE.get();
        if (page == null || page.isClosed()) return null;
        try {
            Path dir = engine().config().reportDir().resolve("screenshots");
            Files.createDirectories(dir);
            String name = "fail-" + ProcessHandle.current().pid() + "-" + testId.replaceAll("[^A-Za-z0-9._-]", "_") + ".jpg";
            page.screenshot(new Page.ScreenshotOptions().setPath(dir.resolve(name))
                    .setType(com.microsoft.playwright.options.ScreenshotType.JPEG).setQuality(70));
            return "screenshots/" + name;
        } catch (Exception e) {
            return null;
        }
    }

    static String currentUrl() {
        Page page = CURRENT_PAGE.get();
        try {
            return page == null || page.isClosed() ? null : page.url();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max) + "...";
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
        String origin = key + "\u0000" + selector;
        if (!ORIGINS.containsKey(origin)) {
            com.selfhealing.healer.core.CallSite caller = com.selfhealing.healer.core.CallSite.find();   // outside computeIfAbsent: its lambda runs inside JDK frames
            if (caller != null) ORIGINS.putIfAbsent(origin, caller);
        }
        return new HealingLocator(this, key, selector);
    }

    /**
     * A plain-language step instead of a selector: {@code healer.find("Login.submit", "the Sign in button").click()}.
     * The element is matched locally (free) or by Claude, then its selector and fingerprint are saved, so later runs
     * cost nothing and a changed element is healed like any other. Look-alikes the description cannot tell apart fail
     * the step instead of guessing.
     *
     * @param key         stable logical name of the element
     * @param description what a person would call it, in any language
     */
    public HealingLocator find(String key, String description) {
        return locator(key, HealingEngine.INTENT + description);
    }

    /** Resolves a plain-language step (see {@link #find}); reported as an info step, never as a WARN. */
    private Locator resolveIntent(String key, String selectorKey, HealingEngine eng, HealerConfig config) {
        String description = selectorKey.substring(HealingEngine.INTENT.length());
        Optional<HealingEngine.CachedHeal> known = eng.cachedHeal(key, selectorKey);
        if (known.isPresent()) {
            Locator l = adapter.locator(known.get().healedSelector);
            if (isAttached(l, config.probeTimeoutMs()) && safeCount(l) == 1) return l;
        }
        try {   // a single-page app may still be rendering: give it the probe time to settle
            page.waitForLoadState(com.microsoft.playwright.options.LoadState.NETWORKIDLE,
                    new Page.WaitForLoadStateOptions().setTimeout(config.probeTimeoutMs()));
        } catch (TimeoutError ignored) {
            // matching still works on what is rendered
        }
        HealingTrace trace = new HealingTrace(verbose ? HealingTrace.console() : null).forIntent();
        trace.title(key);
        trace.note("info", "trace.intent.start", description);
        HealingListener listener = visualizer == null ? trace : HealingListener.of(List.of(trace, visualizer));
        long started = System.currentTimeMillis();
        HealingEngine.Result result = eng.resolveIntent(key, description, adapter, listener);

        HealingEvent event = toEvent(key, selectorKey, result, eng);
        event.kind = "intent";
        event.healDurationMs = System.currentTimeMillis() - started;
        if (result.healed()) {
            HealingSuggestion s = result.suggestion();
            trace.note("ok", "trace.intent.found", com.selfhealing.healer.core.Messages.get("source." + s.source()),
                    s.confidence(), s.selector(), com.selfhealing.healer.core.LlmPricing.format(event.llmCostUsd));
        } else {
            trace.note("error", "trace.intent.notFound", result.failureReason());
        }
        event.trace = trace.lines();
        event.candidates = trace.topCandidates().stream()
                .map(c -> new HealingEvent.Candidate(c.score(), c.candidate().describe(), c.candidate().getSelector()))
                .toList();
        if (!result.healed()) {
            HealingRecorder.record(event);
            throw new HealingFailedException("Step \"" + description + "\" (" + key + "): " + result.failureReason());
        }
        if (result.suggestion().source() != HealingSuggestion.Source.CACHE) HealingRecorder.record(event);
        return adapter.locator(result.suggestion().selector());
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
        if (selector.startsWith(HealingEngine.INTENT)) {
            if (!config.enabled()) throw new IllegalStateException("healer.find(\"" + key + "\", ...) needs the healer, but healer.enabled=false");
            return resolveIntent(key, selector, eng, config);
        }
        Locator original = adapter.locator(selector);
        if (config.mode() == HealerConfig.Mode.OFF || !config.enabled()) return original;
        LocatorQuality.Entry quality = observeQuality(key, selector, config);

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
            suggestStableSelector(quality, original);
            return original;
        }
        if (quality != null) LocatorQuality.markBroken(quality);
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
            String label = "popup".equals(event.kind) ? "CLOSE POPUP"
                    : "HEALED (" + com.selfhealing.healer.core.Messages.get("source." + event.source)
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


    /**
     * Before an interaction: if a layer (cookie banner, modal, promo overlay) covers the element, close it and
     * report it as WARN - once per layer and test; later occurrences are closed silently. {@code healer.popups=off}
     * disables it.
     */
    void clearObstruction(String key, String selector, Locator target) {
        HealerConfig config = engine().config();
        if (config.mode() == HealerConfig.Mode.OFF || !config.enabled() || "off".equalsIgnoreCase(config.get("healer.popups", "auto"))) return;
        for (int attempt = 0; attempt < 3; attempt++) {
            PopupGuard.Obstruction o = PopupGuard.inspect(target);
            if (o == null) return;
            String test = HealingRecorder.currentTest();
            boolean reported = HealingRecorder.eventsFor(test).stream()
                    .anyMatch(e -> "popup".equals(e.kind) && o.layer().equals(e.originalElement));

            HealingTrace trace = new HealingTrace(verbose && !reported ? HealingTrace.console() : null);
            trace.title(key);
            trace.note("warn", "trace.popup.blocked", selector, o.layer());
            HealingEvent event = new HealingEvent();
            event.kind = "popup";
            event.key = key;
            event.status = HealingEvent.Status.HEALED;
            event.source = HealingSuggestion.Source.HEURISTIC;
            event.originalSelector = selector;
            event.originalElement = o.layer();
            event.pageUrl = adapter.url();
            if (!reported) {
                HealingRecorder.record(event);   // gets its id, needed by the screenshot
                if (visualizer != null) visualizer.popup(o);
            }

            long started = System.currentTimeMillis();
            boolean clicked = o.button() != null && !reported && screenshots
                    ? captureThen(event, o, () -> PopupGuard.clickButton(target))
                    : o.button() != null && PopupGuard.clickButton(target);
            if (clicked) {
                trace.note("info", "trace.popup.button", o.button());
            } else {
                page.keyboard().press("Escape");
                trace.note("info", "trace.popup.escape");
            }
            boolean cleared = PopupGuard.waitUncovered(target, 2000);
            trace.note(cleared ? "ok" : "error", cleared ? "trace.popup.cleared" : "trace.popup.stillBlocked", selector);

            event.healedSelector = clicked ? o.buttonSelector() : "Escape";
            event.healedElement = clicked ? o.button() : "Escape";
            event.confidence = clicked ? Math.min(1.0, o.score() / 3.0) : 0.5;
            event.reasoning = clicked ? "Clicked '" + o.button() + "' to close '" + o.layer() + "'"
                                      : "Pressed Escape to close '" + o.layer() + "'";
            event.healDurationMs = System.currentTimeMillis() - started;
            event.trace = trace.lines();
            if (!cleared) {
                if (!reported) event.status = HealingEvent.Status.FAILED;
                return;   // the action runs and reports the real problem
            }
        }
    }

    /** Screenshot with the closing button framed (before the click), then the click. */
    private boolean captureThen(HealingEvent event, PopupGuard.Obstruction o, java.util.function.BooleanSupplier click) {
        event.healedSelector = o.buttonSelector();
        event.source = HealingSuggestion.Source.HEURISTIC;
        event.confidence = Math.min(1.0, o.score() / 3.0);
        event.screenshot = capture(event);
        return click.getAsBoolean();
    }

    private LocatorQuality.Entry observeQuality(String key, String selector, HealerConfig config) {
        if ("off".equalsIgnoreCase(config.get("healer.lint", "on"))) return null;
        com.selfhealing.healer.core.CallSite origin = ORIGINS.get(key + "\u0000" + selector);
        return LocatorQuality.observe(key, selector, origin == null ? null : origin.file(), origin == null ? 0 : origin.line());
    }

    /** For a risky selector that still works: the most stable selector healer.js finds for the same element. */
    private static void suggestStableSelector(LocatorQuality.Entry quality, Locator original) {
        if (quality == null || quality.suggestionChecked || quality.risk == SelectorLint.Risk.OK) return;
        try {
            if (original.count() != 1) return;
            Object s = original.evaluate("el => (" + PlaywrightPageAdapter.LIB + ").uniqueSelector(el)");
            LocatorQuality.suggest(quality, s instanceof String str ? str : null);
        } catch (RuntimeException e) {
            LocatorQuality.suggest(quality, null);   // best effort
        }
    }

    /** A heal made earlier in this run, reported once per test as reused (no new work, no cost). */
    private static void recordOnce(HealingEvent template) {
        String test = HealingRecorder.currentTest();
        boolean seen = HealingRecorder.eventsFor(test).stream().anyMatch(e -> e.kind == null && e.key.equals(template.key));
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
        com.selfhealing.healer.core.CallSite origin = ORIGINS.get(key + "\u0000" + selector);
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
        e.sourceFile = t.sourceFile;
        e.sourceLine = t.sourceLine;
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
