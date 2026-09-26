package com.selfhealing.healer.core;

import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Scores page elements against a stored fingerprint without any network call.
 *
 * <p>Each signal the fingerprint has (id, data-testid, text, label, ancestors ...) contributes
 * {@code weight × similarity}; the score is the weighted average over those signals. A signal
 * the fingerprint lacks is skipped, so an element is never penalised for extra attributes.
 */
public class HeuristicMatcher {

    private static final Map<String, Double> WEIGHTS = Map.ofEntries(
            Map.entry("tag", 1.0),
            Map.entry("id", 2.0),
            Map.entry("data-testid", 2.5),
            Map.entry("data-qa", 1.5),
            Map.entry("name", 1.5),
            Map.entry("aria-label", 1.5),
            Map.entry("placeholder", 1.0),
            Map.entry("title", 1.0),
            Map.entry("type", 0.8),
            Map.entry("role", 0.5),
            Map.entry("class", 1.0),
            Map.entry("href", 0.8),
            Map.entry("text", 2.0),
            Map.entry("label", 1.5),
            Map.entry("ancestors", 1.5),
            Map.entry("position", 0.5));

    private static final Set<String> IDENTIFIERS = Set.of("id", "data-testid", "data-qa", "name");
    private static final Set<String> CLICKABLE = Set.of("button", "a", "input:submit", "input:button", "role:button");

    public record Scored(ElementSnapshot candidate, double score, Map<String, Double> signals) {
    }

    public List<Scored> rank(ElementSnapshot fingerprint, List<ElementSnapshot> candidates) {
        return candidates.stream()
                .map(c -> score(fingerprint, c))
                .sorted(Comparator.comparingDouble(Scored::score).reversed())
                .toList();
    }

    public Scored score(ElementSnapshot fp, ElementSnapshot c) {
        Map<String, Double> signals = new LinkedHashMap<>();
        signals.put("tag", tagSimilarity(fp, c));

        for (String attr : ElementSnapshot.TRACKED_ATTRIBUTES) {
            String expected = fp.attr(attr);
            if (expected == null || !WEIGHTS.containsKey(attr)) continue;
            String actual = c.attr(attr);
            double sim;
            if (attr.equals("class")) {
                sim = Similarity.jaccard(Set.of(expected.split("\\s+")), actual == null ? Set.of() : Set.of(actual.split("\\s+")));
            } else if (attr.equals("type") || attr.equals("role")) {
                sim = expected.equalsIgnoreCase(actual) ? 1 : 0;
            } else if (IDENTIFIERS.contains(attr)) {
                sim = Similarity.identifier(expected, actual);
            } else {
                sim = Similarity.text(expected, actual);
            }
            signals.put(attr, sim);
        }

        if (notBlank(fp.getText())) signals.put("text", Similarity.text(fp.getText(), c.getText()));
        if (notBlank(fp.getLabelText())) signals.put("label", Similarity.text(fp.getLabelText(), c.getLabelText()));
        if (!fp.getAncestors().isEmpty()) signals.put("ancestors", ancestorSimilarity(fp.getAncestors(), c.getAncestors()));
        if (fp.getWidth() > 0 && c.getWidth() > 0) {
            double dist = Math.hypot(fp.getX() - c.getX(), fp.getY() - c.getY());
            signals.put("position", Math.max(0, 1 - dist / 400));
        }

        double weighted = 0;
        double total = 0;
        for (Map.Entry<String, Double> e : signals.entrySet()) {
            double w = WEIGHTS.get(e.getKey());
            weighted += w * e.getValue();
            total += w;
        }
        double score = total == 0 ? 0 : weighted / total;
        if (indexMismatch(fp, c)) {
            // add-to-cart-8 vs add-to-cart-5: the same kind of element, but for another record in a list.
            score *= INDEX_MISMATCH_FACTOR;
            signals.put("indexMismatch", 0.0);
        }
        return new Scored(c, score, signals);
    }

    private static final double INDEX_MISMATCH_FACTOR = 0.5;
    private static final Pattern TRAILING_NUMBER = Pattern.compile("(\\d+)$");
    private static final Pattern EMBEDDED_NUMBER = Pattern.compile("[-_](\\d+)(?=$|[^\\w])");

    /**
     * True when fingerprint and candidate carry different item numbers - in an identifier
     * (id / data-testid / name ending in a number) or in their ancestors (product-card-8 vs product-card-5).
     */
    static boolean indexMismatch(ElementSnapshot fp, ElementSnapshot c) {
        for (String attr : List.of("id", "data-testid", "name")) {
            String a = trailingNumber(fp.attr(attr));
            String b = trailingNumber(c.attr(attr));
            if (a != null && b != null && !a.equals(b)) return true;
        }
        Set<String> fa = numbers(fp.getAncestors());
        Set<String> ca = numbers(c.getAncestors());
        return !fa.isEmpty() && !ca.isEmpty() && java.util.Collections.disjoint(fa, ca);
    }

    private static String trailingNumber(String value) {
        if (value == null) return null;
        Matcher m = TRAILING_NUMBER.matcher(value);
        return m.find() ? m.group(1) : null;
    }

    private static Set<String> numbers(List<String> ancestors) {
        Set<String> out = new HashSet<>();
        for (String a : ancestors) {
            Matcher m = EMBEDDED_NUMBER.matcher(a);
            while (m.find()) out.add(m.group(1));
        }
        return out;
    }

    private static double tagSimilarity(ElementSnapshot fp, ElementSnapshot c) {
        if (fp.getTag() == null || c.getTag() == null) return 0;
        if (fp.getTag().equals(c.getTag())) return 1;
        return family(fp) != null && family(fp).equals(family(c)) ? 0.7 : 0;
    }

    /** Elements that can stand in for each other, e.g. a button re-tagged as a link. */
    private static String family(ElementSnapshot e) {
        String tag = e.getTag();
        String kind = "input".equals(tag) ? "input:" + e.attr("type") : tag;
        if (CLICKABLE.contains(kind) || "button".equals(e.attr("role"))) return "clickable";
        if ("input".equals(tag) || "textarea".equals(tag) || "select".equals(tag)) return "field";
        return null;
    }

    private static double ancestorSimilarity(List<String> expected, List<String> actual) {
        Set<String> a = new HashSet<>();
        expected.forEach(s -> a.addAll(Similarity.tokens(s)));
        Set<String> b = new HashSet<>();
        actual.forEach(s -> b.addAll(Similarity.tokens(s)));
        return Similarity.jaccard(a, b);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
