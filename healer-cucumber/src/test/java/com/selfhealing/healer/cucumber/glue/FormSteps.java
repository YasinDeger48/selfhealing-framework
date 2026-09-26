package com.selfhealing.healer.cucumber.glue;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.selfhealing.healer.playwright.SelfHealingPage;
import io.cucumber.java.After;
import io.cucumber.java.AfterAll;
import io.cucumber.java.Before;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Step definitions as a user would write them. */
public class FormSteps {

    static final String PAGE = "<form><input id='user' placeholder='User'>"
                + "<button type='button' id='save' data-testid='save-btn' class='btn primary' onclick=\"document.getElementById('out').textContent='Saved'\">Save</button>"
                + "<button type='button' id='cancel' class='btn'>Cancel</button></form><p id='out'></p>";

    private static Playwright playwright;
    private static Browser browser;
    private Page page;
    private SelfHealingPage healer;

    @Before
    public void open() {
        if (browser == null) {
            playwright = Playwright.create();
            BrowserType.LaunchOptions o = new BrowserType.LaunchOptions().setHeadless(true);
            String channel = System.getProperty("browser.channel", "msedge");
            if (!"chromium".equals(channel)) o.setChannel(channel);
            browser = playwright.chromium().launch(o);
        }
        page = browser.newPage();
        healer = SelfHealingPage.wrap(page);
    }

    @After
    public void close() {
        page.close();
    }

    @AfterAll
    public static void quit() {
        if (playwright != null) playwright.close();
    }

    @Given("the form is open")
    public void formIsOpen() {
        page.setContent(PAGE);
        healer.locator("Form.save", "#save").textContent();   // recorded while it works
    }

    @When("the save button gets a new id")
    public void rename() {
        page.evaluate("() => { const b = document.querySelector('#save'); b.id = 'store'; b.dataset.testid = 'store-btn'; }");
    }

    @When("I press save")
    public void pressSave() {
        healer.locator("Form.save", "#save").click();
    }

    @Then("the form says {string}")
    public void formSays(String text) {
        assertEquals(text, page.locator("#out").textContent());
    }

    @Then("the cancel button says {string}")
    public void cancelSays(String text) {
        assertEquals(text, healer.locator("Form.cancel", "#cancel").textContent());
    }
}
