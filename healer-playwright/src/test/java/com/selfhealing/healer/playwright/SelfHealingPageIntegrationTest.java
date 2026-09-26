package com.selfhealing.healer.playwright;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.selfhealing.healer.core.HealingEvent;
import com.selfhealing.healer.core.HealingRecorder;
import com.selfhealing.healer.core.HealingSuggestion;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.io.TempDir;

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
    void open(TestInfo info, @TempDir java.nio.file.Path store) {
        System.clearProperty("healer.mode");
        System.setProperty("healer.storeDir", store.toString());   // every test starts without fingerprints or cache
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
    void healedSelectorAvoidsGeneratedIdParts() {
        String form = "<input id='coupon-%1$s' placeholder='Coupon'>"
                + "<button type='button' id='pay-%1$s' data-testid='pay-button-%1$s'>Pay now</button>"
                + "<button type='button' id='back-%1$s'>Back</button>";
        page.setContent(String.format(form, "a1b2c3d4"));
        healer.locator("Checkout.pay", "#pay-a1b2c3d4").click();

        // A new build / page load: every id gets a new random suffix.
        page.setContent(String.format(form, "e5f6a7b8"));
        healer.locator("Checkout.pay", "#pay-a1b2c3d4").click();
        HealingEvent e = lastEvent("Checkout.pay");
        assertEquals(HealingEvent.Status.HEALED, e.status);
        assertEquals("[data-testid^=\"pay-button-\"]", e.healedSelector, "the stable part, not the random suffix");

        // The next load still works with the same healed selector - no new heal needed.
        page.setContent(String.format(form, "9c8d7e6f"));
        healer.locator("Checkout.pay", "#pay-a1b2c3d4").click();
        assertEquals(1, HealingRecorder.eventsFor(test).stream().filter(x -> x.key.equals("Checkout.pay")).count(),
                "the heal is reused on the next load, not repeated");
    }

    private long popups() {
        return HealingRecorder.eventsFor(test).stream().filter(e -> "popup".equals(e.kind)).count();
    }

    private static final String SAVE_FORM = "<button type='button' id='save' onclick=\"this.textContent='Saved'\">Save</button>";

    @Test
    void cookieBannerCoveringTheElementIsClosed() {
        page.setContent("<body style='margin:0'><div style='height:80vh'></div>" + SAVE_FORM
                + "<div id='consent' style='position:fixed;left:0;right:0;bottom:0;height:45vh;background:#eee'>We use cookies."
                + "<button onclick=\"alert('no')\" style='display:none'>Hidden</button>"
                + "<button onclick=\"document.getElementById('consent').remove()\">Reject all</button>"
                + "<button onclick=\"document.getElementById('consent').remove()\">Accept all</button></div></body>");

        healer.locator("Form.save", "#save").click();

        assertEquals("Saved", page.locator("#save").textContent());
        HealingEvent e = lastEvent("Form.save");
        assertEquals("popup", e.kind);
        assertEquals(HealingEvent.Status.HEALED, e.status);
        assertTrue(e.healedElement.contains("accept all"), e.healedElement);
    }

    @Test
    void modalIsClosedWithItsIconButtonNeverWithADangerousOne() {
        page.setContent(SAVE_FORM
                + "<div id='modal' role='dialog' aria-modal='true' style='position:fixed;inset:0;background:rgba(0,0,0,.4)'>"
                + "<div style='background:#fff;margin:100px auto;width:300px'>Get 10% off!"
                + "<button onclick=\"document.body.dataset.subscribed='yes'\">Subscribe</button>"
                + "<button aria-label='Close' onclick=\"document.getElementById('modal').remove()\">&times;</button></div></div>");

        healer.locator("Form.save", "#save").click();

        assertEquals("Saved", page.locator("#save").textContent());
        assertEquals(null, page.evaluate("document.body.dataset.subscribed"), "Subscribe must never be clicked");
        assertEquals(1, popups());
    }

    @Test
    void layerWithoutCloseButtonIsClosedWithEscape() {
        page.setContent(SAVE_FORM
                + "<div id='modal' role='dialog' style='position:fixed;inset:0;background:rgba(0,0,0,.4)'>"
                + "<button onclick=\"alert('x')\">Delete account</button></div>"
                + "<script>document.addEventListener('keydown', e => { if (e.key === 'Escape') document.getElementById('modal').remove(); });</script>");

        healer.locator("Form.save", "#save").click();

        assertEquals("Saved", page.locator("#save").textContent());
        assertEquals("Escape", lastEvent("Form.save").healedSelector);
    }

    @Test
    void elementInsideTheModalIsNotAPopupCase() {
        page.setContent("<div role='dialog' style='position:fixed;inset:0;background:#fff'>"
                + "<button type='button' id='save' onclick=\"this.textContent='Saved'\">Save</button>"
                + "<button onclick=\"this.parentElement.remove()\">Close</button></div>");

        healer.locator("Dialog.save", "#save").click();

        assertEquals("Saved", page.locator("#save").textContent());
        assertEquals(0, popups());
    }

    @Test
    void plainLanguageStepsFindTheirElements() {
        page.setContent("<form onsubmit='return false'><label for='mail'>Email</label><input id='mail' type='email'>"
                + "<label><input type='checkbox' id='terms'> I accept the terms</label>"
                + "<button type='button' onclick=\"document.getElementById('out').textContent='Sent'\">Send message</button>"
                + "<a href='#help'>Help</a></form><p id='out'></p>");

        healer.find("Contact.email", "the email field").fill("jane@example.com");
        healer.find("Contact.terms", "accept the terms checkbox").check();
        healer.find("Contact.send", "Send message button").click();

        assertEquals("jane@example.com", page.locator("#mail").inputValue());
        assertTrue(page.locator("#terms").isChecked());
        assertEquals("Sent", page.locator("#out").textContent());
        HealingEvent e = lastEvent("Contact.send");
        assertEquals("intent", e.kind);
        assertEquals(HealingSuggestion.Source.HEURISTIC, e.source, "found locally, no LLM");

        // the same step again: the saved selector is used, no new resolution
        healer.find("Contact.send", "Send message button").click();
        assertEquals(1, HealingRecorder.eventsFor(test).stream().filter(x -> x.key.equals("Contact.send")).count());
    }

    @Test
    void ambiguousPlainLanguageStepFailsInsteadOfGuessing() {
        page.setContent("<ul><li>Mug <button>Add</button></li><li>Cup <button>Add</button></li></ul>");
        assertThrows(HealingFailedException.class, () -> healer.find("Shop.add", "the Add button").click());
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
