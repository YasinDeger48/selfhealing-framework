package com.selfhealing.healer.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HeuristicMatcherTest {

    private final HeuristicMatcher matcher = new HeuristicMatcher();

    static ElementSnapshot el(String tag, String text, List<String> ancestors, String... attrs) {
        ElementSnapshot e = new ElementSnapshot();
        e.setTag(tag);
        e.setText(text);
        e.setAncestors(new ArrayList<>(ancestors));
        for (int i = 0; i < attrs.length; i += 2) e.getAttributes().put(attrs[i], attrs[i + 1]);
        e.setSelector("#" + e.attr("id"));
        return e;
    }

    static ElementSnapshot addToCart(int id, String idPrefix, String testIdPrefix) {
        return el("button", "Sepete Ekle", List.of("div.product-actions", "article#product-card-" + id + "[product-card-" + id + "]"),
                "id", idPrefix + id, "data-testid", testIdPrefix + id, "class", "btn btn-primary btn-add-cart",
                "aria-label", "Product " + id + " sepete ekle", "name", "addToCart");
    }

    @Test
    void picksTheRightCardAmongLookAlikes() {
        ElementSnapshot fp = addToCart(8, "add-to-cart-", "add-to-cart-");
        List<ElementSnapshot> page = new ArrayList<>();
        for (int i = 1; i <= 8; i++) page.add(addToCart(i, "btn-add-", "product-add-btn-"));

        List<HeuristicMatcher.Scored> ranked = matcher.rank(fp, page);

        assertEquals("btn-add-8", ranked.get(0).candidate().attr("id"));
        assertTrue(ranked.get(0).score() - ranked.get(1).score() >= 0.08, "clear margin over the other cards");
    }

    @Test
    void buttonRetaggedAsLinkStillMatches() {
        ElementSnapshot fp = el("button", "Çıkış", List.of("div.user-area"),
                "id", "logout-button", "data-testid", "logout-button", "aria-label", "Çıkış yap", "type", "button");
        ElementSnapshot link = el("a", "Çıkış Yap", List.of("div.user-area"),
                "id", "logout-button", "data-testid", "logout-button", "aria-label", "Çıkış yap", "role", "button", "href", "#");
        ElementSnapshot other = el("a", "İletişim", List.of("nav#main-nav"), "id", "nav-contact", "href", "/contact");

        List<HeuristicMatcher.Scored> ranked = matcher.rank(fp, List.of(other, link));

        assertEquals(link, ranked.get(0).candidate());
        assertTrue(ranked.get(0).score() > 0.7);
    }

    @Test
    void unrelatedElementsScoreLow() {
        ElementSnapshot fp = el("input", "", List.of("form#login-form"),
                "id", "login-username", "name", "username", "placeholder", "Kullanıcı adınızı girin", "type", "text");
        ElementSnapshot search = el("input", "", List.of("div.toolbar"),
                "id", "product-search", "name", "search", "placeholder", "Ürün ara...", "type", "search");

        assertTrue(matcher.score(fp, search).score() < 0.5);
    }

    @Test
    void diffListsChangedAttributesOnly() {
        ElementSnapshot old = addToCart(1, "add-to-cart-", "add-to-cart-");
        ElementSnapshot now = addToCart(1, "btn-add-", "product-add-btn-");

        Map<String, String[]> changes = HealingEngine.diff(new Fingerprint("k", "#add-to-cart-1", "/", old), now);

        assertEquals(List.of("id", "data-testid"), List.copyOf(changes.keySet()));
        assertEquals("btn-add-1", changes.get("id")[1]);
    }
}
