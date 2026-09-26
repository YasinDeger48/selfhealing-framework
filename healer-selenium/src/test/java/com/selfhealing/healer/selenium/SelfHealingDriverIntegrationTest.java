package com.selfhealing.healer.selenium;

import com.selfhealing.healer.core.HealingEvent;
import com.selfhealing.healer.core.HealingRecorder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.edge.EdgeDriver;
import org.openqa.selenium.edge.EdgeOptions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Selenium adapter in a real browser (headless Edge; -Dselenium.browser=chrome for Chrome).
 * Pattern: use the element once (fingerprint recorded), change the DOM, use it again.
 */
class SelfHealingDriverIntegrationTest {

    private static WebDriver driver;
    private SelfHealingDriver healer;
    private String test;

    @BeforeAll
    static void launch() {
        if ("chrome".equals(System.getProperty("selenium.browser", "edge"))) {
            driver = new ChromeDriver(new ChromeOptions().addArguments("--headless=new", "--window-size=1280,900"));
        } else {
            driver = new EdgeDriver(new EdgeOptions().addArguments("--headless=new", "--window-size=1280,900"));
        }
    }

    @AfterAll
    static void quit() {
        if (driver != null) driver.quit();
    }

    @BeforeEach
    void open(TestInfo info) {
        SelfHealingDriver.resetForTests();
        test = "SeleniumIT." + info.getTestMethod().orElseThrow().getName();
        HealingRecorder.startTest(test);
        healer = SelfHealingDriver.wrap(driver);
    }

    @AfterEach
    void end() {
        HealingRecorder.endTest();
    }

    private void setContent(String html) throws IOException {
        Path file = Files.createTempFile("healer-selenium", ".html");
        Files.writeString(file, "<!doctype html><html><body>" + html + "</body></html>");
        driver.get(file.toUri().toString());
    }

    private void js(String script) {
        ((JavascriptExecutor) driver).executeScript(script);
    }

    private HealingEvent lastEvent(String key) {
        return HealingRecorder.eventsFor(test).stream().filter(e -> e.key.equals(key)).reduce((a, b) -> b).orElseThrow();
    }

    @Test
    void renamedElementIsHealedForById() throws IOException {
        setContent("<form><input id='user' name='user' placeholder='User'>"
                + "<button type='button' id='save' data-testid='save-btn' class='btn primary' onclick=\"this.textContent='Saved'\">Save</button>"
                + "<button type='button' id='cancel' class='btn'>Cancel</button></form>");
        healer.element("Form.save", By.id("save")).getText();

        js("const b = document.getElementById('save'); b.id = 'store'; b.dataset.testid = 'store-btn';");

        healer.element("Form.save", By.id("save")).click();
        assertEquals("Saved", driver.findElement(By.id("store")).getText());
        HealingEvent e = lastEvent("Form.save");
        assertEquals(HealingEvent.Status.HEALED, e.status);
        assertEquals("[data-testid=\"store-btn\"]", e.healedSelector);
        assertEquals("save", e.sourceLiteral, "the literal of By.id(\"save\") - used by the code fix");
        assertEquals(null, e.sourceFile, "framework classes and test runners are never reported as the declaring code");
    }

    @Test
    void xpathLocatorIsHealed() throws IOException {
        setContent("<label for='mail'>Email</label><input id='mail' name='email' placeholder='you@example.com'>");
        healer.element("Signup.email", By.xpath("//input[@name='email']")).fill("a@b.c");

        js("const i = document.getElementById('mail'); i.name = 'contact-email';");

        healer.element("Signup.email", By.xpath("//input[@name='email']")).fill("x@y.z");
        assertEquals("x@y.z", driver.findElement(By.id("mail")).getDomProperty("value"));
        assertEquals(HealingEvent.Status.HEALED, lastEvent("Signup.email").status);
    }

    @Test
    void healedSelectorAvoidsGeneratedIdParts() throws IOException {
        String form = "<button type='button' id='pay-%1$s' data-testid='pay-button-%1$s'>Pay now</button>"
                + "<button type='button' id='back-%1$s'>Back</button>";
        setContent(String.format(form, "a1b2c3d4"));
        healer.element("Checkout.pay", By.id("pay-a1b2c3d4")).getText();

        setContent(String.format(form, "e5f6a7b8"));
        healer.element("Checkout.pay", By.id("pay-a1b2c3d4")).click();
        assertEquals("[data-testid^=\"pay-button-\"]", lastEvent("Checkout.pay").healedSelector);
    }

    @Test
    void cookieBannerCoveringTheElementIsClosed() throws IOException {
        setContent("<div style='height:80vh'></div>"
                + "<button type='button' id='save' onclick=\"this.textContent='Saved'\">Save</button>"
                + "<div id='consent' style='position:fixed;left:0;right:0;bottom:0;height:45vh;background:#eee'>We use cookies."
                + "<button onclick=\"document.getElementById('consent').remove()\">Accept all</button></div>");

        healer.element("Form.save", By.id("save")).click();

        assertEquals("Saved", driver.findElement(By.id("save")).getText());
        assertEquals("popup", lastEvent("Form.save").kind);
    }

    @Test
    void removedListItemIsNotHealedOntoAnother() throws IOException {
        setContent("<ul>"
                + "<li id='item-1'><span>Mug</span><button id='add-1' data-testid='add-1' class='add'>Add</button></li>"
                + "<li id='item-2'><span>Cup</span><button id='add-2' data-testid='add-2' class='add'>Add</button></li></ul>");
        healer.element("Shop.add[2]", By.id("add-2")).getText();

        js("document.getElementById('add-2').remove()");

        assertThrows(HealingFailedException.class, () -> healer.element("Shop.add[2]", By.id("add-2")).click());
        assertEquals(HealingEvent.Status.FAILED, lastEvent("Shop.add[2]").status);
    }
}
