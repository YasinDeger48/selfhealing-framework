package com.selfhealing.healer.playwright;

import com.fasterxml.jackson.core.type.TypeReference;
import com.microsoft.playwright.ElementHandle;
import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import com.selfhealing.healer.core.ElementSnapshot;
import com.selfhealing.healer.core.Json;
import com.selfhealing.healer.core.PageAdapter;

import java.util.List;

/**
 * {@link PageAdapter} backed by Playwright. The scope is either the page's main document or the
 * document of one iframe ({@code frameSelector}); DOM work runs in that document via healer.js.
 */
class PlaywrightPageAdapter implements PageAdapter {

    static final String LIB = com.selfhealing.healer.core.BrowserScripts.HEALER;
    private static final TypeReference<List<ElementSnapshot>> LIST = new TypeReference<>() { };

    private final Page page;
    private final String frameSelector;

    PlaywrightPageAdapter(Page page) {
        this(page, null);
    }

    /** @param frameSelector selector of the iframe to work in, or null for the main document */
    PlaywrightPageAdapter(Page page, String frameSelector) {
        this.page = page;
        this.frameSelector = frameSelector;
    }

    Page page() {
        return page;
    }

    String frameSelector() {
        return frameSelector;
    }

    /** A Playwright locator in this scope. */
    Locator locator(String selector) {
        return frameSelector == null ? page.locator(selector) : page.frameLocator(frameSelector).locator(selector);
    }

    /** Runs a script in this scope's document (main page or iframe). */
    Object evaluate(String expression, Object arg) {
        return frameSelector == null ? page.evaluate(expression, arg) : frame().evaluate(expression, arg);
    }

    private Frame frame() {
        ElementHandle iframe = page.locator(frameSelector).first().elementHandle();
        Frame frame = iframe.contentFrame();
        if (frame == null) throw new PlaywrightException("Not an iframe: " + frameSelector);
        frame.waitForLoadState();
        return frame;
    }

    @Override
    public String url() {
        return frameSelector == null ? page.url() : frame().url();
    }

    @Override
    public List<ElementSnapshot> collectCandidates() {
        Object raw = evaluate("() => (" + LIB + ").collect()", null);
        return Json.MAPPER.convertValue(raw, LIST);
    }

    @Override
    public int count(String selector) {
        try {
            return locator(selector).count();
        } catch (PlaywrightException e) {
            return 0; // invalid selector
        }
    }

    @Override
    public ElementSnapshot snapshot(String selector) {
        try {
            return snapshot(locator(selector).first());
        } catch (PlaywrightException e) {
            return null;
        }
    }

    static ElementSnapshot snapshot(Locator locator) {
        Object raw = locator.evaluate("el => (" + LIB + ").snapshot(el)");
        return Json.MAPPER.convertValue(raw, ElementSnapshot.class);
    }
}
