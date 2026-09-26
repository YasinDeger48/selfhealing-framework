package com.selfhealing.healer.playwright;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.selfhealing.healer.core.HealingEvent;
import com.selfhealing.healer.core.HealingRecorder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The framework on small, self-contained pages in a real browser - independent of any demo site.
 * Pattern of every test: use the element once (fingerprint recorded), change the DOM, use it again.
 */
class SelfHealingPageIntegrationTest {

    private static Playwright playwright;
    private static Browser browser;
    private Page page;
    private SelfHealingPage healer;
    private String test;

    @BeforeAll
    static void launch() {
        playwright = Playwright.create();
        BrowserType.LaunchOptions options = new BrowserType.LaunchOptions().setHeadless(true);
        String channel = System.getProperty("browser.channel", "msedge");
        if (!"chromium".equals(channel)) options.setChannel(channel);
        browser = playwright.chromium().launch(options);
    }

    @AfterAll
    static void close() {
        if (playwright != null) playwright.close();
    }

    @BeforeEach
    void open(TestInfo info) {
        System.clearProperty("healer.mode");
        SelfHealingPage.resetForTests();
        test = "IT." + info.getTestMethod().orElseThrow().getName();
        HealingRecorder.startTest(test);
        page = browser.newPage();
        healer = SelfHealingPage.wrap(page);
    }

    @AfterEach
    void closePage() {
        HealingRecorder.endTest();
        page.close();
        System.clearProperty("healer.mode");
    }

    private HealingEvent lastEvent(String key) {
        return HealingRecorder.eventsFor(test).stream().filter(e -> e.key.equals(key)).reduce((a, b) -> b).orElseThrow();
    }

    @Test
    void renamedElementIsHealed() {
        page.setContent("<form><input id='user' name='user' placeholder='User'>"
                + "<button type='button' id='save' data-testid='save-btn' class='btn primary'>Save</button>"
                + "<button type='button' id='cancel' class='btn'>Cancel</button></form>");
        healer.locator("Form.save", "#save").click();

        page.evaluate("() => { const b = document.querySelector('#save'); b.id = 'store'; b.dataset.testid = 'store-btn'; }");

        assertEquals("Save", healer.locator("Form.save", "#save").textContent());
        HealingEvent e = lastEvent("Form.save");
        assertEquals(HealingEvent.Status.HEALED, e.status);
        assertEquals("[data-testid=\"store-btn\"]", e.healedSelector);
    }

    @Test
    void elementInsideShadowDomIsHealed() {
        page.setContent("<newsletter-box></newsletter-box><script>"
                + "customElements.define('newsletter-box', class extends HTMLElement { connectedCallback() {"
                + " const r = this.attachShadow({mode: 'open'});"
                + " r.innerHTML = \"<label for='mail'>Email</label><input id='mail' name='mail' placeholder='you@example.com'>"
                + "<button id='subscribe' data-testid='subscribe-btn'>Subscribe</button>\"; } });</script>");
        healer.locator("Newsletter.subscribe", "#subscribe").click();

        page.evaluate("() => { const b = document.querySelector('newsletter-box').shadowRoot.querySelector('#subscribe');"
                + " b.id = 'join'; b.dataset.testid = 'join-btn'; }");

        healer.locator("Newsletter.subscribe", "#subscribe").click();
        HealingEvent e = lastEvent("Newsletter.subscribe");
        assertEquals(HealingEvent.Status.HEALED, e.status);
        assertEquals(1, page.locator(e.healedSelector).count(), "healed selector must pierce the shadow root");
    }

    @Test
    void elementInsideIframeIsHealed() {
        page.setContent("<h1>Checkout</h1><iframe id='payment' srcdoc=\""
                + "<label for='card'>Card number</label><input id='card' name='card' placeholder='Card number'>"
                + "<button id='pay'>Pay</button>\"></iframe>");
        SelfHealingPage frame = healer.frame("#payment");
        frame.locator("Payment.card", "#card").fill("1111");

        page.frameLocator("#payment").locator("#card").evaluate("e => { e.id = 'cc'; }");

        frame.locator("Payment.card", "#card").fill("4242");
        assertEquals("4242", page.frameLocator("#payment").locator("#cc").inputValue());
        assertEquals(HealingEvent.Status.HEALED, lastEvent("Payment.card").status);
    }

    @Test
    void removedButtonIsNotHealedOntoTheOtherListItem() {
        page.setContent("<ul>"
                + "<li id='item-1' data-testid='item-1'><span>Mug</span><button id='add-1' data-testid='add-1' class='add'>Add</button></li>"
                + "<li id='item-2' data-testid='item-2'><span>Cup</span><button id='add-2' data-testid='add-2' class='add'>Add</button></li>"
                + "</ul>");
        healer.locator("Shop.add[2]", "#add-2").click();

        page.evaluate("() => document.querySelector('#add-2').remove()");

        assertThrows(HealingFailedException.class, () -> healer.locator("Shop.add[2]", "#add-2").click());
        assertEquals(HealingEvent.Status.FAILED, lastEvent("Shop.add[2]").status);
    }

    @Test
    void suggestModeReportsTheReplacementButFailsTheStep() {
        System.setProperty("healer.mode", "suggest");
        SelfHealingPage.resetForTests();
        healer = SelfHealingPage.wrap(page);
        page.setContent("<button id='buy' data-testid='buy-btn' class='cta'>Buy</button>");
        healer.locator("Cart.buy", "#buy").click();

        page.evaluate("() => { const b = document.querySelector('#buy'); b.id = 'purchase'; b.dataset.testid = 'purchase-btn'; }");

        HealingFailedException ex = assertThrows(HealingFailedException.class, () -> healer.locator("Cart.buy", "#buy").click());
        assertTrue(ex.getMessage().contains("suggest"), ex.getMessage());
        HealingEvent e = lastEvent("Cart.buy");
        assertEquals(HealingEvent.Status.SUGGESTED, e.status);
        assertEquals("[data-testid=\"purchase-btn\"]", e.healedSelector);
    }
}
