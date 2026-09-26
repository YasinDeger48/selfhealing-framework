package com.selfhealing.healer.claude;

import com.selfhealing.healer.core.ElementSnapshot;
import com.selfhealing.healer.core.Fingerprint;
import com.selfhealing.healer.core.HealingSuggestion;
import com.selfhealing.healer.core.HeuristicMatcher;
import com.selfhealing.healer.core.LocatorHealer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClaudeLocatorHealerTest {

    private static final HealingSuggestion.LlmUsage USAGE = new HealingSuggestion.LlmUsage("claude-haiku-4-5", 100, 10, 0.00015);

    private static HeuristicMatcher.Scored candidate(String id, String text) {
        ElementSnapshot e = new ElementSnapshot();
        e.setTag("button");
        e.setText(text);
        e.getAttributes().put("id", id);
        e.setSelector("#" + id);
        return new HeuristicMatcher.Scored(e, 0.4, Map.of());
    }

    private final List<HeuristicMatcher.Scored> candidates = List.of(
            candidate("btn-checkout-final", "Satın Al"), candidate("continue", "Alışverişe Devam Et"));

    @Test
    void mapsIndexToTheCandidateSelector() {
        Optional<HealingSuggestion> s = ClaudeLocatorHealer.parse(
                "{\"candidateIndex\":0,\"confidence\":0.93,\"reasoning\":\"same submit button\"}", candidates, USAGE);

        assertEquals("#btn-checkout-final", s.orElseThrow().selector());
        assertEquals(0.93, s.get().confidence());
        assertEquals(USAGE, s.get().usage());
    }

    @Test
    void minusOneOutOfRangeOrGarbageMeansNoMatch() {
        assertTrue(ClaudeLocatorHealer.parse("{\"candidateIndex\":-1,\"confidence\":0.2,\"reasoning\":\"none\"}", candidates, USAGE).isEmpty());
        assertTrue(ClaudeLocatorHealer.parse("{\"candidateIndex\":7,\"confidence\":0.9,\"reasoning\":\"x\"}", candidates, USAGE).isEmpty());
        assertTrue(ClaudeLocatorHealer.parse("not json", candidates, USAGE).isEmpty());
    }

    @Test
    void promptCarriesNameFingerprintAndNumberedCandidates() {
        ElementSnapshot old = new ElementSnapshot();
        old.setTag("button");
        old.setText("Siparişi Tamamla");
        old.getAttributes().put("id", "place-order");
        LocatorHealer.Request request = new LocatorHealer.Request("CartPage.placeOrder", "#place-order",
                new Fingerprint("CartPage.placeOrder", "#place-order", "/cart", old), candidates, "http://x/cart");

        String prompt = ClaudeLocatorHealer.buildPrompt(request,
                new com.selfhealing.healer.core.PrivacyFilter(com.selfhealing.healer.core.HealerConfig.from(new java.util.Properties())).session());

        assertTrue(prompt.contains("\"el\":\"CartPage.placeOrder\""));
        assertTrue(prompt.contains("Siparişi Tamamla"));
        assertTrue(prompt.contains("\"i\":1"));
    }

    @Test
    void personalDataIsMaskedBeforeItLeaves() {
        ElementSnapshot header = new ElementSnapshot();
        header.setTag("span");
        header.setText("Jane Doe · jane.doe@example.com · +90 532 123 45 67");
        header.getAttributes().put("title", "jane.doe@example.com");
        header.setSelector("#user");
        LocatorHealer.Request request = new LocatorHealer.Request("Header.user", "#user-email", null,
                List.of(new HeuristicMatcher.Scored(header, 0.3, Map.of())), "http://x");
        com.selfhealing.healer.core.PrivacyFilter.Session masking =
                new com.selfhealing.healer.core.PrivacyFilter(com.selfhealing.healer.core.HealerConfig.from(new java.util.Properties())).session();

        String prompt = ClaudeLocatorHealer.buildPrompt(request, masking);

        assertTrue(!prompt.contains("jane.doe@example.com") && !prompt.contains("532 123"), prompt);
        assertTrue(prompt.contains("[EMAIL]") && prompt.contains("[PHONE]"), prompt);
        assertEquals(Map.of("EMAIL", 2, "PHONE", 1), masking.counts());
    }
}
