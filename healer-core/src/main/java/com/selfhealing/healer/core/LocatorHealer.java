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

    Answer suggest(Request request);

    LocatorHealer NONE = request -> Answer.none();
}
