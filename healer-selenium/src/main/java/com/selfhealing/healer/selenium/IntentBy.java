package com.selfhealing.healer.selenium;

import org.openqa.selenium.By;
import org.openqa.selenium.SearchContext;
import org.openqa.selenium.WebElement;

import java.util.List;

/** A plain-language "locator" ({@link SelfHealingDriver#find}); only the healer can resolve it. */
final class IntentBy extends By {

    final String description;

    IntentBy(String description) {
        this.description = description;
    }

    @Override
    public List<WebElement> findElements(SearchContext context) {
        throw new UnsupportedOperationException("Resolve plain-language steps through SelfHealingDriver.find(...)");
    }

    @Override
    public String toString() {
        return "By.intent: " + description;
    }
}
