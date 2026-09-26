package com.selfhealing.healer.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cold start: when an element was never seen working (no recorded fingerprint), derive what we can from
 * the selector itself - {@code #booking-reference-fe465c1e} still says "id ~ booking-reference".
 * Enough for the heuristic to rank candidates; the real fingerprint is learned after the first heal.
 */
public final class SelectorFingerprint {

    private static final Pattern ID = Pattern.compile("#((?:\\\\.|[\\w-])+)");
    private static final Pattern ATTR = Pattern.compile("\\[\\s*([\\w-]+)\\s*[*^$|~]?=\\s*['\"]?([^'\"\\]]+)['\"]?\\s*]");
    private static final Pattern CLASS = Pattern.compile("(?<!\\\\)\\.((?:\\\\.|[\\w-])+)");
    private static final Pattern TAG = Pattern.compile("^\\s*([a-zA-Z][a-zA-Z0-9]*)");
    private static final Pattern TEXT = Pattern.compile(":has-text\\(\\s*['\"](.+?)['\"]\\s*\\)|^text=['\"]?(.+?)['\"]?$");

    private static final Pattern PSEUDO = Pattern.compile(":[\\w-]+\\((?:'[^']*'|\"[^\"]*\"|[^)])*\\)");

    private SelectorFingerprint() {
    }

    /** A minimal snapshot built from the selector's id, attributes, classes, tag and text; null if nothing usable. */
    public static ElementSnapshot derive(String selector) {
        if (selector == null || selector.isBlank()) return null;
        // For "a >> b" chains the last part names the element itself.
        String last = selector.contains(">>") ? selector.substring(selector.lastIndexOf(">>") + 2).trim() : selector.trim();
        ElementSnapshot e = new ElementSnapshot();
        Matcher m = TEXT.matcher(last);
        if (m.find()) e.setText(m.group(1) != null ? m.group(1) : m.group(2));
        if (last.startsWith("text=")) last = "";
        // Pseudo-classes (:has-text(...), :nth-child(...)) may contain spaces - drop them before splitting.
        last = PSEUDO.matcher(last).replaceAll("");
        // Only the final compound selector (after the last combinator) describes the element.
        String[] parts = last.split("\\s*[ >+~]\\s*(?![^\\[]*])");
        String target = parts[parts.length - 1];

        m = TAG.matcher(target);
        if (m.find()) e.setTag(m.group(1).toLowerCase(Locale.ROOT));
        m = ID.matcher(target);
        if (m.find()) e.getAttributes().put("id", unescape(m.group(1)));
        m = ATTR.matcher(target);
        while (m.find()) {
            String name = m.group(1);
            if (ElementSnapshot.TRACKED_ATTRIBUTES.contains(name)) e.getAttributes().put(name, m.group(2).trim());
        }
        List<String> classes = new ArrayList<>();
        m = CLASS.matcher(target.replaceAll("\\[[^\\]]*]", ""));
        while (m.find()) classes.add(unescape(m.group(1)));
        if (!classes.isEmpty()) e.getAttributes().merge("class", String.join(" ", classes), (a, b) -> a + " " + b);

        boolean usable = !e.getAttributes().isEmpty() || (e.getText() != null && !e.getText().isBlank());
        return usable ? e : null;
    }

    private static String unescape(String s) {
        return s.replace("\\", "");
    }
}
