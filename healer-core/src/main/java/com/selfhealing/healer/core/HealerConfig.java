package com.selfhealing.healer.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Healer settings, read in layers - a later layer overrides an earlier one:
 * <ol>
 *   <li>{@code healer.properties} on the classpath (the project's shared settings)</li>
 *   <li>{@code healer-<profile>.properties} when a profile is set ({@code -Dhealer.profile=ci}, {@code HEALER_PROFILE}
 *       or {@code healer.profile} in the file)</li>
 *   <li>{@code healer-local.properties} - personal settings, not committed (classpath, then the working directory)</li>
 *   <li>environment variables: {@code HEALER_REPORT_OPEN=always} -&gt; {@code healer.report.open} (also {@code BROWSER_*},
 *       {@code APP_*})</li>
 *   <li>system properties: {@code -Dhealer.mode=suggest}, {@code -Dbrowser.headless=false}</li>
 * </ol>
 * Values may reference the environment or system properties: {@code healer.llm.apiKey=${env:ANTHROPIC_API_KEY}},
 * {@code ${env:NAME:-default}}, {@code ${sys:name}}.
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

    /** Which screenshots go into the report. */
    public enum Screenshots { ALL, FAILURES, NONE }

    /** Every setting with a short description: environment variable mapping, typo warnings, documentation. */
    public static final Map<String, String> KNOWN = new LinkedHashMap<>();

    static {
        String[][] known = {
                {"healer.enabled", "false turns the healer off completely: no healing, reports or listeners"},
                {"healer.profile", "loads healer-<profile>.properties on top (e.g. ci)"},
                {"healer.language", "en | de | ru | ja | tr | ar"},
                {"healer.mode", "auto | suggest | off"},
                {"healer.probeTimeoutMs", "wait for the original selector before healing"},
                {"healer.minConfidence", "reject matches below this score"},
                {"healer.minMargin", "best match must beat the runner-up by this much"},
                {"healer.storeDir", "fingerprints and heal cache"},
                {"healer.reportDir", "report output"},
                {"healer.failOnHeal", "a healed test fails (strict CI)"},
                {"healer.verbose", "print every healing step"},
                {"healer.visual", "draw healing steps on the page"},
                {"healer.visual.pauseMs", "pause between drawn steps"},
                {"healer.screenshots", "legacy: false = healer.report.screenshots=none"},
                {"healer.runId", "same id for all JVMs of one build: one merged report"},
                {"healer.popups", "auto | off"},
                {"healer.lint", "on | off"},
                {"healer.triage", "on | off"},
                {"healer.triage.llm", "Claude explanation for failed tests"},
                {"healer.triage.model", "model for failure explanations"},
                {"healer.triage.maxCalls", "explanation budget per run"},
                {"healer.fix", "patch | apply | off"},
                {"healer.fix.sourceDirs", "where selectors are searched"},
                {"healer.fix.extensions", "files searched for selectors"},
                {"healer.report.open", "never | always | onFailure | onWarn - open the HTML report after the run (not on CI)"},
                {"healer.report.pdf", "auto (not on CI) | true | false"},
                {"healer.report.pdfBrowser", "msedge | chrome | chromium"},
                {"healer.report.screenshots", "all | failures | none"},
                {"healer.report.history", "keep every run's report in history/<timestamp>"},
                {"healer.report.historyKeep", "how many history entries to keep"},
                {"healer.llm.enabled", "use Claude when the local heuristic is not confident"},
                {"healer.llm.apiKey", "API key or a reference such as ${env:ANTHROPIC_API_KEY}"},
                {"healer.llm.model", "first model asked"},
                {"healer.llm.escalateTo", "stronger model when the first is not confident (empty = off)"},
                {"healer.llm.escalateOnNoMatch", "also escalate when the first model finds no match"},
                {"healer.llm.effort", "low | medium | high | xhigh | max"},
                {"healer.llm.language", "language of Claude's reasoning"},
                {"healer.llm.candidates", "candidates sent to Claude"},
                {"healer.llm.maxCallsPerRun", "Claude call budget per run"},
                {"healer.llm.maxCostPerRun", "USD budget per run for all Claude calls (empty = no limit)"},
                {"healer.llm.timeoutSeconds", "per-call timeout"},
                {"healer.llm.logPrompts", "save every Claude request for audit"},
                {"healer.privacy.mask", "mask personal data before it is sent to Claude"},
                {"healer.privacy.patterns", "extra masking rules NAME=regex;NAME2=regex"},
                {"browser.name", "chromium | msedge | chrome | firefox | webkit"},
                {"browser.headless", "true | false"},
                {"browser.slowmo", "ms between browser actions"},
                {"browser.viewport", "window size, e.g. 1280x900"},
                {"browser.timeoutMs", "default timeout of browser actions"},
                {"browser.video", "off | failures | all (Playwright)"},
                {"browser.trace", "off | failures | all (Playwright)"},
                {"app.baseUrl", "base URL of the application under test"},
        };
        for (String[] k : known) KNOWN.put(k[0], k[1]);
    }

    private static final List<String> ENV_PREFIXES = List.of("HEALER_", "BROWSER_", "APP_");
    private static final List<String> CI_VARIABLES = List.of("CI", "GITHUB_ACTIONS", "GITLAB_CI", "JENKINS_URL", "TF_BUILD",
            "BUILDKITE", "TEAMCITY_VERSION", "BITBUCKET_BUILD_NUMBER", "CIRCLECI", "TRAVIS", "CODEBUILD_BUILD_ID");
    private static final Pattern INLINE_COMMENT = Pattern.compile("\\s+#.*$");
    private static final Pattern REFERENCE = Pattern.compile("\\$\\{(env|sys):([^}:]+)(?::-([^}]*))?}");

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
    private final boolean enabled;
    private final boolean ci;
    private final Properties raw;
    /** Where each setting came from, for diagnostics: key -> file / "environment" / "system property". */
    private final Map<String, String> sources;

    private HealerConfig(Properties p, Map<String, String> env, Map<String, String> sources) {
        this.raw = p;
        this.sources = sources;
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
        this.enabled = !"false".equalsIgnoreCase(p.getProperty("healer.enabled", "true").trim());
        this.ci = CI_VARIABLES.stream().anyMatch(v -> {
            String value = env.get(v);
            return value != null && !value.isBlank() && !"false".equalsIgnoreCase(value);
        });
    }

    /** Any setting by name (after all layers and references), for extension modules and helpers. */
    public String get(String name, String defaultValue) {
        String v = raw.getProperty(name);
        // Not set -> derived default; set to an empty value -> empty (e.g. healer.llm.escalateTo= turns escalation off).
        if (v == null && DERIVED_DEFAULTS.containsKey(name)) return DERIVED_DEFAULTS.get(name).get();
        if (v != null && v.isBlank() && DERIVED_DEFAULTS.containsKey(name)) return "";
        return v == null || v.isBlank() ? defaultValue : v.trim();
    }

    public boolean getBoolean(String name, boolean defaultValue) {
        return Boolean.parseBoolean(get(name, String.valueOf(defaultValue)));
    }

    public long getLong(String name, long defaultValue) {
        try {
            return Long.parseLong(get(name, String.valueOf(defaultValue)));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /** All setting names with this prefix, e.g. healer.llm.price. */
    public List<String> keysWithPrefix(String prefix) {
        return raw.stringPropertyNames().stream().filter(k -> k.startsWith(prefix)).sorted().toList();
    }

    /** Where a setting came from (file name, "environment", "system property"), or null for the default. */
    public String source(String name) {
        return sources.get(name);
    }

    /** Defaults that depend on other settings. */
    private static final Map<String, Supplier<String>> DERIVED_DEFAULTS = Map.of(
            "healer.llm.language", () -> Messages.englishName(Messages.language()),
            "healer.llm.escalateTo", () -> "claude-opus-5");

    public static HealerConfig load() {
        return load(System.getenv(), System.getProperties(), Path.of("").toAbsolutePath());
    }

    /** All layers, see the class comment. Visible for tests. */
    static HealerConfig load(Map<String, String> env, Properties system, Path workDir) {
        Properties p = new Properties();
        Map<String, String> sources = new LinkedHashMap<>();
        readClasspath(p, sources, "healer.properties");
        String profile = firstNonBlank(system.getProperty("healer.profile"), env.get("HEALER_PROFILE"), p.getProperty("healer.profile"));
        if (profile != null) {
            if (!readClasspath(p, sources, "healer-" + profile + ".properties")) {
                System.out.println("[healer] profile '" + profile + "': healer-" + profile + ".properties not found on the classpath");
            }
        }
        readClasspath(p, sources, "healer-local.properties");
        readFile(p, sources, workDir.resolve("healer-local.properties"));
        env.forEach((name, value) -> {
            if (ENV_PREFIXES.stream().anyMatch(name::startsWith)) {
                String key = keyForEnvironment(name, p);
                p.setProperty(key, value);
                sources.put(key, "environment " + name);
            }
        });
        system.forEach((k, v) -> {
            String key = k.toString();
            if (key.startsWith("healer.") || key.startsWith("browser.") || key.startsWith("app.")) {
                p.setProperty(key, v.toString());
                sources.put(key, "system property");
            }
        });
        for (String key : p.stringPropertyNames()) {
            // "healer.report.open=onWarn   # never | always ..." - a trailing comment is not part of the value
            String value = INLINE_COMMENT.matcher(p.getProperty(key)).replaceFirst("");
            p.setProperty(key, resolve(value, env, system));
        }
        warnUnknown(p);
        return new HealerConfig(p, env, sources);
    }

    public static HealerConfig from(Properties p) {
        return new HealerConfig(p, Map.of(), Map.of());
    }

    /** HEALER_REPORT_OPEN -> healer.report.open; HEALER_LLM_MAXCOSTPERRUN -> healer.llm.maxCostPerRun. */
    static String keyForEnvironment(String name, Properties loaded) {
        String flat = name.toLowerCase(Locale.ROOT).replace("_", "");
        for (String known : KNOWN.keySet()) {
            if (known.toLowerCase(Locale.ROOT).replace(".", "").equals(flat)) return known;
        }
        for (String existing : loaded.stringPropertyNames()) {
            if (existing.toLowerCase(Locale.ROOT).replace(".", "").equals(flat)) return existing;
        }
        return name.toLowerCase(Locale.ROOT).replace('_', '.');
    }

    /** ${env:NAME}, ${env:NAME:-default}, ${sys:name}; an unset reference without default becomes empty. */
    static String resolve(String value, Map<String, String> env, Properties system) {
        if (value == null || !value.contains("${")) return value;
        Matcher m = REFERENCE.matcher(value);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String found = "env".equals(m.group(1)) ? env.get(m.group(2)) : system.getProperty(m.group(2));
            String replacement = found != null && !found.isEmpty() ? found : (m.group(3) != null ? m.group(3) : "");
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static boolean readClasspath(Properties p, Map<String, String> sources, String name) {
        try (InputStream in = HealerConfig.class.getClassLoader().getResourceAsStream(name)) {
            if (in == null) return false;
            Properties layer = new Properties();
            try (Reader r = new java.io.InputStreamReader(in, StandardCharsets.UTF_8)) {
                layer.load(r);
            }
            layer.stringPropertyNames().forEach(k -> sources.put(k, name));
            p.putAll(layer);
            return true;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + name, e);
        }
    }

    private static void readFile(Properties p, Map<String, String> sources, Path file) {
        if (!Files.isRegularFile(file)) return;
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Properties layer = new Properties();
            layer.load(r);
            layer.stringPropertyNames().forEach(k -> sources.put(k, file.getFileName().toString()));
            p.putAll(layer);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + file, e);
        }
    }

    /** A misspelled setting silently does nothing - say so, with the closest known name. */
    private static void warnUnknown(Properties p) {
        List<String> unknown = new ArrayList<>();
        for (String key : p.stringPropertyNames()) {
            if (!key.startsWith("healer.") && !key.startsWith("browser.") && !key.startsWith("app.")) continue;
            if (KNOWN.containsKey(key) || key.startsWith("healer.llm.price.")) continue;
            unknown.add(key);
        }
        for (String key : unknown) {
            String best = null;
            double score = 0;
            for (String known : KNOWN.keySet()) {
                double s = Similarity.levenshteinRatio(key.toLowerCase(Locale.ROOT), known.toLowerCase(Locale.ROOT));
                if (s > score) {
                    score = s;
                    best = known;
                }
            }
            System.out.println("[healer] unknown setting '" + key + "'" + (score > 0.75 ? " - did you mean '" + best + "'?" : ""));
        }
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) if (v != null && !v.isBlank()) return v.trim();
        return null;
    }

    public Mode mode() { return mode; }
    public long probeTimeoutMs() { return probeTimeoutMs; }
    public double minConfidence() { return minConfidence; }
    public double minMargin() { return minMargin; }
    public Path storeDir() { return storeDir; }
    public Path reportDir() { return reportDir; }
    public boolean failOnHeal() { return failOnHeal; }
    public boolean llmEnabled() { return llmEnabled && enabled; }
    public String llmModel() { return llmModel; }
    public int llmCandidates() { return llmCandidates; }

    /** healer.enabled=false: the healer does nothing - no healing, no reports, no listeners. */
    public boolean enabled() { return enabled; }

    /** Running on a CI server (CI, GITHUB_ACTIONS, JENKINS_URL, TF_BUILD ... set). */
    public boolean ci() { return ci; }

    /** healer.report.pdf: auto (default) = everywhere except on CI. */
    public boolean pdfReport() {
        String v = get("healer.report.pdf", "auto");
        return "auto".equalsIgnoreCase(v) ? !ci : Boolean.parseBoolean(v);
    }

    /** healer.report.screenshots (all | failures | none); the legacy healer.screenshots=false means none. */
    public Screenshots screenshots() {
        if ("false".equalsIgnoreCase(get("healer.screenshots", "true"))) return Screenshots.NONE;
        return switch (get("healer.report.screenshots", "all").toLowerCase(Locale.ROOT)) {
            case "none", "off", "false" -> Screenshots.NONE;
            case "failures", "failuresonly", "onfailure" -> Screenshots.FAILURES;
            default -> Screenshots.ALL;
        };
    }
}
