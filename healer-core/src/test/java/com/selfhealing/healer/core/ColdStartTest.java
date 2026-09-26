package com.selfhealing.healer.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Healing an element that was never seen working: the fingerprint comes from the selector, then is learned. */
class ColdStartTest {

    @Test
    void derivesFingerprintFromSelector() {
        ElementSnapshot byId = SelectorFingerprint.derive("#booking-reference-fe465c1e");
        assertEquals("booking-reference-fe465c1e", byId.attr("id"));

        ElementSnapshot byAttr = SelectorFingerprint.derive("form.lookup input[data-testid='passenger-surname-input-fe465c1e']");
        assertEquals("input", byAttr.getTag());
        assertEquals("passenger-surname-input-fe465c1e", byAttr.attr("data-testid"));
        assertNull(byAttr.attr("class"), "classes of an ancestor do not describe the element");

        ElementSnapshot byText = SelectorFingerprint.derive("button.primary:has-text('Retrieve journey')");
        assertEquals("button", byText.getTag());
        assertEquals("primary", byText.attr("class"));
        assertEquals("Retrieve journey", byText.getText());

        assertNull(SelectorFingerprint.derive("div > span"));
    }

    @Test
    void generatedIdPartsDoNotHideTheSameIdentifier() {
        assertEquals("booking reference", Similarity.withoutGenerated("booking-reference-fe465c1e"));
        assertEquals("booking reference", Similarity.withoutGenerated("booking-reference-45901727"));
        assertEquals("add to cart 8", Similarity.withoutGenerated("add-to-cart-8"));
        assertTrue(Similarity.identifier("booking-reference-fe465c1e", "booking-reference-3fc4aa85") >= 0.95);
        assertTrue(Similarity.identifier("booking-reference-fe465c1e", "passenger-surname-3fc4aa85") < 0.5);
    }

    @Test
    void healsWithoutRecordedFingerprintAndLearnsOne(@TempDir Path dir) {
        HealingEngineTest.FakePage page = new HealingEngineTest.FakePage();
        page.elements.add(field("booking-reference-3fc4aa85", "booking-reference-input-3fc4aa85", "journey-3fc4aa85", "TFH-2026"));
        page.elements.add(field("passenger-surname-3fc4aa85", "passenger-surname-input-3fc4aa85", "identity-3fc4aa85", "IPEK"));
        page.elements.add(HeuristicMatcherTest.el("button", "Retrieve journey", List.of("form"),
                "id", "recover-journey-3fc4aa85", "data-testid", "find-booking-button-3fc4aa85"));

        HealingEngine engine = engine(dir);
        List<String> events = new java.util.ArrayList<>();
        HealingListener listener = new HealingListener() {
            @Override public void fingerprintDerived(String key, ElementSnapshot derived) { events.add("derived"); }
            @Override public void fingerprintLearned(String key) { events.add("learned"); }
        };
        HealingEngine.Result result = engine.heal("Lab.bookingReference",
                "[data-testid='booking-reference-input-fe465c1e']", page, listener);

        assertTrue(result.healed(), () -> result.failureReason());
        assertEquals("#booking-reference-3fc4aa85", result.suggestion().selector());
        assertEquals(List.of("derived", "learned"), events);

        Fingerprint learned = new JsonFileMap<>(dir.resolve("fingerprints.json"), Fingerprint.class)
                .get("Lab.bookingReference").orElse(null);
        assertNotNull(learned);
        assertEquals("learned", learned.getOrigin());
        assertEquals("TFH-2026", learned.getElement().attr("placeholder"));
    }

    @Test
    void refusesWhenTheSelectorSaysNothingUseful(@TempDir Path dir) {
        HealingEngineTest.FakePage page = new HealingEngineTest.FakePage();
        page.elements.add(field("booking-reference-3fc4aa85", "booking-reference-input-3fc4aa85", "journey-3fc4aa85", "TFH-2026"));
        HealingEngine.Result result = engine(dir).heal("Lab.x", "div > span", page);
        assertFalse(result.healed());
        assertTrue(result.failureReason().contains("no fingerprint"));
    }

    private static ElementSnapshot field(String id, String testId, String name, String placeholder) {
        return HeuristicMatcherTest.el("input", "", List.of("form"),
                "id", id, "data-testid", testId, "name", name, "placeholder", placeholder);
    }

    private static HealingEngine engine(Path dir) {
        Properties p = new Properties();
        p.setProperty("healer.storeDir", dir.toString());
        p.setProperty("healer.llm.enabled", "false");
        return new HealingEngine(HealerConfig.from(p), null);
    }
}
