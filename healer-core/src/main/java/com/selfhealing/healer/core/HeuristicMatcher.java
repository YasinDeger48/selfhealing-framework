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
        if (fp.getTag() != null) signals.put("tag", tagSimilarity(fp, c)); // a selector-derived fingerprint may lack it

        Set<String> absent = new HashSet<>();
        for (String attr : ElementSnapshot.TRACKED_ATTRIBUTES) {
            String expected = fp.attr(attr);
            if (expected == null || !WEIGHTS.containsKey(attr)) continue;
            String actual = c.attr(attr);
            if (actual == null) absent.add(attr);
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
            // An attribute the element no longer has at all is weaker evidence than one with a different value.
            double w = WEIGHTS.get(e.getKey()) * (absent.contains(e.getKey()) ? ABSENT_FACTOR : 1);
            weighted += w * e.getValue();
            total += w;
        }
        double score = total == 0 ? 0 : weighted / total;
        if (indexMismatch(fp, c)) {
            // add-to-cart-8 vs add-to-cart-5: the same kind of element, but for another record in a list.
            score *= INDEX_MISMATCH_FACTOR;
            signals.put("indexMismatch", 0.0);
        }
        if (oppositeMeaning(fp, c)) {
            // quantity-increase vs quantity-decrease: nearly the same name, the opposite action.
            score *= INDEX_MISMATCH_FACTOR;
            signals.put("opposite", 0.0);
        }
        return new Scored(c, score, signals);
    }

    private static final double INDEX_MISMATCH_FACTOR = 0.5;
    private static final double ABSENT_FACTOR = 0.5;

    /** Word pairs with opposite meaning; a candidate named with the other word of a pair is a different control. */
    private static final String[][] OPPOSITES = {
            {"increase", "decrease"}, {"inc", "dec"}, {"increment", "decrement"}, {"plus", "minus"}, {"add", "remove"},
            {"next", "prev"}, {"next", "previous"}, {"forward", "back"}, {"up", "down"}, {"open", "close"},
            {"show", "hide"}, {"expand", "collapse"}, {"enable", "disable"}, {"on", "off"}, {"yes", "no"},
            {"accept", "reject"}, {"accept", "decline"}, {"approve", "deny"}, {"login", "logout"}, {"signin", "signout"},
            {"first", "last"}, {"min", "max"}, {"left", "right"}, {"start", "stop"}, {"play", "pause"},
            {"subscribe", "unsubscribe"}, {"follow", "unfollow"}, {"lock", "unlock"}, {"import", "export"},
            {"upload", "download"}, {"zoomin", "zoomout"}, {"undo", "redo"}, {"from", "to"}, {"in", "out"},
            {"artir", "azalt"}, {"ileri", "geri"}, {"ac", "kapat"}, {"ekle", "cikar"}, {"goster", "gizle"}};

    /**
     * False when the candidate cannot be the element whatever its score: another list item (different item number),
     * the opposite control, or a non-interactive element standing in for a button, link or field.
     */
    public static boolean compatible(ElementSnapshot fp, ElementSnapshot c) {
        if (indexMismatch(fp, c) || oppositeMeaning(fp, c)) return false;
        String expected = family(fp);
        return expected == null || expected.equals(family(c));
    }

    /** True when one element is named with a word and the other with its opposite (in identifiers, labels or text). */
    static boolean oppositeMeaning(ElementSnapshot fp, ElementSnapshot c) {
        Set<String> a = words(fp);
        Set<String> b = words(c);
        for (String[] pair : OPPOSITES) {
            if ((a.contains(pair[0]) && !a.contains(pair[1]) && b.contains(pair[1]) && !b.contains(pair[0]))
                    || (a.contains(pair[1]) && !a.contains(pair[0]) && b.contains(pair[0]) && !b.contains(pair[1]))) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> words(ElementSnapshot e) {
        Set<String> out = new HashSet<>();
        for (String attr : List.of("id", "data-testid", "data-qa", "name", "aria-label", "title")) out.addAll(Similarity.tokens(e.attr(attr)));
        out.addAll(Similarity.tokens(e.getText()));
        out.addAll(Similarity.tokens(e.getLabelText()));
        return out;
    }
    // An item number is a short, all-digit token after - or _ (add-to-cart-8, row_12). Random ids such as
    // "-fe465c10" or long generated numbers are not item numbers and must not trigger the penalty.
    private static final Pattern TRAILING_NUMBER = Pattern.compile("[-_](\\d{1,4})$");
    private static final Pattern EMBEDDED_NUMBER = Pattern.compile("[-_](\\d{1,4})(?=$|[^\\w])");

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
        if (tag == null) return null;   // a fingerprint derived from a selector may not know the tag
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
