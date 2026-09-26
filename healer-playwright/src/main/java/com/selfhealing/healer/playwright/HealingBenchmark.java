package com.selfhealing.healer.playwright;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.options.LoadState;
import com.selfhealing.healer.core.HealerConfig;
import com.selfhealing.healer.core.HealingEngine;
import com.selfhealing.healer.core.Json;
import com.selfhealing.healer.core.LocatorHealer;
import com.selfhealing.healer.core.LocatorHealerProvider;

import java.io.IOException;
import java.io.InputStream;
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

/**
 * Measures healing accuracy on any page: the interactive elements are fingerprinted, a simulated release changes them
 * (see bench.js - levels low, medium, high, extreme, removed), and every broken selector is healed. Each element carries
 * a hidden marker the matcher never sees, so every heal is checked: <b>correct</b> (same element), <b>wrong</b> (another
 * element - a false positive), or <b>not healed</b>. For removed elements, not healing is the correct answer.
 *
 * <pre>
 * java ... HealingBenchmark --url https://app/login --url https://app/cart [--storage-state state.json]
 *      [--levels low,medium,high,extreme,removed] [--llm true] [--seed 42] [--max 30] [--channel msedge|chromium]
 *      [--out target/healer-benchmark] [--set healer.llm.escalateTo=]
 * </pre>
 */
public final class HealingBenchmark {

    private static final String LIB = load();
    public static final List<String> LEVELS = List.of("low", "medium", "high", "extreme", "removed");

    /** Result of one page at one level. accuracy = correct / affected; for "removed", correct = rightly not healed. */
    public record Result(String page, String level, int elements, int unaffected, int correct, int wrong, int notHealed,
                         long healMs, double costUsd, List<Detail> details) {
        public int affected() { return correct + wrong + notHealed; }
        public double accuracy() { return affected() == 0 ? 1 : (double) correct / affected(); }
    }

    /** One measured element: outcome = correct | wrong | notHealed (for removed: correct = rightly not healed). */
    public record Detail(String key, String selector, String outcome, String healedTo, String reason) {
    }

    public record Options(List<String> levels, boolean llm, long seed, int maxElements, Path storageState, Properties healer) {
        public static Options defaults() {
            return new Options(LEVELS, false, 42, 30, null, new Properties());
        }
    }

    private HealingBenchmark() {
    }

    private static String load() {
        try (InputStream in = HealingBenchmark.class.getResourceAsStream("bench.js")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static List<Result> run(Browser browser, List<String> urls, Options options) {
        List<Result> results = new ArrayList<>();
        for (String url : urls) {
            for (String level : options.levels()) results.add(runOne(browser, url, level, options));
        }
        return results;
    }

    @SuppressWarnings("unchecked")
    private static Result runOne(Browser browser, String url, String level, Options options) {
        Path store;
        try {
            store = Files.createTempDirectory("healer-bench");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Properties p = new Properties();
        p.putAll(options.healer());
        p.setProperty("healer.storeDir", store.toString());
        p.setProperty("healer.llm.enabled", String.valueOf(options.llm()));
        HealerConfig config = HealerConfig.from(p);
        LocatorHealer llm = options.llm() ? LocatorHealerProvider.discover(config) : LocatorHealer.NONE;
        HealingEngine engine = new HealingEngine(config, llm);

        Browser.NewContextOptions ctx = new Browser.NewContextOptions().setViewportSize(1280, 900);
        if (options.storageState() != null) ctx.setStorageStatePath(options.storageState());
        try (BrowserContext context = browser.newContext(ctx)) {
            Page page = context.newPage();
            page.navigate(url);
            page.waitForLoadState(LoadState.NETWORKIDLE);
            PlaywrightPageAdapter adapter = new PlaywrightPageAdapter(page);

            // 1. fingerprint the elements as a test would find them today
            List<Map<String, Object>> targets = (List<Map<String, Object>>) page.evaluate(
                    "([max]) => (" + LIB + ").mark(max, e => (" + PlaywrightPageAdapter.LIB + ").uniqueSelector(e))",
                    List.of(options.maxElements()));
            for (Map<String, Object> t : targets) {
                Locator el = page.locator("[data-bench-id='" + t.get("id") + "']");
                engine.remember(key(t), (String) t.get("selector"), url, PlaywrightPageAdapter.snapshot(el));
            }

            // 2. the release
            List<Number> removed = (List<Number>) page.evaluate("([lv, seed]) => (" + LIB + ").mutate(lv, seed)",
                    List.of(level, (int) options.seed()));
            List<Integer> gone = removed.stream().map(Number::intValue).toList();

            // 3. heal every broken selector and check where it points
            int unaffected = 0, correct = 0, wrong = 0, notHealed = 0;
            List<Detail> details = new ArrayList<>();
            long healMs = 0;
            double cost = 0;
            for (Map<String, Object> t : targets) {
                int id = ((Number) t.get("id")).intValue();
                boolean isGone = gone.contains(id);
                if ("removed".equals(level) && !isGone) continue;   // only deleted elements are measured here
                String selector = (String) t.get("selector");
                if (!isGone && Integer.valueOf(id).equals(identify(page, selector))) {
                    unaffected++;
                    continue;
                }
                long t0 = System.currentTimeMillis();
                HealingEngine.Result r = engine.heal(key(t), selector, adapter);
                healMs += System.currentTimeMillis() - t0;
                if (r.suggestion() != null && r.suggestion().usage() != null) cost += r.suggestion().usage().costUsd();
                else if (r.llmUsage() != null) cost += r.llmUsage().costUsd();
                String outcome;
                if (!r.healed()) {
                    outcome = isGone ? "correct" : "notHealed";
                } else if (!isGone && Integer.valueOf(id).equals(identify(page, r.suggestion().selector()))) {
                    outcome = "correct";
                } else {
                    outcome = "wrong";
                }
                switch (outcome) {
                    case "correct" -> correct++;
                    case "wrong" -> wrong++;
                    default -> notHealed++;
                }
                details.add(new Detail(key(t), selector, outcome, r.healed() ? r.suggestion().selector() : null,
                        r.healed() ? r.suggestion().reasoning() : r.failureReason()));
            }
            return new Result(url, level, targets.size(), unaffected, correct, wrong, notHealed, healMs, cost, details);
        } finally {
            deleteQuietly(store);
        }
    }

    private static String key(Map<String, Object> t) {
        return "Bench." + t.get("tag") + "[" + t.get("id") + "]";
    }

    /** The bench id of the single element the selector matches; -1 for an unmarked one, null for none or several. */
    private static Integer identify(Page page, String selector) {
        try {
            Locator l = page.locator(selector);
            if (l.count() != 1) return null;
            String id = l.getAttribute("data-bench-id");
            return id == null ? -1 : Integer.parseInt(id);
        } catch (PlaywrightException | NumberFormatException e) {
            return null;
        }
    }

    // ---- output ---------------------------------------------------------------------------------

    /** Totals per level (all pages), in level order. */
    public static Map<String, Result> totals(List<Result> results) {
        Map<String, Result> out = new LinkedHashMap<>();
        for (String level : LEVELS) {
            List<Result> rs = results.stream().filter(r -> r.level().equals(level)).toList();
            if (rs.isEmpty()) continue;
            out.put(level, new Result("all", level, rs.stream().mapToInt(Result::elements).sum(),
                    rs.stream().mapToInt(Result::unaffected).sum(), rs.stream().mapToInt(Result::correct).sum(),
                    rs.stream().mapToInt(Result::wrong).sum(), rs.stream().mapToInt(Result::notHealed).sum(),
                    rs.stream().mapToLong(Result::healMs).sum(), rs.stream().mapToDouble(Result::costUsd).sum(), List.of()));
        }
        return out;
    }

    public static String markdown(List<Result> results, boolean llm) {
        StringBuilder sb = new StringBuilder();
        sb.append("| Level | Broken | Healed correctly | Wrong element | Not healed | Accuracy | Avg time | Cost |\n")
          .append("|---|---:|---:|---:|---:|---:|---:|---:|\n");
        totals(results).forEach((level, r) -> sb.append(String.format(Locale.ROOT, "| %s | %d | %d | %d | %d | %.1f%% | %d ms | $%.4f |%n",
                "removed".equals(level) ? "removed (must not heal)" : level, r.affected(), r.correct(), r.wrong(), r.notHealed(),
                r.accuracy() * 100, r.affected() == 0 ? 0 : r.healMs() / r.affected(), r.costUsd())));
        sb.append("\n").append(llm ? "Local heuristic + Claude." : "Local heuristic only (no Claude).")
          .append(" For `removed`, \"healed correctly\" means the deleted element was rightly reported as not healable.\n");
        return sb.toString();
    }

    public static void main(String[] args) throws IOException {
        List<String> urls = new ArrayList<>();
        List<String> levels = LEVELS;
        boolean llm = false;
        long seed = 42;
        int max = 30;
        Path state = null;
        String channel = "msedge";
        Path out = Path.of("target/healer-benchmark");
        Properties healer = new Properties();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--url" -> urls.add(args[++i]);
                case "--levels" -> levels = List.of(args[++i].split(","));
                case "--llm" -> llm = Boolean.parseBoolean(args[++i]);
                case "--seed" -> seed = Long.parseLong(args[++i]);
                case "--max" -> max = Integer.parseInt(args[++i]);
                case "--storage-state" -> state = Path.of(args[++i]);
                case "--channel" -> channel = args[++i];
                case "--out" -> out = Path.of(args[++i]);
                case "--set" -> {
                    String[] kv = args[++i].split("=", 2);
                    healer.setProperty(kv[0], kv.length > 1 ? kv[1] : "");
                }
                default -> throw new IllegalArgumentException("Unknown option " + args[i]);
            }
        }
        if (urls.isEmpty()) throw new IllegalArgumentException("At least one --url is needed");
        try (Playwright pw = Playwright.create()) {
            BrowserType.LaunchOptions launch = new BrowserType.LaunchOptions().setHeadless(true);
            if (!"chromium".equals(channel)) launch.setChannel(channel);
            Browser browser = pw.chromium().launch(launch);
            List<Result> results = run(browser, urls, new Options(levels, llm, seed, max, state, healer));
            String md = markdown(results, llm);
            Files.createDirectories(out);
            Files.writeString(out.resolve("benchmark.md"), md, StandardCharsets.UTF_8);
            Json.MAPPER.writeValue(out.resolve("benchmark.json").toFile(), Map.of("results", results, "totals", totals(results)));
            System.out.println(md);
            System.out.println("Per page: " + out.resolve("benchmark.json").toAbsolutePath());
        }
    }

    private static void deleteQuietly(Path dir) {
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(f -> f.toFile().delete());
        } catch (IOException ignored) {
        }
    }
}
