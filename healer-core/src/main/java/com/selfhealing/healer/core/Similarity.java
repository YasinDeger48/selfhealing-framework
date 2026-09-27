package com.selfhealing.healer.core;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** String similarity helpers, all returning a value in [0, 1]. */
public final class Similarity {

    private Similarity() {
    }

    /** Lower-case, strip accents (ş→s, ı→i), collapse whitespace. */
    public static String normalize(String s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s.toLowerCase(Locale.ROOT).replace('ı', 'i'), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return n.replaceAll("\\s+", " ").trim();
    }

    /** Splits identifiers like {@code loginSubmit-button_2} into {login, submit, button, 2}. */
    public static Set<String> tokens(String s) {
        if (s == null) return Set.of();
        String spaced = s.replaceAll("([a-z])([A-Z])", "$1 $2");
        return Arrays.stream(normalize(spaced).split("[^a-z0-9]+"))
                .filter(t -> !t.isEmpty())
                .collect(Collectors.toSet());
    }

    public static double jaccard(Collection<String> a, Collection<String> b) {
        if (a.isEmpty() && b.isEmpty()) return 1;
        Set<String> inter = new HashSet<>(a);
        inter.retainAll(b);
        Set<String> union = new HashSet<>(a);
        union.addAll(b);
        return union.isEmpty() ? 0 : (double) inter.size() / union.size();
    }

    /** 1 - normalized Levenshtein distance. */
    public static double levenshteinRatio(String a, String b) {
        a = normalize(a);
        b = normalize(b);
        if (a.equals(b)) return 1;
        int max = Math.max(a.length(), b.length());
        if (max == 0) return 1;
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return 1.0 - (double) prev[b.length()] / max;
    }

    /** For identifiers (id, data-testid, name): token overlap, with edit distance as a softer signal. */
    public static double identifier(String a, String b) {
        if (a == null || b == null) return 0;
        if (a.equals(b)) return 1;
        double sim = Math.max(jaccard(tokens(a), tokens(b)), 0.9 * levenshteinRatio(a, b));
        // booking-reference-fe465c1e vs booking-reference-3fc4aa85: the same id with a generated part.
        String sa = withoutGenerated(a);
        String sb = withoutGenerated(b);
        if (!sa.isEmpty() && sa.equals(sb) && !sa.equals(normalize(a))) sim = Math.max(sim, GENERATED_MATCH);
        // street-input vs street-field, country-select vs country-dropdown: only the control-type word changed.
        String ra = withoutRoleWords(sa.isEmpty() ? normalize(a) : sa);
        String rb = withoutRoleWords(sb.isEmpty() ? normalize(b) : sb);
        if (!ra.isEmpty() && ra.equals(rb)) sim = Math.max(sim, ROLE_WORD_MATCH);
        return sim;
    }

    private static final double ROLE_WORD_MATCH = 0.9;

    /**
     * One name inside the other once separators, control-type words and generated parts are dropped:
     * login-username contains user-name and username-input ("username"). 0 when neither contains the other or the
     * shorter part is too short to mean anything. Used for cold starts, where only the old selector's name is known.
     */
    public static double contained(String a, String b) {
        String ja = joinedName(a);
        String jb = joinedName(b);
        String shorter = ja.length() <= jb.length() ? ja : jb;
        String longer = shorter == ja ? jb : ja;
        if (shorter.length() < 4 || !longer.contains(shorter)) return 0;
        return 0.5 + 0.3 * shorter.length() / longer.length();
    }

    static String joinedName(String s) {
        if (s == null) return "";
        String spaced = s.replaceAll("([a-z])([A-Z])", "$1 $2");
        return Arrays.stream(normalize(spaced).split("[^a-z0-9]+"))
                .filter(t -> !t.isEmpty() && !ROLE_WORDS.contains(t) && !GENERATED.matcher(t).matches())
                .collect(Collectors.joining());
    }
    /** Words that name the kind of control rather than its purpose; renamed freely (input -> field, select -> dropdown). */
    private static final java.util.Set<String> ROLE_WORDS = java.util.Set.of("input", "field", "fld", "box", "textbox", "txt",
            "button", "btn", "select", "dropdown", "combo", "combobox", "picker", "link", "lnk", "anchor", "checkbox", "chk",
            "check", "radio", "toggle", "switch", "textarea", "control", "ctrl", "widget");

    static String withoutRoleWords(String s) {
        return Arrays.stream(normalize(s).split("[^a-z0-9]+"))
                .filter(t -> !t.isEmpty() && !ROLE_WORDS.contains(t))
                .collect(Collectors.joining(" "));
    }

    private static final double GENERATED_MATCH = 0.95;
    // A generated token: 6+ letters/digits with at least one digit (fe465c1e, 45901727, a1b2c3).
    private static final java.util.regex.Pattern GENERATED = java.util.regex.Pattern.compile("(?=[a-z]*\\d)[a-z0-9]{6,}");

    /** True for a random-looking token such as fe465c1e or 45901727 (6+ letters/digits, at least one digit). */
    public static boolean isGenerated(String token) {
        return token != null && GENERATED.matcher(token.toLowerCase(Locale.ROOT)).matches();
    }

    /** The identifier with random-looking tokens removed, e.g. "booking-reference-fe465c1e" -> "booking reference". */
    static String withoutGenerated(String s) {
        return Arrays.stream(normalize(s).split("[^a-z0-9]+"))
                .filter(t -> !t.isEmpty() && !GENERATED.matcher(t).matches())
                .collect(Collectors.joining(" "));
    }

    /** For human text (labels, button text, placeholders). */
    public static double text(String a, String b) {
        if (a == null || b == null) return 0;
        if (normalize(a).equals(normalize(b))) return 1;
        return Math.max(jaccard(tokens(a), tokens(b)), levenshteinRatio(a, b));
    }
}
