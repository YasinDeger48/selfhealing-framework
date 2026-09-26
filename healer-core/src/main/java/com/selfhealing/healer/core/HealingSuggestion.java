package com.selfhealing.healer.core;

import java.util.Map;

/**
 * A replacement selector for a broken one.
 *
 * @param selector   the new selector (validated to match exactly one element before use)
 * @param source     which stage produced it
 * @param confidence 0..1
 * @param reasoning  why this element was chosen, for the report
 * @param element    the element the new selector points to
 * @param changes    attribute → [old value, new value] for everything that differs from the fingerprint
 * @param usage      tokens spent when an LLM produced the suggestion, else null
 */
public record HealingSuggestion(String selector, Source source, double confidence, String reasoning,
                                ElementSnapshot element, Map<String, String[]> changes, LlmUsage usage) {

    public enum Source { CACHE, HEURISTIC, LLM }

    /** @param model the model(s) asked, e.g. "claude-haiku-4-5 -> claude-opus-5" after an escalation */
    public record LlmUsage(String model, long inputTokens, long outputTokens, double costUsd) {
    }

    public HealingSuggestion(String selector, Source source, double confidence, String reasoning,
                             ElementSnapshot element, Map<String, String[]> changes) {
        this(selector, source, confidence, reasoning, element, changes, null);
    }
}
