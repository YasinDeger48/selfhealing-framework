package com.selfhealing.healer.selenium;

import com.fasterxml.jackson.databind.JsonNode;
import com.selfhealing.healer.core.BrowserScripts;
import com.selfhealing.healer.core.HealingEvent;
import com.selfhealing.healer.core.HealingRecorder;
import com.selfhealing.healer.core.HealingSuggestion;
import com.selfhealing.healer.core.HealingTrace;
import com.selfhealing.healer.core.Json;
import org.openqa.selenium.Keys;
import org.openqa.selenium.WebDriverException;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.interactions.Actions;

/**
 * Closes a layer (cookie banner, modal, promo overlay) that covers an element before it is used - the same rules as
 * for Playwright (popup.js): close / accept / "not now" buttons in six languages, never buttons that delete, buy or
 * submit, Escape as a fallback. Reported once per layer and test as a WARN.
 */
final class SeleniumPopups {

    private static final String POPUP = "(" + BrowserScripts.POPUP + ")";

    private SeleniumPopups() {
    }

    static void clear(SeleniumPageAdapter adapter, String key, String selector, WebElement target, boolean verbose) {
        for (int attempt = 0; attempt < 3; attempt++) {
            JsonNode o = inspect(adapter, target);
            if (o == null) return;
            String layer = o.path("layer").asText();
            String button = o.path("button").isNull() ? null : o.path("button").asText(null);
            String test = HealingRecorder.currentTest();
            boolean reported = HealingRecorder.eventsFor(test).stream()
                    .anyMatch(e -> "popup".equals(e.kind) && layer.equals(e.originalElement));

            HealingTrace trace = new HealingTrace(verbose && !reported ? HealingTrace.console() : null);
            trace.title(key);
            trace.note("warn", "trace.popup.blocked", selector, layer);
            long started = System.currentTimeMillis();
            boolean clicked = false;
            if (button != null) {
                try {
                    Object el = adapter.script("return " + POPUP + ".button(arguments[0]);", target);
                    if (el instanceof WebElement b) {
                        b.click();
                        clicked = true;
                    }
                } catch (WebDriverException ignored) {
                    // fall back to Escape
                }
            }
            if (clicked) {
                trace.note("info", "trace.popup.button", button);
            } else {
                new Actions(adapter.driver()).sendKeys(Keys.ESCAPE).perform();
                trace.note("info", "trace.popup.escape");
            }
            boolean cleared = waitUncovered(adapter, target, 2000);
            trace.note(cleared ? "ok" : "error", cleared ? "trace.popup.cleared" : "trace.popup.stillBlocked", selector);

            if (!reported) {
                HealingEvent event = new HealingEvent();
                event.kind = "popup";
                event.key = key;
                event.status = cleared ? HealingEvent.Status.HEALED : HealingEvent.Status.FAILED;
                event.source = HealingSuggestion.Source.HEURISTIC;
                event.originalSelector = selector;
                event.originalElement = layer;
                event.healedSelector = clicked ? o.path("buttonSelector").asText(null) : "Escape";
                event.healedElement = clicked ? button : "Escape";
                event.confidence = clicked ? Math.min(1.0, o.path("score").asDouble() / 3.0) : 0.5;
                event.reasoning = clicked ? "Clicked '" + button + "' to close '" + layer + "'" : "Pressed Escape to close '" + layer + "'";
                event.pageUrl = adapter.url();
                event.healDurationMs = System.currentTimeMillis() - started;
                event.trace = trace.lines();
                HealingRecorder.record(event);
            }
            if (!cleared) return;
        }
    }

    private static JsonNode inspect(SeleniumPageAdapter adapter, WebElement target) {
        try {
            String json = (String) adapter.script("const H = " + SeleniumPageAdapter.LIB + ";"
                    + "return JSON.stringify(" + POPUP + ".inspect(arguments[0], e => H.uniqueSelector(e)));", target);
            JsonNode node = Json.MAPPER.readTree(json);
            return node == null || node.isNull() ? null : node;
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean waitUncovered(SeleniumPageAdapter adapter, WebElement target, long timeoutMs) {
        long end = System.currentTimeMillis() + timeoutMs;
        while (true) {
            try {
                if (!Boolean.TRUE.equals(adapter.script("return !!" + POPUP + ".cover(arguments[0]);", target))) return true;
            } catch (WebDriverException e) {
                return true;
            }
            if (System.currentTimeMillis() >= end) return false;
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }
}
