package com.selfhealing.healer.core;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Pluggable last-resort healer, consulted when the local heuristic is not confident enough.
 * The Claude implementation lives in its own module; {@link #NONE} disables this stage.
 */
public interface LocatorHealer {

    /**
     * @param key              logical element name, e.g. {@code LoginPage.username}
     * @param originalSelector the selector that no longer matches
     * @param fingerprint      the element as it looked when the selector last worked
     * @param candidates       page elements, best heuristic matches first
     * @param pageUrl          current page
     */
    record Request(String key, String originalSelector, Fingerprint fingerprint,
                   List<HeuristicMatcher.Scored> candidates, String pageUrl) {
    }

    /**
     * @param suggestion the chosen element, or empty when the healer found no trustworthy match
     * @param usage      tokens and cost spent - also when no match was found (null if nothing was called)
     * @param masked     values hidden by the privacy filter before sending, by type (e.g. EMAIL → 2)
     */
    record Answer(Optional<HealingSuggestion> suggestion, HealingSuggestion.LlmUsage usage, Map<String, Integer> masked) {
        public static Answer none() {
            return new Answer(Optional.empty(), null, Map.of());
        }
    }

    /**
     * A plain-language step: find the element the description names (no selector, no fingerprint yet).
     *
     * @param description e.g. "the Sign in button", in any language
     * @param candidates  page elements, best local matches first
     */
    record IntentRequest(String key, String description, List<HeuristicMatcher.Scored> candidates, String pageUrl) {
    }

    Answer suggest(Request request);

    /** Optional: finds the element for a description. Healers without this ability find nothing. */
    default Answer findByIntent(IntentRequest request) {
        return Answer.none();
    }

    LocatorHealer NONE = request -> Answer.none();
}
