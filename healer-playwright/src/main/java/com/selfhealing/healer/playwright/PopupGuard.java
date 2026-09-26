package com.selfhealing.healer.playwright;

import com.microsoft.playwright.ElementHandle;
import com.microsoft.playwright.JSHandle;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.PlaywrightException;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Finds out whether a layer (cookie banner, newsletter modal, promo overlay ...) covers an element, and closes it:
 * the layer's close / accept / "not now" button, in six languages, or Escape. Buttons that change data or spend
 * money are never clicked. The checks run in the browser via popup.js.
 */
final class PopupGuard {

    private static final String LIB = load();
    /** popup.js with healer.js for readable selectors in the report. */
    private static final String INSPECT = "el => (" + LIB + ").inspect(el, e => (" + PlaywrightPageAdapter.LIB + ").uniqueSelector(e))";
    private static final String BUTTON = "el => (" + LIB + ").button(el)";
    private static final String COVERED = "el => !!(" + LIB + ").cover(el)";

    /** What covers the element. */
    record Obstruction(String layer, String layerSelector, String button, String buttonSelector, double score) {
    }

    private PopupGuard() {
    }

    private static String load() {
        try (InputStream in = PopupGuard.class.getResourceAsStream("popup.js")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Null when the element can receive the action (or cannot be checked). */
    @SuppressWarnings("unchecked")
    static Obstruction inspect(Locator target) {
        try {
            Object raw = target.first().evaluate(INSPECT);
            if (!(raw instanceof Map<?, ?> m)) return null;
            Map<String, Object> o = (Map<String, Object>) m;
            return new Obstruction((String) o.get("layer"), (String) o.get("layerSelector"), (String) o.get("button"),
                    (String) o.get("buttonSelector"), ((Number) o.getOrDefault("score", 0)).doubleValue());
        } catch (PlaywrightException e) {
            return null;   // detached, navigating ... - the action itself will report real problems
        }
    }

    /** Clicks the layer's closing button; false if there is none. */
    static boolean clickButton(Locator target) {
        try {
            JSHandle handle = target.first().evaluateHandle(BUTTON);
            ElementHandle button = handle.asElement();
            if (button == null) return false;
            button.click(new ElementHandle.ClickOptions().setTimeout(3000));
            return true;
        } catch (PlaywrightException e) {
            return false;
        }
    }

    /** Waits up to {@code timeoutMs} for the element to be uncovered (closing animations). */
    static boolean waitUncovered(Locator target, long timeoutMs) {
        long end = System.currentTimeMillis() + timeoutMs;
        while (true) {
            try {
                if (!Boolean.TRUE.equals(target.first().evaluate(COVERED))) return true;
            } catch (PlaywrightException e) {
                return true;
            }
            if (System.currentTimeMillis() >= end) return false;
            target.page().waitForTimeout(100);
        }
    }
}
