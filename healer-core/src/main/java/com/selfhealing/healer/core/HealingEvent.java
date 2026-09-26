package com.selfhealing.healer.core;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** One healing attempt, as it appears in the report. */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public class HealingEvent {

    public enum Status {
        /** Replacement used; the test continued. Reported as WARN. */
        HEALED,
        /** Replacement found but not used (suggest mode). */
        SUGGESTED,
        /** No trustworthy replacement; the step fails. */
        FAILED
    }

    /** Unique within the run; steps and tests refer to it. */
    public int id;
    public String test;
    public String key;
    public Status status;
    public String originalSelector;
    public String healedSelector;
    /** Where the locator was declared, relative to a source root (com/acme/pages/LoginPage.java). */
    public String sourceFile;
    public int sourceLine;
    public HealingSuggestion.Source source;
    public double confidence;
    public String reasoning;
    public String pageUrl;
    public Instant timestamp = Instant.now();
    public String originalElement;
    public String healedElement;
    /** attribute → [old, new] */
    public Map<String, String[]> changes;
    /** Best candidates when healing failed, for debugging. */
    public List<String> topCandidates;
    public String screenshot;
    public String llmModel;
    public long llmInputTokens;
    public long llmOutputTokens;
    public double llmCostUsd;
    /** The healing steps as shown on the demo overlay and in the console. */
    public List<HealingTrace.Line> trace;
    /** Best heuristic candidates: "score|description|selector". */
    public List<Candidate> candidates;
    /** Time spent waiting for the original selector before healing started. */
    public long probeWaitMs;
    /** Time the healing pipeline itself took (page scan, scoring, Claude, validation). */
    public long healDurationMs;
    /** True when this test reused a heal made earlier in the same run (no new work, no cost). */
    public boolean reused;

    public record Candidate(double score, String element, String selector) {
    }

    public String summaryLine() {
        return switch (status) {
            case HEALED, SUGGESTED -> String.format("%s %s: '%s' -> '%s' (%s, confidence %.2f)",
                    status, key, originalSelector, healedSelector, source, confidence);
            case FAILED -> String.format("FAILED %s: '%s' could not be healed - %s", key, originalSelector, reasoning);
        };
    }
}
