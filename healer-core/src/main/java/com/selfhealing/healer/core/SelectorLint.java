package com.selfhealing.healer.core;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rates how likely a selector is to break, from the selector text alone. Reasons (codes, translated in the report):
 * <ul>
 *   <li>HIGH - {@code absoluteXpath} (/html/body/...), {@code position} (nth-child, [3], nth=), {@code generated}
 *       (random-looking id or class part), {@code noIdentifier} (only tags and structure)</li>
 *   <li>MEDIUM - {@code styleClass} (only CSS classes, which change with styling), {@code deepChain} (3+ levels)</li>
 *   <li>LOW - {@code text} (visible text: changes with copy edits and translations)</li>
 * </ul>
 * Stable attributes (data-testid, id, name, aria-label ...) and prefix matches of generated values are OK.
 */
public final class SelectorLint {

    public enum Risk { OK, LOW, MEDIUM, HIGH }

    public record Assessment(Risk risk, List<String> reasons) {
    }

    private static final Pattern ABSOLUTE_XPATH = Pattern.compile("^(xpath=)?\\(?/html", Pattern.CASE_INSENSITIVE);
    private static final Pattern XPATH = Pattern.compile("^(xpath=|/|\\(/)");
    private static final Pattern POSITION = Pattern.compile(
            ":nth-(child|of-type|last-child|last-of-type|match)\\(|>>\\s*nth=|:nth\\(|:(first|last)-(child|of-type)|:only-child"
            + "|\\[\\s*\\d+\\s*]|\\[\\s*(position|last)\\(\\)");
    private static final Pattern TEXT = Pattern.compile("^text=|:has-text\\(|:text(-is|-matches)?\\(|text\\(\\)|contains\\(\\s*\\.");
    private static final Pattern ID = Pattern.compile("#((?:\\\\.|[\\w-])+)");
    private static final Pattern ATTR = Pattern.compile("\\[\\s*@?([\\w-]+)\\s*([\\^$*~|]?)=\\s*['\"]?([^'\"\\]]*)['\"]?\\s*]");
    private static final Pattern CLASS = Pattern.compile("(?<![\\w\\\\])\\.((?:\\\\.|[\\w-])+)");
    private static final Pattern PLAYWRIGHT_ATTR = Pattern.compile("^(data-testid|role|id|alt|title|placeholder|label)=", Pattern.CASE_INSENSITIVE);

    private SelectorLint() {
    }

    public static Assessment assess(String selector) {
        List<String> reasons = new ArrayList<>();
        if (selector == null || selector.isBlank()) return new Assessment(Risk.HIGH, List.of("noIdentifier"));
        String s = selector.trim();
        String bare = s.replaceAll("'[^']*'|\"[^\"]*\"", "''");   // quoted values cannot contain structure

        if (ABSOLUTE_XPATH.matcher(s).find()) reasons.add("absoluteXpath");
        if (POSITION.matcher(bare).find()) reasons.add("position");

        boolean identifier = PLAYWRIGHT_ATTR.matcher(s).find();
        boolean generated = false;
        Matcher m = ID.matcher(XPATH.matcher(s).find() ? "" : bare.replaceAll("\\[[^\\]]*]", ""));
        while (m.find()) {
            identifier = true;
            generated |= hasGeneratedPart(m.group(1).replace("\\", ""));
        }
        m = ATTR.matcher(s);
        while (m.find()) {
            identifier = true;
            boolean prefixMatch = "^".equals(m.group(2)) || "*".equals(m.group(2));
            if (!prefixMatch) generated |= hasGeneratedPart(m.group(3));
        }
        boolean classes = false;
        m = CLASS.matcher(bare.replaceAll("\\[[^\\]]*]", ""));
        while (m.find()) {
            classes = true;
            generated |= hasGeneratedPart(m.group(1).replace("\\", ""));
        }
        boolean text = TEXT.matcher(s).find();

        if (generated) reasons.add("generated");
        if (!identifier && !classes && !text && !reasons.contains("absoluteXpath")) reasons.add("noIdentifier");
        if (!identifier && classes && !text) reasons.add("styleClass");
        if (combinators(bare) >= 3) reasons.add("deepChain");
        if (text && !identifier) reasons.add("text");

        Risk risk = Risk.OK;
        for (String r : reasons) risk = max(risk, severity(r));
        return new Assessment(risk, List.copyOf(reasons));
    }

    public static Risk severity(String reason) {
        return switch (reason) {
            case "absoluteXpath", "position", "generated", "noIdentifier" -> Risk.HIGH;
            case "styleClass", "deepChain" -> Risk.MEDIUM;
            case "text" -> Risk.LOW;
            default -> Risk.OK;
        };
    }

    private static boolean hasGeneratedPart(String value) {
        for (String token : value.split("[^A-Za-z0-9]+")) {
            if (Similarity.isGenerated(token)) return true;
        }
        return false;
    }

    /** Descendant / child / sibling steps outside brackets and parentheses; for XPath the number of steps. */
    private static int combinators(String s) {
        if (XPATH.matcher(s).find()) return Math.max(0, s.replaceAll("^xpath=", "").split("/+").length - 2);
        int depth = 0, count = 0;
        boolean inStep = false;
        String flat = s.replace(">>", " ");
        for (int i = 0; i < flat.length(); i++) {
            char c = flat.charAt(i);
            if (c == '[' || c == '(') depth++;
            else if (c == ']' || c == ')') depth--;
            else if (depth == 0 && (c == ' ' || c == '>' || c == '+' || c == '~')) {
                if (inStep) count++;
                inStep = false;
                continue;
            }
            inStep = true;
        }
        return count;
    }

    private static Risk max(Risk a, Risk b) {
        return a.ordinal() >= b.ordinal() ? a : b;
    }
}
