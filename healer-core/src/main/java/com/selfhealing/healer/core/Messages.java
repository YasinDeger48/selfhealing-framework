package com.selfhealing.healer.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

/**
 * User-facing texts of the healer (console trace, demo overlay, HTML report) in the language set by
 * {@code healer.language}. Missing keys fall back to English.
 */
public final class Messages {

    /** Supported languages, in the order the report's language menu shows them. */
    public static final List<String> LANGUAGES = List.of("en", "de", "ru", "ja", "tr", "ar");

    /** A message reference used as an argument of another message, resolved in the same language. */
    public record Msg(String key, List<Object> args) {
    }

    private static final Map<String, Properties> BUNDLES = new LinkedHashMap<>();
    private static final Properties ENGLISH;
    private static volatile Properties current;
    private static volatile String language = "en";

    static {
        for (String lang : LANGUAGES) {
            Properties p = load(lang);
            if (p != null) BUNDLES.put(lang, p);
        }
        ENGLISH = BUNDLES.get("en");
        current = ENGLISH;
    }

    private Messages() {
    }

    private static Properties load(String lang) {
        try (InputStream in = Messages.class.getResourceAsStream("messages_" + lang + ".properties")) {
            if (in == null) return null;
            Properties p = new Properties();
            p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            return p;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Selects the language; unknown languages fall back to English. */
    public static void use(String lang) {
        String code = lang == null ? "en" : lang.trim().toLowerCase(Locale.ROOT);
        Properties p = BUNDLES.get(code);
        current = p == null ? ENGLISH : p;
        language = p == null ? "en" : code;
    }

    public static String language() {
        return language;
    }

    /** English name of a language, e.g. for asking Claude to answer in it. */
    public static String englishName(String code) {
        return switch (code) {
            case "de" -> "German";
            case "ru" -> "Russian";
            case "ja" -> "Japanese";
            case "tr" -> "Turkish";
            case "ar" -> "Arabic";
            default -> "English";
        };
    }

    /**
     * The message for {@code key} in the current language, formatted with {@link String#format}
     * (root locale: 0.75, not 0,75). {@link Msg} arguments are resolved first.
     */
    public static String get(String key, Object... args) {
        String pattern = current.getProperty(key, ENGLISH.getProperty(key, key));
        if (args.length == 0) return pattern;
        Object[] resolved = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            resolved[i] = args[i] instanceof Msg m ? get(m.key(), m.args().toArray()) : args[i];
        }
        return String.format(Locale.ROOT, pattern, resolved);
    }

    /**
     * Texts the HTML report needs in every language, so it can switch language in the browser:
     * {@code report.*} keys (prefix stripped) plus the {@code trace.*} and {@code source.*} keys.
     */
    public static Map<String, Map<String, String>> reportLabels() {
        Map<String, Map<String, String>> all = new LinkedHashMap<>();
        for (Map.Entry<String, Properties> b : BUNDLES.entrySet()) {
            Map<String, String> labels = new TreeMap<>();
            for (String k : ENGLISH.stringPropertyNames()) {
                String v = b.getValue().getProperty(k, ENGLISH.getProperty(k));
                if (k.startsWith("report.")) labels.put(k.substring("report.".length()), v);
                else if (k.startsWith("trace.") || k.startsWith("source.")) labels.put(k, v);
            }
            all.put(b.getKey(), labels);
        }
        return all;
    }

    public static List<String> languages() {
        return new ArrayList<>(BUNDLES.keySet());
    }
}
