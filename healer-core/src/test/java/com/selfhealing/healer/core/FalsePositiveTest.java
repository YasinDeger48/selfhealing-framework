package com.selfhealing.healer.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A removed element must not be "healed" onto a look-alike that belongs to another record. */
class FalsePositiveTest {

    private final HeuristicMatcher matcher = new HeuristicMatcher();

    @Test
    void removedButtonIsNotHealedOntoTheOnlyOtherCard(@TempDir Path dir) {
        Properties p = new Properties();
        p.setProperty("healer.storeDir", dir.toString());
        HealingEngine engine = new HealingEngine(HealerConfig.from(p), null);
        engine.remember("ProductsPage.addToCart[2]", "#add-to-cart-2", "/products",
                HeuristicMatcherTest.addToCart(2, "add-to-cart-", "add-to-cart-"));

        // Product 2 lost its button; product 1's identical button is the only candidate,
        // so the "top two too close" rule cannot help - only the item number can.
        HealingEngineTest.FakePage page = new HealingEngineTest.FakePage();
        page.elements.add(HeuristicMatcherTest.addToCart(1, "add-to-cart-", "add-to-cart-"));

        HealingEngine.Result result = engine.heal("ProductsPage.addToCart[2]", "#add-to-cart-2", page);

        assertFalse(result.healed(), "must not heal onto product 1's button");
        assertTrue(result.ranked().get(0).score() < 0.6, "look-alike from another record scores below the threshold");
        assertEquals(0.0, result.ranked().get(0).signals().get("indexMismatch"));
    }

    @Test
    void renameThatKeepsTheItemNumberIsNotPenalised() {
        ElementSnapshot fp = HeuristicMatcherTest.addToCart(8, "add-to-cart-", "add-to-cart-");
        ElementSnapshot renamed = HeuristicMatcherTest.addToCart(8, "btn-add-", "product-add-btn-");

        HeuristicMatcher.Scored s = matcher.score(fp, renamed);

        assertFalse(s.signals().containsKey("indexMismatch"));
        assertTrue(s.score() >= 0.6, "same record, renamed ids: still healable, got " + s.score());
    }

    @Test
    void numberInAncestorsAloneAlsoCounts() {
        ElementSnapshot fp = HeuristicMatcherTest.el("button", "Buy", List.of("div.actions", "article#card-3[card-3]"), "class", "buy");
        ElementSnapshot other = HeuristicMatcherTest.el("button", "Buy", List.of("div.actions", "article#card-4[card-4]"), "class", "buy");

        assertTrue(HeuristicMatcher.indexMismatch(fp, other));
        assertFalse(HeuristicMatcher.indexMismatch(fp, fp));
    }

    @Test
    void theOppositeControlIsNeverTheReplacement() {
        ElementSnapshot increase = HeuristicMatcherTest.el("button", "+", List.of("div.quantity"),
                "data-testid", "quantity-increase-button", "aria-label", "Increase quantity", "type", "button");
        ElementSnapshot decrease = HeuristicMatcherTest.el("button", "-", List.of("div.quantity"),
                "data-testid", "quantity-decrease-button", "aria-label", "Decrease quantity", "type", "button");
        assertTrue(HeuristicMatcher.oppositeMeaning(increase, decrease));
        assertTrue(new HeuristicMatcher().score(increase, decrease).score() < 0.6,
                "the increase button was removed: decrease must not replace it");
        ElementSnapshot renamed = HeuristicMatcherTest.el("button", "+", List.of("div.quantity"),
                "data-testid", "qty-increase", "aria-label", "Increase quantity", "type", "button");
        assertFalse(HeuristicMatcher.oppositeMeaning(increase, renamed));
    }
}
