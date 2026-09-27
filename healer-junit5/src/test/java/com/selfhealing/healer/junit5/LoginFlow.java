package com.selfhealing.healer.junit5;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.selfhealing.healer.playwright.SelfHealingPage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** A JUnit 5 class as a user would write it (run through the launcher by HealingExtensionTest). */
@ExtendWith(HealingExtension.class)
class LoginFlow {

    static final String PAGE = "<form><input id='user' placeholder='User'>"
            + "<button type='button' id='save' data-testid='save-btn' class='btn primary' onclick=\"document.getElementById('out').textContent='Saved'\">Save</button>"
            + "<button type='button' id='cancel' class='btn'>Cancel</button></form><p id='out'></p>";

    static Playwright playwright;
    static Browser browser;
    Page page;
    SelfHealingPage healer;

    @BeforeAll
    static void launch() {
        playwright = Playwright.create();
        BrowserType.LaunchOptions o = new BrowserType.LaunchOptions().setHeadless(true);
        String channel = System.getProperty("browser.channel", "msedge");
        if (!"chromium".equals(channel)) o.setChannel(channel);
        browser = playwright.chromium().launch(o);
    }

    @AfterAll
    static void close() {
        playwright.close();
    }

    @BeforeEach
    void open() {
        page = browser.newPage();
        healer = SelfHealingPage.wrap(page);
    }

    @AfterEach
    void closePage() {
        page.close();
    }

    @Test
    void healsRenamedButton() {
        page.setContent(PAGE);
        healer.locator("Form.save", "#save").click();
        page.evaluate("() => { const b = document.querySelector('#save'); b.id = 'store'; b.dataset.testid = 'store-btn'; }");
        healer.locator("Form.save", "#save").click();
        assertEquals("Saved", page.locator("#out").textContent());
    }

    @Test
    void wrongExpectation() {
        page.setContent(PAGE);
        assertEquals("Abort", healer.locator("Form.cancel", "#cancel").textContent());
    }
}
