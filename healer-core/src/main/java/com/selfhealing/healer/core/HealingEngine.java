package com.selfhealing.healer.core;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Driver-independent healing pipeline: healing cache → local heuristic → pluggable LLM healer.
 * Every suggestion is validated against the live page (must match exactly one element).
 */
public class HealingEngine {

    /** A previously healed selector, reused while the original stays broken. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CachedHeal {
        public String originalSelector;
        public String healedSelector;
        public HealingSuggestion.Source source;
        public double confidence;
        public String reasoning;
        public Instant healedAt;
    }

    /** @param llmUsage what the LLM stage cost, also when it found nothing (null if it was not called) */
    public record Result(HealingSuggestion suggestion, String failureReason, List<HeuristicMatcher.Scored> ranked,
                         HealingSuggestion.LlmUsage llmUsage) {
        public Result(HealingSuggestion suggestion, String failureReason, List<HeuristicMatcher.Scored> ranked) {
            this(suggestion, failureReason, ranked, suggestion == null ? null : suggestion.usage());
        }

        public boolean healed() {
            return suggestion != null;
        }
    }

    private final HealerConfig config;
    private final JsonFileMap<Fingerprint> fingerprints;
    private final JsonFileMap<CachedHeal> cache;
    private final HeuristicMatcher matcher;
    private final LocatorHealer llm;

    public HealingEngine(HealerConfig config, LocatorHealer llm) {
        this.config = config;
        this.fingerprints = new JsonFileMap<>(config.storeDir().resolve("fingerprints.json"), Fingerprint.class);
        this.cache = new JsonFileMap<>(config.storeDir().resolve("healed-locators.json"), CachedHeal.class);
        this.matcher = new HeuristicMatcher();
        this.llm = llm == null ? LocatorHealer.NONE : llm;
        LlmPricing.configure(config);
    }

    public HealerConfig config() {
        return config;
    }

    /** Stores the element behind a working selector; the original selector works again, so drop any cached heal. */
    public void remember(String key, String selector, String pageUrl, ElementSnapshot element) {
        fingerprints.put(key, new Fingerprint(key, selector, pageUrl, element));
        cache.remove(key);
    }

    /** The heal stored for this element by an earlier run, if it was made for the same original selector. */
    public Optional<CachedHeal> cachedHeal(String key, String originalSelector) {
        return cache.get(key).filter(c -> originalSelector.equals(c.originalSelector));
    }

    public Optional<Fingerprint> fingerprint(String key) {
        return fingerprints.get(key);
    }

    public Result heal(String key, String originalSelector, PageAdapter page) {
        return heal(key, originalSelector, page, HealingListener.NONE);
    }

    public Result heal(String key, String originalSelector, PageAdapter page, HealingListener listener) {
        Result result = runPipeline(key, originalSelector, page, listener);
        listener.finished(key, result);
        return result;
    }

    private Result runPipeline(String key, String originalSelector, PageAdapter page, HealingListener listener) {
        Fingerprint fp = fingerprints.get(key).orElse(null);

        // 1. Healing cache (no page scan needed)
        Optional<CachedHeal> cached = cachedHeal(key, originalSelector);
        if (cached.isPresent() && page.count(cached.get().healedSelector) == 1) {
            CachedHeal c = cached.get();
            listener.cacheHit(key, c);
            ElementSnapshot element = page.snapshot(c.healedSelector);
            String reasoning = "Reused heal from " + c.healedAt + " (" + c.source + "): " + c.reasoning;
            return new Result(new HealingSuggestion(c.healedSelector, HealingSuggestion.Source.CACHE, c.confidence,
                    reasoning, element, diff(fp, element)), null, List.of());
        }

        // Cold start: never seen working -> derive a fingerprint from the selector itself.
        boolean coldStart = fp == null || !"recorded".equals(fp.getOrigin());
        if (fp == null) {
            ElementSnapshot derived = SelectorFingerprint.derive(originalSelector);
            if (derived != null) {
                fp = new Fingerprint(key, originalSelector, page.url(), derived);
                fp.setOrigin("derived");
                listener.fingerprintDerived(key, derived);
            }
        }
        List<ElementSnapshot> candidates = page.collectCandidates();
        List<HeuristicMatcher.Scored> ranked = fp == null ? List.of() : matcher.rank(fp.getElement(), candidates);

        // Candidates the guards rule out whatever their score - another list item, the opposite control, a field for a
        // button - are neither accepted locally nor offered to the LLM (safer, and no LLM call if nothing is left).
        ElementSnapshot target = fp == null ? null : fp.getElement();
        // Nor is an element that another locator knows as its own (tab-specs when tab-reviews was removed).
        Set<String> others = otherKnownElements(key, target);
        List<HeuristicMatcher.Scored> plausible = target == null ? ranked
                : ranked.stream().filter(s -> HeuristicMatcher.compatible(target, s.candidate()))
                        .filter(s -> !isKnownElsewhere(s.candidate(), others)).toList();

        // 2. Local heuristic
        if (fp != null && !plausible.isEmpty()) {
            HeuristicMatcher.Scored best = plausible.get(0);
            double second = plausible.size() > 1 ? plausible.get(1).score() : 0;
            double margin = best.score() - second;
            boolean accepted = best.score() >= config.minConfidence() && margin >= config.minMargin()
                    && best.candidate().getSelector() != null;
            listener.heuristicRanked(key, ranked, config.minConfidence(), config.minMargin(), accepted);
            if (accepted) {
                int matches = page.count(best.candidate().getSelector());
                listener.validated(key, best.candidate().getSelector(), matches);
                if (matches == 1) {
                    HealingSuggestion s = new HealingSuggestion(best.candidate().getSelector(), HealingSuggestion.Source.HEURISTIC,
                            best.score(), explain(best, margin), best.candidate(), diff(fp, best.candidate()));
                    if (coldStart) learn(key, originalSelector, page.url(), best.candidate(), listener);
                    return new Result(cacheAndReturn(key, originalSelector, s), null, ranked);
                }
            }
        }

        // 3. LLM healer - only the best local matches are sent, to keep the prompt small
        HealingSuggestion.LlmUsage llmUsage = null;
        if (config.llmEnabled() && llm != LocatorHealer.NONE && (target == null || !plausible.isEmpty())) {
            List<HeuristicMatcher.Scored> top = ranked.isEmpty()
                    ? candidates.stream().map(c -> new HeuristicMatcher.Scored(c, 0, Map.of())).limit(config.llmCandidates()).toList()
                    : plausible.subList(0, Math.min(config.llmCandidates(), plausible.size()));
            listener.llmRequested(key, config.llmModel(), top.size());
            long t0 = System.currentTimeMillis();
            LocatorHealer.Answer answer = llm.suggest(new LocatorHealer.Request(key, originalSelector, fp, top, page.url()));
            Optional<HealingSuggestion> s = answer.suggestion();
            llmUsage = answer.usage();
            listener.llmAnswered(key, answer, System.currentTimeMillis() - t0);
            if (s.isPresent() && s.get().confidence() >= config.minConfidence()) {
                int matches = page.count(s.get().selector());
                listener.validated(key, s.get().selector(), matches);
                ElementSnapshot element = matches == 1 ? page.snapshot(s.get().selector()) : null;
                if (matches == 1 && (target == null || element == null || HeuristicMatcher.compatible(target, element))) {
                    HealingSuggestion validated = new HealingSuggestion(s.get().selector(), HealingSuggestion.Source.LLM,
                            s.get().confidence(), s.get().reasoning(), element, diff(fp, element), s.get().usage());
                    if (coldStart && element != null) learn(key, originalSelector, page.url(), element, listener);
                    return new Result(cacheAndReturn(key, originalSelector, validated), null, ranked);
                }
            }
        }

        String reason;
        if (fp == null) {
            reason = "no fingerprint recorded for '" + key + "' and nothing usable in the selector - run the test once against a working build";
        } else if (ranked.isEmpty()) {
            reason = "no candidate elements on the page";
        } else {
            HeuristicMatcher.Scored best = ranked.get(0);
            double margin = best.score() - (ranked.size() > 1 ? ranked.get(1).score() : 0);
            reason = String.format("best match %s scored %.2f (min %.2f), margin %.2f (min %.2f)%s",
                    best.candidate().describe(), best.score(), config.minConfidence(), margin, config.minMargin(),
                    config.llmEnabled() ? "; LLM healer did not return a valid match" : "; LLM healer disabled");
        }
        return new Result(null, reason, ranked, llmUsage);
    }

    /**
     * data-testid and id values of the elements other locators were recorded with - except locators recorded on the
     * same element as this one (two page objects may point at one element in different ways).
     */
    private Set<String> otherKnownElements(String key, ElementSnapshot target) {
        Set<String> mine = new java.util.HashSet<>();
        if (target != null) {
            for (String attr : List.of("data-testid", "id")) {
                String v = target.attr(attr);
                if (v != null && !v.isBlank()) mine.add(attr + "=" + v);
            }
        }
        Set<String> out = new java.util.HashSet<>();
        fingerprints.all().forEach((k, f) -> {
            if (k.equals(key) || f.getElement() == null) return;
            if (List.of("data-testid", "id").stream().anyMatch(a -> mine.contains(a + "=" + f.getElement().attr(a)))) return;
            for (String attr : List.of("data-testid", "id")) {
                String v = f.getElement().attr(attr);
                if (v != null && !v.isBlank()) out.add(attr + "=" + v);
            }
        });
        return out;
    }

    private static boolean isKnownElsewhere(ElementSnapshot c, Set<String> others) {
        for (String attr : List.of("data-testid", "id")) {
            String v = c.attr(attr);
            if (v != null && !v.isBlank() && others.contains(attr + "=" + v)) return true;
        }
        return false;
    }

    /** Stores the healed element as the fingerprint of an element that never had a recorded one. */
    private void learn(String key, String selector, String url, ElementSnapshot element, HealingListener listener) {
        Fingerprint learned = new Fingerprint(key, selector, url, element);
        learned.setOrigin("learned");
        fingerprints.put(key, learned);
        listener.fingerprintLearned(key);
    }

    /** Prefix of the "selector" under which a plain-language step is cached: intent:the Sign in button. */
    public static final String INTENT = "intent:";
    private static final double INTENT_MIN_SCORE = 0.75;
    private static final double INTENT_MIN_MARGIN = 0.15;

    /**
     * Finds the element a plain-language step describes. Order: the selector found by an earlier run (free) - the
     * element's recorded fingerprint if that selector broke (regular healing) - local matching of the description
     * (free) - the LLM. The result is cached and the element's fingerprint recorded, so later runs cost nothing.
     * Like healing, it never guesses: look-alikes the description cannot tell apart mean "not found".
     */
    public Result resolveIntent(String key, String description, PageAdapter page, HealingListener listener) {
        String selectorKey = INTENT + description;
        Optional<CachedHeal> cached = cachedHeal(key, selectorKey);
        if (cached.isPresent() && page.count(cached.get().healedSelector) == 1) {
            listener.cacheHit(key, cached.get());
            CachedHeal c = cached.get();
            return new Result(new HealingSuggestion(c.healedSelector, HealingSuggestion.Source.CACHE, c.confidence,
                    "Found before (" + c.source + ")", page.snapshot(c.healedSelector), Map.of()), null, List.of());
        }
        if (fingerprints.get(key).isPresent()) {
            Result healed = runPipeline(key, selectorKey, page, listener);
            if (healed.healed()) return healed;
        }

        List<ElementSnapshot> candidates = page.collectCandidates();
        List<HeuristicMatcher.Scored> ranked = IntentMatcher.rank(description, candidates).stream()
                .map(m -> new HeuristicMatcher.Scored(m.element(), m.score(), Map.of())).toList();
        double best = ranked.isEmpty() ? 0 : ranked.get(0).score();
        double margin = best - (ranked.size() > 1 ? ranked.get(1).score() : 0);
        boolean accepted = best >= INTENT_MIN_SCORE && margin >= INTENT_MIN_MARGIN;
        listener.heuristicRanked(key, ranked, INTENT_MIN_SCORE, INTENT_MIN_MARGIN, accepted);
        if (accepted) {
            ElementSnapshot el = ranked.get(0).candidate();
            int matches = page.count(el.getSelector());
            listener.validated(key, el.getSelector(), matches);
            if (matches == 1) {
                return foundByIntent(key, selectorKey, page, new HealingSuggestion(el.getSelector(), HealingSuggestion.Source.HEURISTIC,
                        best, String.format(Locale.ROOT, "Description match %.2f (margin %.2f)", best, margin), el, Map.of()), ranked);
            }
        }

        HealingSuggestion.LlmUsage usage = null;
        if (config.llmEnabled() && llm != LocatorHealer.NONE && !ranked.isEmpty()) {
            List<HeuristicMatcher.Scored> top = ranked.subList(0, Math.min(config.llmCandidates(), ranked.size()));
            listener.llmRequested(key, config.llmModel(), top.size());
            long t0 = System.currentTimeMillis();
            LocatorHealer.Answer answer = llm.findByIntent(new LocatorHealer.IntentRequest(key, description, top, page.url()));
            usage = answer.usage();
            listener.llmAnswered(key, answer, System.currentTimeMillis() - t0);
            Optional<HealingSuggestion> s = answer.suggestion();
            if (s.isPresent() && s.get().confidence() >= config.minConfidence()) {
                int matches = page.count(s.get().selector());
                listener.validated(key, s.get().selector(), matches);
                if (matches == 1) {
                    ElementSnapshot el = page.snapshot(s.get().selector());
                    return foundByIntent(key, selectorKey, page, new HealingSuggestion(s.get().selector(), HealingSuggestion.Source.LLM,
                            s.get().confidence(), s.get().reasoning(), el, Map.of(), s.get().usage()), ranked);
                }
            }
        }
        String reason = ranked.isEmpty() ? "no candidate elements on the page"
                : String.format(Locale.ROOT, "best match %s scored %.2f (min %.2f), margin %.2f (min %.2f)%s",
                        ranked.get(0).candidate().describe(), best, INTENT_MIN_SCORE, margin, INTENT_MIN_MARGIN,
                        config.llmEnabled() ? "; LLM found no clear match" : "; LLM disabled");
        return new Result(null, "no element clearly matches \"" + description + "\": " + reason, ranked, usage);
    }

    private Result foundByIntent(String key, String selectorKey, PageAdapter page, HealingSuggestion s,
                                 List<HeuristicMatcher.Scored> ranked) {
        if (s.element() != null) fingerprints.put(key, new Fingerprint(key, selectorKey, page.url(), s.element()));
        return new Result(cacheAndReturn(key, selectorKey, s), null, ranked);
    }

    private HealingSuggestion cacheAndReturn(String key, String originalSelector, HealingSuggestion s) {
        CachedHeal c = new CachedHeal();
        c.originalSelector = originalSelector;
        c.healedSelector = s.selector();
        c.source = s.source();
        c.confidence = s.confidence();
        c.reasoning = s.reasoning();
        c.healedAt = Instant.now();
        cache.put(key, c);
        return s;
    }

    private static String explain(HeuristicMatcher.Scored best, double margin) {
        String strong = best.signals().entrySet().stream()
                .filter(e -> e.getValue() >= 0.8)
                .map(Map.Entry::getKey)
                .collect(Collectors.joining(", "));
        String weak = best.signals().entrySet().stream()
                .filter(e -> e.getValue() < 0.5)
                .map(Map.Entry::getKey)
                .collect(Collectors.joining(", "));
        return String.format("Heuristic match %.2f (margin %.2f over next candidate). Matching: %s. Changed: %s.",
                best.score(), margin, strong.isEmpty() ? "-" : strong, weak.isEmpty() ? "-" : weak);
    }

    /** attribute → [old, new] for tag, tracked attributes and text. */
    public static Map<String, String[]> diff(Fingerprint fp, ElementSnapshot now) {
        Map<String, String[]> changes = new LinkedHashMap<>();
        if (fp == null || now == null) return changes;
        ElementSnapshot old = fp.getElement();
        compare(changes, "tag", old.getTag(), now.getTag());
        for (String attr : ElementSnapshot.TRACKED_ATTRIBUTES) {
            compare(changes, attr, old.attr(attr), now.attr(attr));
        }
        compare(changes, "text", old.getText(), now.getText());
        return changes;
    }

    private static void compare(Map<String, String[]> changes, String name, String a, String b) {
        String x = a == null ? "" : a.trim();
        String y = b == null ? "" : b.trim();
        if (!Objects.equals(x, y)) changes.put(name, new String[] {x, y});
    }
}
