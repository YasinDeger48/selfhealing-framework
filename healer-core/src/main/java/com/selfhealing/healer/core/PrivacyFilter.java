package com.selfhealing.healer.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Masks personal and secret data in page content before it leaves the machine (e.g. in a Claude request).
 * Built-in rules: e-mail, JWT, IBAN, payment card (Luhn-checked), phone, national id, API tokens,
 * long numbers. Extra rules: {@code healer.privacy.patterns=NAME=regex;NAME2=regex2}.
 * Disable with {@code healer.privacy.mask=false}.
 */
public final class PrivacyFilter {

    private record Rule(String name, Pattern pattern, Predicate<String> accept) {
    }

    private final boolean enabled;
    private final List<Rule> rules = new ArrayList<>();

    public PrivacyFilter(HealerConfig config) {
        this(Boolean.parseBoolean(config.get("healer.privacy.mask", "true")), config.get("healer.privacy.patterns", ""));
    }

    PrivacyFilter(boolean enabled, String customPatterns) {
        this.enabled = enabled;
        // Custom rules first: they are the most specific to the application.
        for (String entry : customPatterns.split(";")) {
            int eq = entry.indexOf('=');
            if (eq > 0) rules.add(new Rule(entry.substring(0, eq).trim(), Pattern.compile(entry.substring(eq + 1).trim()), s -> true));
        }
        rules.add(new Rule("EMAIL", Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"), s -> true));
        rules.add(new Rule("TOKEN", Pattern.compile("\\beyJ[\\w-]{5,}\\.[\\w-]{5,}\\.[\\w-]{5,}\\b"), s -> true));
        rules.add(new Rule("IBAN", Pattern.compile("\\b[A-Z]{2}\\d{2}(?: ?[A-Z0-9]{4}){3,7}(?: ?[A-Z0-9]{1,3})?\\b"), s -> true));
        rules.add(new Rule("CARD", Pattern.compile("(?<![\\w-])(?:\\d[ -]?){12,18}\\d(?![\\w-])"), PrivacyFilter::luhn));
        rules.add(new Rule("NATIONAL_ID", Pattern.compile("(?<!\\d)[1-9]\\d{10}(?!\\d)"), PrivacyFilter::turkishId));
        // E.164: at most 15 digits; at least 10, so dates, prices and postal codes stay readable
        rules.add(new Rule("PHONE", Pattern.compile("(?<![\\w-])\\+?\\d[\\d ()./-]{7,}\\d(?![\\w-])"),
                s -> digits(s) >= 10 && digits(s) <= 15));
        rules.add(new Rule("TOKEN", Pattern.compile("(?<![\\w-])(?=[\\w-]*\\d)(?=[\\w-]*[A-Za-z])[\\w-]{32,}(?![\\w-])"), s -> true));
        rules.add(new Rule("NUMBER", Pattern.compile("(?<!\\d)\\d{6,}(?!\\d)"), s -> true));
    }

    public boolean enabled() {
        return enabled;
    }

    /** One masking pass (e.g. one LLM request): masks values and counts what was hidden, by type. */
    public final class Session {
        private final Map<String, Integer> counts = new LinkedHashMap<>();

        public String mask(String value) {
            if (!enabled || value == null || value.isEmpty()) return value;
            String out = value;
            for (Rule rule : rules) {
                Matcher m = rule.pattern().matcher(out);
                StringBuilder sb = new StringBuilder();
                boolean changed = false;
                while (m.find()) {
                    if (!rule.accept().test(m.group())) continue;
                    m.appendReplacement(sb, Matcher.quoteReplacement("[" + rule.name() + "]"));
                    counts.merge(rule.name(), 1, Integer::sum);
                    changed = true;
                }
                if (changed) {
                    m.appendTail(sb);
                    out = sb.toString();
                }
            }
            return out;
        }

        public Map<String, Integer> counts() {
            return Map.copyOf(counts);
        }
    }

    public Session session() {
        return new Session();
    }

    private static int digits(String s) {
        int n = 0;
        for (char ch : s.toCharArray()) if (Character.isDigit(ch)) n++;
        return n;
    }

    /** Turkish national id (TC Kimlik No) checksum. */
    static boolean turkishId(String s) {
        if (s.length() != 11 || s.charAt(0) == '0') return false;
        int[] d = s.chars().map(c -> c - '0').toArray();
        int odd = d[0] + d[2] + d[4] + d[6] + d[8];
        int even = d[1] + d[3] + d[5] + d[7];
        int d10 = Math.floorMod(odd * 7 - even, 10);
        int d11 = (odd + even + d[9]) % 10;
        return d[9] == d10 && d[10] == d11;
    }

    /** Luhn checksum, so ordinary long numbers are not reported as payment cards. */
    static boolean luhn(String s) {
        String d = s.replaceAll("\\D", "");
        if (d.length() < 13 || d.length() > 19) return false;
        int sum = 0;
        boolean dbl = false;
        for (int i = d.length() - 1; i >= 0; i--) {
            int v = d.charAt(i) - '0';
            if (dbl) {
                v *= 2;
                if (v > 9) v -= 9;
            }
            sum += v;
            dbl = !dbl;
        }
        return sum % 10 == 0;
    }
}
