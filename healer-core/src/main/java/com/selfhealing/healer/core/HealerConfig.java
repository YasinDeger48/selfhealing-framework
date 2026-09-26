package com.selfhealing.healer.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;

/**
 * Healer settings. Read from {@code healer.properties} on the classpath, then overridden by
 * system properties with the same name (e.g. {@code -Dhealer.mode=suggest}).
 */
public final class HealerConfig {

    public enum Mode {
        /** No healing: original selectors only. */
        OFF,
        /** Find a replacement and report it, but let the step fail. */
        SUGGEST,
        /** Use the replacement, continue the test and report a WARN. */
        AUTO
    }

    private final Mode mode;
    private final long probeTimeoutMs;
    private final double minConfidence;
    private final double minMargin;
    private final Path storeDir;
    private final Path reportDir;
    private final boolean failOnHeal;
    private final boolean llmEnabled;
    private final String llmModel;
    private final int llmCandidates;
    private final Properties raw;

    private HealerConfig(Properties p) {
        this.raw = p;
        Messages.use(p.getProperty("healer.language", "en"));
        this.mode = Mode.valueOf(p.getProperty("healer.mode", "auto").trim().toUpperCase(Locale.ROOT));
        this.probeTimeoutMs = Long.parseLong(p.getProperty("healer.probeTimeoutMs", "3000").trim());
        this.minConfidence = Double.parseDouble(p.getProperty("healer.minConfidence", "0.60").trim());
        this.minMargin = Double.parseDouble(p.getProperty("healer.minMargin", "0.08").trim());
        this.storeDir = Path.of(p.getProperty("healer.storeDir", ".healer").trim());
        this.reportDir = Path.of(p.getProperty("healer.reportDir", "target/healer-report").trim());
        this.failOnHeal = Boolean.parseBoolean(p.getProperty("healer.failOnHeal", "false").trim());
        this.llmEnabled = Boolean.parseBoolean(p.getProperty("healer.llm.enabled", "false").trim());
        this.llmModel = p.getProperty("healer.llm.model", "claude-haiku-4-5").trim();
        this.llmCandidates = Integer.parseInt(p.getProperty("healer.llm.candidates", "10").trim());
    }

    /** Any setting by name, for extension modules (e.g. {@code healer.llm.effort}). */
    public String get(String name, String defaultValue) {
        String v = raw.getProperty(name);
        // Not set -> derived default; set to an empty value -> empty (e.g. healer.llm.escalateTo= turns escalation off).
        if (v == null && DERIVED_DEFAULTS.containsKey(name)) return DERIVED_DEFAULTS.get(name).get();
        if (v != null && v.isBlank() && DERIVED_DEFAULTS.containsKey(name)) return "";
        return v == null || v.isBlank() ? defaultValue : v.trim();
    }

    /** Defaults that depend on other settings. */
    private static final java.util.Map<String, java.util.function.Supplier<String>> DERIVED_DEFAULTS = java.util.Map.of(
            "healer.llm.language", () -> Messages.englishName(Messages.language()),
            "healer.llm.escalateTo", () -> "claude-opus-5");

    public static HealerConfig load() {
        Properties p = new Properties();
        try (InputStream in = HealerConfig.class.getClassLoader().getResourceAsStream("healer.properties")) {
            if (in != null) p.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read healer.properties", e);
        }
        System.getProperties().forEach((k, v) -> {
            if (k.toString().startsWith("healer.")) p.setProperty(k.toString(), v.toString());
        });
        return new HealerConfig(p);
    }

    public static HealerConfig from(Properties p) {
        return new HealerConfig(p);
    }

    public Mode mode() { return mode; }
    public long probeTimeoutMs() { return probeTimeoutMs; }
    public double minConfidence() { return minConfidence; }
    public double minMargin() { return minMargin; }
    public Path storeDir() { return storeDir; }
    public Path reportDir() { return reportDir; }
    public boolean failOnHeal() { return failOnHeal; }
    public boolean llmEnabled() { return llmEnabled; }
    public String llmModel() { return llmModel; }
    public int llmCandidates() { return llmCandidates; }
}
