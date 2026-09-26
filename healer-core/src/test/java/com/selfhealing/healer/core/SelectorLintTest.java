package com.selfhealing.healer.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static com.selfhealing.healer.core.SelectorLint.Risk.HIGH;
import static com.selfhealing.healer.core.SelectorLint.Risk.LOW;
import static com.selfhealing.healer.core.SelectorLint.Risk.MEDIUM;
import static com.selfhealing.healer.core.SelectorLint.Risk.OK;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SelectorLintTest {

    private static void rated(String selector, SelectorLint.Risk risk, String... reasons) {
        SelectorLint.Assessment a = SelectorLint.assess(selector);
        assertEquals(risk, a.risk(), selector + " " + a.reasons());
        for (String r : reasons) assertTrue(a.reasons().contains(r), selector + " should have " + r + ": " + a.reasons());
    }

    @Test
    void stableSelectorsAreOk() {
        rated("[data-testid='login-submit-button']", OK);
        rated("#login-username", OK);
        rated("input[name='password']", OK);
        rated("button[aria-label=\"Close\"]", OK);
        rated("[data-testid^='booking-reference-input-']", OK);   // prefix of a generated value
        rated("data-testid=checkout", OK);
        rated("#add-to-cart-8", OK);                               // an item number is not generated
    }

    @Test
    void fragileSelectorsAreFlagged() {
        rated("/html/body/div[2]/form/input[1]", HIGH, "absoluteXpath", "position");
        rated("ul.products > li:nth-child(3) > button", HIGH, "position");
        rated("#booking-reference-fe465c1e", HIGH, "generated");
        rated(".css-1x2y3z4", HIGH, "generated");
        rated("div > span", HIGH, "noIdentifier");
        rated("button >> nth=2", HIGH, "position");
        rated(".btn.btn-primary", MEDIUM, "styleClass");
        rated("main .card .footer .actions a", MEDIUM, "deepChain");
        rated("text=Sign in", LOW, "text");
        rated("button:has-text('Add to Cart')", LOW, "text");
    }

    @Test
    void qualityKeepsOnlyBetterSuggestionsAndSkipsBrokenOnes() {
        LocatorQuality.clear();
        LocatorQuality.Entry e = LocatorQuality.observe("Cart.checkout", "div.cart > button:nth-child(2)", "com/x/CartPage.java", 12);
        LocatorQuality.suggest(e, "button:nth-of-type(2)");
        assertEquals(null, e.suggestion, "not better");
        e.suggestionChecked = false;
        LocatorQuality.suggest(e, "[data-testid='checkout']");
        assertEquals("[data-testid='checkout']", e.suggestion);

        LocatorQuality.Entry broken = LocatorQuality.observe("Cart.coupon", ".coupon-box input", null, 0);
        LocatorQuality.suggest(broken, "#coupon");
        LocatorQuality.markBroken(broken);
        List<HealingEvent> improvements = LocatorQuality.asImprovements(LocatorQuality.all());
        assertEquals(1, improvements.size(), "a broken locator is a heal, not an improvement");
        assertEquals("div.cart > button:nth-child(2)", improvements.get(0).originalSelector);
        LocatorQuality.clear();
    }
}
