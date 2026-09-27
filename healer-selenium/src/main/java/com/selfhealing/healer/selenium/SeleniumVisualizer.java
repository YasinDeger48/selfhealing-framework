package com.selfhealing.healer.selenium;

import com.selfhealing.healer.core.HealingEngine;
import com.selfhealing.healer.core.HealingListener;
import com.selfhealing.healer.core.HealingSuggestion;
import com.selfhealing.healer.core.HeuristicMatcher;
import com.selfhealing.healer.core.LlmPricing;
import org.openqa.selenium.WebDriverException;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static com.selfhealing.healer.core.Messages.get;

/**
 * Draws the healing steps on the page for demos ({@code healer.visual=true}) - the Selenium twin of the Playwright
 * overlay: a log panel, dashed boxes around the scored candidates and a solid green box around the chosen element.
 * Everything it adds is marked {@code data-healer-ui} and ignored by candidate collection.
 */
class SeleniumVisualizer implements HealingListener {

    private static final String LIB = load();
    private static final String ORANGE = "#f08c00";
    private static final String GREEN = "#2f9e44";
    private static final String RED = "#e03131";
    private static final String PURPLE = "#7048e8";

    private final SeleniumPageAdapter page;
    private final long pauseMs;

    SeleniumVisualizer(SeleniumPageAdapter page, long pauseMs) {
        this.page = page;
        this.pauseMs = pauseMs;
    }

    private static String load() {
        try (InputStream in = SeleniumVisualizer.class.getResourceAsStream("/com/selfhealing/healer/core/overlay.js")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void broken(String key, String selector, long waitedMs) {
        log("<b style='color:#fff'>" + esc(key) + "</b>", null);
        log(get("vis.notFound", esc(selector)), RED);
        pause(1);
    }

    @Override
    public void cacheHit(String key, HealingEngine.CachedHeal heal) {
        log(get("vis.cache", heal.source), GREEN);
    }

    @Override
    public void heuristicRanked(String key, List<HeuristicMatcher.Scored> ranked, double minConfidence,
                                double minMargin, boolean accepted) {
        log(get("vis.heuristic", ranked.size(), fmt(minConfidence)), null);
        for (int i = Math.min(3, ranked.size()) - 1; i >= 0; i--) {
            HeuristicMatcher.Scored s = ranked.get(i);
            box(s.candidate().getSelector(), "#" + (i + 1) + "  " + fmt(s.score()), ORANGE, false);
        }
        for (int i = 0; i < Math.min(3, ranked.size()); i++) {
            HeuristicMatcher.Scored s = ranked.get(i);
            log("&nbsp;&nbsp;#" + (i + 1) + " " + fmt(s.score()) + " " + esc(s.candidate().describe()), ORANGE);
        }
        if (!ranked.isEmpty()) {
            Map<String, Double> sig = ranked.get(0).signals();
            StringBuilder sb = new StringBuilder("&nbsp;&nbsp;&nbsp;&nbsp;");
            sig.entrySet().stream()
                    .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                    .limit(6)
                    .forEach(e -> sb.append(esc(e.getKey())).append(' ').append(fmt(e.getValue())).append(" &middot; "));
            log("<span style='color:#cbd5e1'>" + sb + "</span>", null);
            log(get(accepted ? "vis.accepted" : "vis.askClaude"), accepted ? GREEN : ORANGE);
        }
        pause(2);
    }

    @Override
    public void llmRequested(String key, String model, int candidates) {
        log(get("vis.llmRequest", esc(model), candidates), PURPLE);
    }

    @Override
    public void llmAnswered(String key, com.selfhealing.healer.core.LocatorHealer.Answer answer, long elapsedMs) {
        if (answer.suggestion().isEmpty()) {
            log(get("vis.llmNone"), RED);
            return;
        }
        HealingSuggestion s = answer.suggestion().get();
        String cost = "";
        if (s.usage() != null) {
            HealingSuggestion.LlmUsage u = s.usage();
            cost = " &middot; " + (u.inputTokens() + u.outputTokens()) + " tokens &middot; " + LlmPricing.format(u.costUsd());
        }
        log(get("vis.llmChose", fmt(s.confidence()), String.format(java.util.Locale.ROOT, "%.1f", elapsedMs / 1000.0), cost), PURPLE);
        log("&nbsp;&nbsp;<i>\"" + esc(s.reasoning()) + "\"</i>", "#c4b5fd");
    }

    @Override
    public void finished(String key, HealingEngine.Result result) {
        clearBoxes();
        if (result.healed()) {
            HealingSuggestion s = result.suggestion();
            box(s.selector(), "HEALED (" + get("source." + s.source()) + " " + fmt(s.confidence()) + ")", GREEN, true);
            log(get("vis.healed", esc(s.selector())), GREEN);
            pause(2);
            clearBoxes();
        } else {
            log(get("vis.failed"), RED);
            pause(1);
        }
    }

    private void log(String html, String color) {
        run("return (" + LIB + ").log(arguments[0], arguments[1]);", html, color);
    }

    private void box(String selector, String label, String color, boolean solid) {
        if (selector != null) run("return (" + LIB + ").box(arguments[0], arguments[1], arguments[2], arguments[3]);", selector, label, color, solid);
    }

    private void clearBoxes() {
        run("return (" + LIB + ").clearBoxes();");
    }

    private void run(String script, Object... args) {
        try {
            page.script(script, args);
        } catch (WebDriverException ignored) {
            // the page navigated away; drawing is best effort
        }
    }

    private void pause(int units) {
        if (pauseMs <= 0) return;
        try {
            Thread.sleep(pauseMs * units);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String fmt(double d) {
        return String.format(java.util.Locale.ROOT, "%.2f", d);
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
