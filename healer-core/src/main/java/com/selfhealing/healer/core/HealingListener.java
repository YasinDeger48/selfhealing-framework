package com.selfhealing.healer.core;

import java.util.List;

/**
 * Observes each step of a healing attempt, e.g. to log it or to draw it on the page.
 * All methods are optional.
 */
public interface HealingListener {

    /** The original selector did not match within the probe timeout. */
    default void broken(String key, String selector, long waitedMs) { }

    default void cacheHit(String key, HealingEngine.CachedHeal heal) { }

    /** No recorded fingerprint: one was derived from the selector (cold start). */
    default void fingerprintDerived(String key, ElementSnapshot derived) { }

    /** After a cold-start heal, the healed element's fingerprint was stored for future runs. */
    default void fingerprintLearned(String key) { }

    /** Local scoring finished; {@code ranked} is sorted best first. */
    default void heuristicRanked(String key, List<HeuristicMatcher.Scored> ranked, double minConfidence,
                                 double minMargin, boolean accepted) { }

    default void llmRequested(String key, String model, int candidates) { }

    default void llmAnswered(String key, LocatorHealer.Answer answer, long elapsedMs) { }

    /** The chosen selector was checked against the live page. */
    default void validated(String key, String selector, int matches) { }

    default void finished(String key, HealingEngine.Result result) { }

    HealingListener NONE = new HealingListener() { };

    /** Calls several listeners in order. */
    static HealingListener of(List<HealingListener> listeners) {
        return new HealingListener() {
            @Override public void broken(String k, String s, long w) { listeners.forEach(l -> l.broken(k, s, w)); }
            @Override public void cacheHit(String k, HealingEngine.CachedHeal h) { listeners.forEach(l -> l.cacheHit(k, h)); }
            @Override public void fingerprintDerived(String k, ElementSnapshot d) { listeners.forEach(l -> l.fingerprintDerived(k, d)); }
            @Override public void fingerprintLearned(String k) { listeners.forEach(l -> l.fingerprintLearned(k)); }
            @Override public void heuristicRanked(String k, List<HeuristicMatcher.Scored> r, double c, double m, boolean a) {
                listeners.forEach(l -> l.heuristicRanked(k, r, c, m, a));
            }
            @Override public void llmRequested(String k, String model, int n) { listeners.forEach(l -> l.llmRequested(k, model, n)); }
            @Override public void llmAnswered(String k, LocatorHealer.Answer a, long ms) { listeners.forEach(l -> l.llmAnswered(k, a, ms)); }
            @Override public void validated(String k, String s, int n) { listeners.forEach(l -> l.validated(k, s, n)); }
            @Override public void finished(String k, HealingEngine.Result r) { listeners.forEach(l -> l.finished(k, r)); }
        };
    }
}
