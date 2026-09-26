package com.selfhealing.healer.selenium;

import com.fasterxml.jackson.core.type.TypeReference;
import com.selfhealing.healer.core.BrowserScripts;
import com.selfhealing.healer.core.ElementSnapshot;
import com.selfhealing.healer.core.Json;
import com.selfhealing.healer.core.PageAdapter;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebDriverException;
import org.openqa.selenium.WebElement;

import java.util.List;

/**
 * {@link PageAdapter} backed by Selenium WebDriver. healer.js runs in CSS-only mode, so every selector it produces
 * works with {@code By.cssSelector}. Results travel as JSON strings: independent of each driver's value conversion.
 */
class SeleniumPageAdapter implements PageAdapter {

    /** healer.js in CSS-only mode, as an expression. */
    static final String LIB = "(Object.assign(" + BrowserScripts.HEALER + ", { cssOnly: true }))";
    private static final TypeReference<List<ElementSnapshot>> LIST = new TypeReference<>() { };

    private final WebDriver driver;

    SeleniumPageAdapter(WebDriver driver) {
        this.driver = driver;
    }

    WebDriver driver() {
        return driver;
    }

    Object script(String body, Object... args) {
        return ((JavascriptExecutor) driver).executeScript(body, args);
    }

    @Override
    public String url() {
        return driver.getCurrentUrl();
    }

    @Override
    public List<ElementSnapshot> collectCandidates() {
        String json = (String) script("return JSON.stringify(" + LIB + ".collect());");
        return read(json, LIST);
    }

    @Override
    public int count(String selector) {
        try {
            return driver.findElements(SeleniumSelectors.toBy(selector)).size();
        } catch (WebDriverException e) {
            return 0;   // invalid selector
        }
    }

    @Override
    public ElementSnapshot snapshot(String selector) {
        try {
            List<WebElement> found = driver.findElements(SeleniumSelectors.toBy(selector));
            return found.isEmpty() ? null : snapshot(found.get(0));
        } catch (WebDriverException e) {
            return null;
        }
    }

    ElementSnapshot snapshot(WebElement element) {
        String json = (String) script("return JSON.stringify(" + LIB + ".snapshot(arguments[0]));", element);
        return read(json, new TypeReference<ElementSnapshot>() { });
    }

    /** The most stable CSS selector for an element (for locator-quality suggestions). */
    String uniqueSelector(WebElement element) {
        return (String) script("return " + LIB + ".uniqueSelector(arguments[0]);", element);
    }

    private static <T> T read(String json, TypeReference<T> type) {
        try {
            return Json.MAPPER.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("Unexpected healer.js result", e);
        }
    }
}
