package com.selfhealing.healer.core;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Every locator used in the run with its {@link SelectorLint} rating and, when the element was found, a more stable
 * selector for it. Shown in the report's "Locator quality" section; suggestions can be applied with
 * {@code locator-improvements.patch}. Disable with {@code healer.lint=off}.
 */
public final class LocatorQuality {

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public static class Entry {
        public String key;
        public String selector;
        public SelectorLint.Risk risk;
        public List<String> reasons = List.of();
        /** A more stable selector for the same element (only when it rates better). */
        public String suggestion;
        public String sourceFile;
        public int sourceLine;
        /** True when the selector did not match in this run (it was healed or failed). */
        public boolean broken;
        public TreeSet<String> tests = new TreeSet<>();
        @JsonIgnore
        public boolean suggestionChecked;
    }

    private static final Map<String, Entry> ENTRIES = new LinkedHashMap<>();

    private LocatorQuality() {
    }

    /** Records that a locator was used by the current test; returns its entry (created and rated on first use). */
    public static synchronized Entry observe(String key, String selector, String sourceFile, int sourceLine) {
        Entry e = ENTRIES.computeIfAbsent(key + "\u0000" + selector, k -> {
            Entry n = new Entry();
            n.key = key;
            n.selector = selector;
            SelectorLint.Assessment a = SelectorLint.assess(selector);
            n.risk = a.risk();
            n.reasons = a.reasons();
            n.sourceFile = sourceFile;
            n.sourceLine = sourceLine;
            return n;
        });
        e.tests.add(HealingRecorder.currentTest());
        return e;
    }

    /** Keeps the suggestion only if it rates better than the current selector. */
    public static synchronized void suggest(Entry e, String suggestion) {
        e.suggestionChecked = true;
        if (suggestion == null || suggestion.equals(e.selector)) return;
        if (SelectorLint.assess(suggestion).risk().ordinal() < e.risk.ordinal()) e.suggestion = suggestion;
    }

    public static synchronized void markBroken(Entry e) {
        e.broken = true;
    }

    public static synchronized List<Entry> all() {
        return new ArrayList<>(ENTRIES.values());
    }

    /** Entries of several JVMs combined (same key and selector = one entry with the union of tests). */
    public static List<Entry> merge(List<List<Entry>> parts) {
        Map<String, Entry> out = new LinkedHashMap<>();
        for (List<Entry> part : parts) {
            for (Entry e : part) {
                Entry m = out.putIfAbsent(e.key + "\u0000" + e.selector, e);
                if (m == null) continue;
                m.tests.addAll(e.tests);
                m.broken |= e.broken;
                if (m.suggestion == null) m.suggestion = e.suggestion;
            }
        }
        return new ArrayList<>(out.values());
    }

    /** Riskiest first, then by key. */
    public static List<Entry> sorted(List<Entry> entries) {
        return entries.stream()
                .sorted(Comparator.comparing((Entry e) -> e.risk).reversed().thenComparing(e -> e.key))
                .toList();
    }

    /** Suggestions as pseudo heals, so {@link LocatorFixer} can turn them into a patch. */
    public static List<HealingEvent> asImprovements(List<Entry> entries) {
        List<HealingEvent> out = new ArrayList<>();
        for (Entry e : entries) {
            if (e.suggestion == null || e.broken) continue;
            HealingEvent h = new HealingEvent();
            h.key = e.key;
            h.status = HealingEvent.Status.SUGGESTED;
            h.originalSelector = e.selector;
            h.healedSelector = e.suggestion;
            h.sourceFile = e.sourceFile;
            h.sourceLine = e.sourceLine;
            h.test = e.tests.isEmpty() ? null : e.tests.first();
            out.add(h);
        }
        return out;
    }

    public static synchronized void clear() {
        ENTRIES.clear();
    }
}
