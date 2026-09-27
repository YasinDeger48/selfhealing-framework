package com.selfhealing.healer.junit4;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.selfhealing.healer.playwright.SelfHealingPage;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;

/** A JUnit 4 class as a user would write it (run through JUnitCore by HealingRuleTest). */
public class LoginFlow {

    static final String PAGE = "<form><input id='user' placeholder='User'>"
            + "<button type='button' id='save' data-testid='save-btn' class='btn primary' onclick=\"document.getElementById('out').textContent='Saved'\">Save</button>"
            + "<button type='button' id='cancel' class='btn'>Cancel</button></form><p id='out'></p>";

    @Rule
    public HealingRule healing = new HealingRule();

    private static Playwright playwright;
    private static Browser browser;
    private Page page;
    private SelfHealingPage healer;

    @BeforeClass
    public static void launch() {
        playwright = Playwright.create();
        BrowserType.LaunchOptions o = new BrowserType.LaunchOptions().setHeadless(true);
        String channel = System.getProperty("browser.channel", "msedge");
        if (!"chromium".equals(channel)) o.setChannel(channel);
        browser = playwright.chromium().launch(o);
    }

    @AfterClass
    public static void close() {
        playwright.close();
    }

    @Before
    public void open() {
        page = browser.newPage();
        healer = SelfHealingPage.wrap(page);
    }

    @After
    public void closePage() {
        page.close();
    }

    @Test
    public void healsRenamedButton() {
        page.setContent(PAGE);
        healer.locator("Form.save", "#save").click();
        page.evaluate("() => { const b = document.querySelector('#save'); b.id = 'store'; b.dataset.testid = 'store-btn'; }");
        healer.locator("Form.save", "#save").click();
        Assert.assertEquals("Saved", page.locator("#out").textContent());
    }

    @Test
    public void wrongExpectation() {
        page.setContent(PAGE);
        Assert.assertEquals("Abort", healer.locator("Form.cancel", "#cancel").textContent());
    }
}
