package com.selfhealing.healer.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HealingEngineTest {

    /** In-memory page: selectors are "#id". */
    static class FakePage implements PageAdapter {
        final List<ElementSnapshot> elements = new ArrayList<>();

        @Override public String url() { return "http://test/page"; }
        @Override public List<ElementSnapshot> collectCandidates() { return elements; }
        @Override public int count(String selector) {
            return (int) elements.stream().filter(e -> selector.equals(e.getSelector())).count();
        }
        @Override public ElementSnapshot snapshot(String selector) {
            return elements.stream().filter(e -> selector.equals(e.getSelector())).findFirst().orElse(null);
        }
    }

    private static HealingEngine engine(Path dir, boolean llm, LocatorHealer healer) {
        Properties p = new Properties();
        p.setProperty("healer.storeDir", dir.toString());
        p.setProperty("healer.llm.enabled", String.valueOf(llm));
        return new HealingEngine(HealerConfig.from(p), healer);
    }

    private static ElementSnapshot username(String id, String testId) {
        return HeuristicMatcherTest.el("input", "", List.of("form#login-form"),
                "id", id, "data-testid", testId, "name", "username", "placeholder", "Kullanıcı adınızı girin", "type", "text");
    }

    @Test
    void healsRenamedElementAndCachesIt(@TempDir Path dir) {
        HealingEngine engine = engine(dir, false, null);
        engine.remember("Login.username", "#login-username", "/login", username("login-username", "login-username-input"));

        FakePage page = new FakePage();
        page.elements.add(username("user-name", "username-input"));
        page.elements.add(HeuristicMatcherTest.el("button", "Giriş Yap", List.of("form#login-form"), "id", "login-button"));

        HealingEngine.Result first = engine.heal("Login.username", "#login-username", page);
        assertTrue(first.healed());
        assertEquals("#user-name", first.suggestion().selector());
        assertEquals(HealingSuggestion.Source.HEURISTIC, first.suggestion().source());

        // A new engine reads the persisted cache.
        HealingEngine.Result second = engine(dir, false, null).heal("Login.username", "#login-username", page);
        assertEquals(HealingSuggestion.Source.CACHE, second.suggestion().source());
    }

    @Test
    void refusesWhenNothingIsSimilarEnough(@TempDir Path dir) {
        HealingEngine engine = engine(dir, false, null);
        engine.remember("Login.username", "#login-username", "/login", username("login-username", "login-username-input"));

        FakePage page = new FakePage();
        page.elements.add(HeuristicMatcherTest.el("a", "İletişim", List.of("nav"), "id", "nav-contact", "href", "/contact"));

        HealingEngine.Result result = engine.heal("Login.username", "#login-username", page);
        assertFalse(result.healed());
        assertTrue(result.failureReason().contains("LLM healer disabled"));
    }

    @Test
    void fallsBackToLlmAndValidatesItsAnswer(@TempDir Path dir) {
        FakePage page = new FakePage();
        ElementSnapshot target = HeuristicMatcherTest.el("input", "", List.of("div"), "id", "x-42", "type", "text");
        page.elements.add(target);

        LocatorHealer llm = request -> new LocatorHealer.Answer(Optional.of(new HealingSuggestion("#x-42",
                HealingSuggestion.Source.LLM, 0.9, "only text input on the page", null, null)), null, java.util.Map.of());
        HealingEngine engine = engine(dir, true, llm);
        engine.remember("Login.username", "#login-username", "/login", username("login-username", "login-username-input"));

        HealingEngine.Result result = engine.heal("Login.username", "#login-username", page);
        assertEquals(HealingSuggestion.Source.LLM, result.suggestion().source());

        LocatorHealer wrong = request -> new LocatorHealer.Answer(Optional.of(new HealingSuggestion("#does-not-exist",
                HealingSuggestion.Source.LLM, 0.99, "hallucinated", null, null)), null, java.util.Map.of());
        HealingEngine strict = engine(dir.resolve("other"), true, wrong);
        strict.remember("Login.username", "#login-username", "/login", username("login-username", "login-username-input"));
        assertFalse(strict.heal("Login.username", "#login-username", page).healed(), "unvalidated selector must be rejected");
    }

    /** The login page after a release: every name of the username field changed, "username" is still in them. */
    private static FakePage changedLogin(boolean withUsername) {
        FakePage page = new FakePage();
        page.elements.add(HeuristicMatcherTest.el("div", "", List.of("form#login-form"), "id", "login-error", "data-testid", "login-error-message"));
        page.elements.add(HeuristicMatcherTest.el("form", "Username Password Sign in", List.of("div#login-card"), "id", "login-form", "data-testid", "login-form"));
        if (withUsername) {
            ElementSnapshot user = HeuristicMatcherTest.el("input", "", List.of("form#login-form"),
                    "id", "user-name", "data-testid", "username-input", "name", "username", "type", "text");
            user.setLabelText("Username");
            page.elements.add(user);
        }
        ElementSnapshot pass = HeuristicMatcherTest.el("input", "", List.of("form#login-form"),
                "id", "login-password", "data-testid", "password-input", "name", "pass", "type", "password");
        pass.setLabelText("Password");
        page.elements.add(pass);
        return page;
    }

    @Test
    void coldStartFillFindsTheRenamedFieldNeverADiv(@TempDir Path dir) {
        HealingEngine engine = engine(dir, false, null);   // nothing recorded: only the old selector is known
        ActionHint.set("fill");
        try {
            HealingEngine.Result r = engine.heal("LoginPage.username", "#login-username", changedLogin(true));
            assertTrue(r.healed(), "login-username -> user-name / username-input: " + r.failureReason());
            assertEquals("#user-name", r.suggestion().selector());
        } finally {
            ActionHint.clear();
        }
    }

    @Test
    void coldStartFillOfARemovedFieldIsNotHealedOntoAnotherOne(@TempDir Path dir) {
        HealingEngine engine = engine(dir, false, null);
        ActionHint.set("fill");
        try {
            HealingEngine.Result r = engine.heal("LoginPage.username", "#login-username", changedLogin(false));
            assertFalse(r.healed(), "the password field is not the username field");
        } finally {
            ActionHint.clear();
        }
    }

    @Test
    void coldStartButtonIsFoundByItsWordsNotByAParagraph(@TempDir Path dir) {
        FakePage page = changedLogin(true);
        page.elements.add(HeuristicMatcherTest.el("button", "Log In", List.of("form#login-form"),
                "id", "btn-signin", "data-testid", "signin-button", "type", "submit"));
        page.elements.add(HeuristicMatcherTest.el("p", "Demo user: standard_user / secret123", List.of("form#login-form"),
                "id", "demo-hint", "data-testid", "login-demo-hint"));
        ActionHint.set("click");
        try {
            HealingEngine.Result r = engine(dir, false, null).heal("LoginPage.loginButton", "[data-testid='login-submit-button']", page);
            assertTrue(r.healed(), "login-submit-button -> the \"Log In\" button: " + r.failureReason());
            assertEquals("#btn-signin", r.suggestion().selector());
        } finally {
            ActionHint.clear();
        }
    }

    @Test
    void coldStartWithoutAnActionStaysAsStrictAsBefore(@TempDir Path dir) {
        HealingEngine.Result r = engine(dir, false, null).heal("LoginPage.username", "#login-username", changedLogin(true));
        assertFalse(r.healed() && !"#user-name".equals(r.suggestion().selector()), "never a div or form");
    }

    @Test
    void neverHealsOntoAnElementAnotherLocatorOwns(@TempDir Path dir) {
        HealingEngine engine = engine(dir, false, null);
        ElementSnapshot reviews = HeuristicMatcherTest.el("button", "Reviews", List.of("div.product-tabs"),
                "id", "tab-reviews", "data-testid", "tab-reviews", "class", "tab");
        ElementSnapshot specs = HeuristicMatcherTest.el("button", "Specifications", List.of("div.product-tabs"),
                "id", "tab-specs", "data-testid", "tab-specs", "class", "tab");
        engine.remember("Product.reviewsTab", "#tab-reviews", "/p", reviews);
        engine.remember("Product.specsTab", "#tab-specs", "/p", specs);
        // the same element as reviews, under a second key: must not block healing reviews
        engine.remember("Product.reviewsByText", "button:has-text('Reviews')", "/p", reviews);

        FakePage page = new FakePage();
        page.elements.add(specs);   // the reviews tab was removed
        HealingEngine.Result refused = engine.heal("Product.reviewsTab", "#tab-reviews", page);
        assertFalse(refused.healed(), "specs belongs to another locator");
        assertTrue(refused.failureReason().contains("element of locator 'Product.specsTab'"),
                "the message says why a good score was not enough: " + refused.failureReason());

        FakePage renamed = new FakePage();
        renamed.elements.add(specs);
        renamed.elements.add(HeuristicMatcherTest.el("button", "Reviews", List.of("div.product-tabs"),
                "id", "tab-reviews-v2", "data-testid", "tab-reviews-v2", "class", "tab"));
        assertEquals("#tab-reviews-v2", engine.heal("Product.reviewsTab", "#tab-reviews", renamed).suggestion().selector());
    }

    @Test
    void anUnchangedElementDoesNotRewriteTheFingerprintFile(@TempDir Path dir) throws Exception {
        HealingEngine engine = engine(dir, false, null);
        engine.remember("Login.username", "#login-username", "/login", username("login-username", "login-username-input"));
        Path file = dir.resolve("fingerprints.json");
        String first = java.nio.file.Files.readString(file);
        Thread.sleep(20);
        engine.remember("Login.username", "#login-username", "/login", username("login-username", "login-username-input"));
        assertEquals(first, java.nio.file.Files.readString(file), "same element: file untouched (no new timestamp)");

        engine.remember("Login.username", "#login-username", "/login", username("login-username", "user-input"));
        assertFalse(first.equals(java.nio.file.Files.readString(file)), "changed element: fingerprint updated");
    }

    @Test
    void anUnchangedElementIsRecognisedAfterReadingTheFileAgain(@TempDir Path dir) throws Exception {
        // the next run reads the file: empty values (label "", text "") were not written and come back as null
        ElementSnapshot badge = HeuristicMatcherTest.el("span", "", List.of("header"), "id", "cart-count");
        badge.setLabelText("");
        engine(dir, false, null).remember("Header.cartBadge", "#cart-count", "/products", badge);
        Path file = dir.resolve("fingerprints.json");
        String first = java.nio.file.Files.readString(file);
        Thread.sleep(20);
        ElementSnapshot again = HeuristicMatcherTest.el("span", "", List.of("header"), "id", "cart-count");
        again.setLabelText("");
        engine(dir, false, null).remember("Header.cartBadge", "#cart-count", "/products", again);
        assertEquals(first, java.nio.file.Files.readString(file), "same element in a new run: file untouched");
    }

    @Test
    void positionAndNumbersInTheTextDoNotCountAsAChange(@TempDir Path dir) throws Exception {
        ElementSnapshot before = HeuristicMatcherTest.el("div", "Order number: SL-37307842", List.of("main"), "data-testid", "order-success");
        engine(dir, false, null).remember("Cart.orderSuccess", "[data-testid='order-success']", "/cart", before);
        Path file = dir.resolve("fingerprints.json");
        String first = java.nio.file.Files.readString(file);
        ElementSnapshot after = HeuristicMatcherTest.el("div", "Order number: SL-14256909", List.of("main"), "data-testid", "order-success");
        after.setY(before.getY() + 140);
        engine(dir, false, null).remember("Cart.orderSuccess", "[data-testid='order-success']", "/cart", after);
        assertEquals(first, java.nio.file.Files.readString(file), "other order number, scrolled: same element");

        ElementSnapshot renamed = HeuristicMatcherTest.el("div", "Order received", List.of("main"), "data-testid", "order-success");
        engine(dir, false, null).remember("Cart.orderSuccess", "[data-testid='order-success']", "/cart", renamed);
        assertFalse(first.equals(java.nio.file.Files.readString(file)), "other text: fingerprint updated");
    }
}
