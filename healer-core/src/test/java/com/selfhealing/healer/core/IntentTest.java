package com.selfhealing.healer.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Plain-language steps: healer.find("Login.submit", "the Sign in button"). */
class IntentTest {

    private static ElementSnapshot el(String tag, String text, String label, String... attrs) {
        ElementSnapshot e = HeuristicMatcherTest.el(tag, text, List.of("form#login"), attrs);
        e.setLabelText(label);
        e.setVisible(true);
        return e;
    }

    private static HealingEngineTest.FakePage loginPage() {
        HealingEngineTest.FakePage page = new HealingEngineTest.FakePage();
        page.elements.add(el("input", "", "Email", "id", "email", "type", "email"));
        page.elements.add(el("input", "", null, "id", "pw", "type", "password", "placeholder", "Password"));
        page.elements.add(el("input", "", "Remember me", "id", "remember", "type", "checkbox"));
        page.elements.add(el("select", "", null, "id", "country", "aria-label", "Country"));
        page.elements.add(el("button", "Sign in", null, "id", "go", "type", "submit"));
        page.elements.add(el("a", "Sign up", null, "id", "register", "href", "/register"));
        return page;
    }

    private static String best(String description) {
        List<IntentMatcher.Match> ranked = IntentMatcher.rank(description, loginPage().elements);
        return ranked.get(0).element().getSelector();
    }

    @Test
    void matchesDescriptionsToElementsByTextLabelAndKind() {
        assertEquals("#go", best("the Sign in button"));
        assertEquals("#email", best("email field"));
        assertEquals("#pw", best("password"));
        assertEquals("#remember", best("Remember me checkbox"));
        assertEquals("#country", best("country dropdown"));
        assertEquals("#register", best("Sign up link"));
    }

    @Test
    void resolvesCachesAndRefusesToGuessBetweenLookAlikes(@TempDir Path dir) {
        Properties p = new Properties();
        p.setProperty("healer.storeDir", dir.toString());
        HealingEngine engine = new HealingEngine(HealerConfig.from(p), null);

        HealingEngine.Result first = engine.resolveIntent("Login.submit", "the Sign in button", loginPage(), HealingListener.NONE);
        assertTrue(first.healed(), first.failureReason());
        assertEquals("#go", first.suggestion().selector());
        assertEquals(HealingSuggestion.Source.HEURISTIC, first.suggestion().source());
        assertTrue(engine.fingerprint("Login.submit").isPresent(), "fingerprint saved: later changes are healed");

        HealingEngine.Result again = engine.resolveIntent("Login.submit", "the Sign in button", loginPage(), HealingListener.NONE);
        assertEquals(HealingSuggestion.Source.CACHE, again.suggestion().source(), "later runs cost nothing");

        HealingEngineTest.FakePage list = new HealingEngineTest.FakePage();
        for (int i = 1; i <= 3; i++) list.elements.add(el("button", "Add to cart", null, "id", "add-" + i));
        HealingEngine.Result ambiguous = engine.resolveIntent("Shop.add", "the Add to cart button", list, HealingListener.NONE);
        assertFalse(ambiguous.healed(), "three identical buttons: never guess");
    }
}
