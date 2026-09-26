package com.selfhealing.healer.core;

import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Finds the element a plain-language description names ("the Sign in button", "e-posta alanı") among the page's
 * candidates, locally: the description's words are compared with each element's visible text, label, aria-label,
 * placeholder and title (and, more weakly, its identifiers). Words that name a kind of control ("button", "link",
 * "field", "checkbox" ... in six languages) must fit the element's type.
 */
public final class IntentMatcher {

    /** Control-kind words -> family ("clickable" | "field" | "check" | "select"). */
    private static final Map<String, String> KINDS = new LinkedHashMap<>();
    /** Only articles and "click"-verbs: words like "in", "to", "open" can be part of a label (Sign in, Add to cart). */
    private static final Set<String> STOP = Set.of("the", "a", "an", "click", "press", "tap", "der", "die", "das", "den",
            "dem", "le", "la", "les", "el", "bir", "tikla", "tiklayin", "klicken", "нажать", "нажмите");

    static {
        for (String w : List.of("button", "btn", "buton", "butonu", "dugme", "dugmesi", "knopf", "schaltflache", "кнопка",
                "ボタン", "زر", "link", "linki", "baglanti", "baglantisi", "ссылка", "リンク", "رابط", "tab", "sekme", "sekmesi",
                "menu", "icon", "ikon", "ikonu")) KINDS.put(w, "clickable");
        for (String w : List.of("field", "input", "box", "textbox", "textarea", "alan", "alani", "kutu", "kutusu", "feld",
                "eingabefeld", "поле", "フィールド", "入力", "حقل")) KINDS.put(w, "field");
        for (String w : List.of("checkbox", "check", "toggle", "switch", "radio", "onay", "kutucugu", "kontrollkastchen",
                "флажок", "チェックボックス", "خانة")) KINDS.put(w, "check");
        for (String w : List.of("dropdown", "select", "combobox", "list", "liste", "listesi", "secim", "auswahl", "список",
                "ドロップダウン", "قائمة")) KINDS.put(w, "select");
    }

    public record Match(ElementSnapshot element, double score) {
    }

    private IntentMatcher() {
    }

    /** Candidates ranked best first. */
    public static List<Match> rank(String description, List<ElementSnapshot> candidates) {
        Set<String> words = new HashSet<>(tokens(description));
        String kind = null;
        for (String w : words) if (KINDS.containsKey(w)) kind = KINDS.get(w);
        Set<String> content = new HashSet<>(words);
        content.removeIf(w -> KINDS.containsKey(w) || STOP.contains(w));
        String phrase = String.join(" ", content);
        String expected = kind;
        return candidates.stream()
                .filter(c -> c.getSelector() != null && c.isVisible())
                .map(c -> new Match(c, score(phrase, content, expected, c)))
                .sorted(Comparator.comparingDouble(Match::score).reversed())
                .toList();
    }

    static double score(String phrase, Set<String> content, String kind, ElementSnapshot c) {
        if (content.isEmpty()) return 0;
        double best = 0;
        for (String human : new String[] {c.getText(), c.getLabelText(), c.attr("aria-label"), c.attr("placeholder"),
                c.attr("title"), c.attr("value")}) {
            if (human == null || human.isBlank() || human.length() > 80) continue;
            best = Math.max(best, Math.max(Similarity.text(phrase, human), coverage(content, tokens(human))));
        }
        // identifiers are a weaker signal: developers' names, not what the user sees
        for (String id : new String[] {c.attr("data-testid"), c.attr("id"), c.attr("name")}) {
            if (id == null) continue;
            best = Math.max(best, 0.8 * coverage(content, Similarity.tokens(id)));
        }
        if (kind != null) {
            String family = family(c);
            if (family == null || !family.equals(kind)) best *= 0.3;
        }
        return best;
    }

    /** Words in any script (Latin, Cyrillic, Japanese, Arabic ...), lower-case, accents removed. */
    static Set<String> tokens(String s) {
        if (s == null) return Set.of();
        Set<String> out = new HashSet<>();
        for (String t : Similarity.normalize(s.replaceAll("([a-z])([A-Z])", "$1 $2")).split("[^\\p{L}\\p{N}]+")) {
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    /** Share of the description's words found in the element's words. */
    private static double coverage(Set<String> want, Set<String> have) {
        if (want.isEmpty() || have.isEmpty()) return 0;
        long hit = want.stream().filter(have::contains).count();
        return (double) hit / want.size() * ((double) hit / Math.max(hit, have.size()) * 0.3 + 0.7);
    }

    private static String family(ElementSnapshot e) {
        String tag = e.getTag();
        String type = e.attr("type");
        String role = e.attr("role");
        if ("select".equals(tag) || "combobox".equals(role) || "listbox".equals(role)) return "select";
        if ("input".equals(tag) && ("checkbox".equals(type) || "radio".equals(type))) return "check";
        if ("checkbox".equals(role) || "switch".equals(role) || "radio".equals(role)) return "check";
        if ("button".equals(tag) || "a".equals(tag) || "button".equals(role) || "link".equals(role) || "tab".equals(role)
                || ("input".equals(tag) && ("submit".equals(type) || "button".equals(type)))) return "clickable";
        if ("input".equals(tag) || "textarea".equals(tag) || "textbox".equals(role)) return "field";
        return null;
    }
}
