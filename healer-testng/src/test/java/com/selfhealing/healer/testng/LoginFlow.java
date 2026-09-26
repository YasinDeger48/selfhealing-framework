package com.selfhealing.healer.testng;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.selfhealing.healer.playwright.SelfHealingPage;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/** A TestNG class as a user would write it (run programmatically by HealingTestNGListenerTest). */
public class LoginFlow {

    static final String PAGE = "<form><input id='user' placeholder='User'>"
                + "<button type='button' id='save' data-testid='save-btn' class='btn primary' onclick=\"document.getElementById('out').textContent='Saved'\">Save</button>"
                + "<button type='button' id='cancel' class='btn'>Cancel</button></form><p id='out'></p>";

    private Playwright playwright;
    private Browser browser;
    private Page page;
    private SelfHealingPage healer;

    @BeforeClass
    public void launch() {
        playwright = Playwright.create();
        BrowserType.LaunchOptions o = new BrowserType.LaunchOptions().setHeadless(true);
        String channel = System.getProperty("browser.channel", "msedge");
        if (!"chromium".equals(channel)) o.setChannel(channel);
        browser = playwright.chromium().launch(o);
    }

    @AfterClass
    public void close() {
        playwright.close();
    }

    @BeforeMethod
    public void open() {
        page = browser.newPage();
        healer = SelfHealingPage.wrap(page);
    }

    @AfterMethod
    public void closePage() {
        page.close();
    }

    @Test
    public void healsRenamedButton() {
        page.setContent(PAGE);
        healer.locator("Form.save", "#save").click();
        page.evaluate("() => { const b = document.querySelector('#save'); b.id = 'store'; b.dataset.testid = 'store-btn'; }");
        healer.locator("Form.save", "#save").click();
        Assert.assertEquals(page.locator("#out").textContent(), "Saved");
    }

    @Test
    public void wrongExpectation() {
        page.setContent(PAGE);
        Assert.assertEquals(healer.locator("Form.cancel", "#cancel").textContent(), "Abort");
    }
}
