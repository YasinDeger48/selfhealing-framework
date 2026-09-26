package com.selfhealing.healer.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static com.selfhealing.healer.core.Messages.get;

/**
 * Records the steps of one healing attempt as readable lines - the same story the demo overlay
 * tells - for the console and the HTML report. Texts come from {@link Messages}.
 */
public class HealingTrace implements HealingListener {

    /**
     * @param kind drives the colour in the report: title, error, info, candidate, detail, ok, warn, llm, quote
     * @param text the line in the run's language (console)
     * @param key  message key, so the report can re-render the line in another language (null = literal text)
     * @param args message arguments; {@link Messages.Msg} for nested messages
     */
    public record Line(String kind, String text, String key, List<Object> args) {
    }

    private static final int SHOWN_CANDIDATES = 3;

    private final List<Line> lines = new ArrayList<>();
    private final Consumer<Line> sink;
    private List<HeuristicMatcher.Scored> ranked = List.of();

    /** @param sink receives each line as it is produced (e.g. console printing); may be null */
    public HealingTrace(Consumer<Line> sink) {
        this.sink = sink;
    }

    /** Prints lines to stdout with the {@code [healer]} prefix. */
    public static Consumer<Line> console() {
        return line -> System.out.println("[healer] " + ("title".equals(line.kind()) ? "\n[healer] " : "") + line.text());
    }

    public List<Line> lines() {
        return List.copyOf(lines);
    }

    /** Best candidates of the heuristic step, for the report. */
    public List<HeuristicMatcher.Scored> topCandidates() {
        return ranked.stream().limit(SHOWN_CANDIDATES).toList();
    }

    private void add(String kind, String text) {
        emit(new Line(kind, text, null, null));
    }

    private void msg(String kind, String key, Object... args) {
        emit(new Line(kind, get(key, args), key, java.util.Arrays.asList(args)));
    }

    private void emit(Line line) {
        lines.add(line);
        if (sink != null) sink.accept(line);
    }

    private static Messages.Msg ref(String key, Object... args) {
        return new Messages.Msg(key, java.util.Arrays.asList(args));
    }

    @Override
    public void broken(String key, String selector, long waitedMs) {
        add("title", "---- " + key + " " + "-".repeat(Math.max(3, 56 - key.length())));
        if (waitedMs > 0) msg("error", "trace.notFound", selector, waitedMs);
        else msg("error", "trace.notFoundNoWait", selector);
    }

    @Override
    public void cacheHit(String key, HealingEngine.CachedHeal heal) {
        msg("ok", "trace.cacheHit", heal.source.name(), heal.healedSelector);
    }

    @Override
    public void heuristicRanked(String key, List<HeuristicMatcher.Scored> ranked, double minConfidence,
                                double minMargin, boolean accepted) {
        this.ranked = ranked;
        msg("info", "trace.cacheMiss");
        msg("info", "trace.heuristic", ranked.size(), minConfidence, minMargin);
        for (int i = 0; i < Math.min(SHOWN_CANDIDATES, ranked.size()); i++) {
            HeuristicMatcher.Scored s = ranked.get(i);
            add("candidate", String.format(Locale.ROOT, "   #%d  %.2f  %s", i + 1, s.score(), s.candidate().describe()));
            if (i == 0) msg("detail", "trace.signals", signals(s.signals()));
        }
        if (ranked.isEmpty()) return;
        double best = ranked.get(0).score();
        double margin = best - (ranked.size() > 1 ? ranked.get(1).score() : 0);
        if (accepted) {
            msg("ok", "trace.accept", best, minConfidence, margin);
        } else if (best < minConfidence) {
            msg("warn", "trace.lowScore", best, minConfidence);
        } else {
            msg("warn", "trace.tooClose", margin, minMargin);
        }
    }

    @Override
    public void llmRequested(String key, String model, int candidates) {
        msg("llm", "trace.llmRequest", model, candidates);
    }

    @Override
    public void llmAnswered(String key, LocatorHealer.Answer answer, long elapsedMs) {
        if (answer.masked() != null && !answer.masked().isEmpty()) {
            int total = answer.masked().values().stream().mapToInt(Integer::intValue).sum();
            String types = answer.masked().entrySet().stream().map(e -> e.getKey() + " " + e.getValue())
                    .collect(Collectors.joining(", "));
            msg("ok", "trace.privacy", total, types);
        }
        HealingSuggestion.LlmUsage u = answer.usage();
        Object cost = u == null ? "" : ref("trace.llmCost", u.inputTokens(), u.outputTokens(), LlmPricing.format(u.costUsd()));
        if (answer.suggestion().isEmpty()) {
            msg("error", "trace.llmNone", elapsedMs / 1000.0, cost);
            return;
        }
        HealingSuggestion s = answer.suggestion().get();
        msg("llm", "trace.llmAnswer", elapsedMs / 1000.0,
                s.element() == null ? s.selector() : s.element().describe(), s.confidence(), cost);
        msg("quote", "trace.llmReason", s.reasoning());
    }

    @Override
    public void validated(String key, String selector, int matches) {
        msg(matches == 1 ? "ok" : "error", "trace.validate", selector, matches,
                ref(matches == 1 ? "trace.validateOk" : "trace.validateRejected"));
    }

    @Override
    public void finished(String key, HealingEngine.Result result) {
        if (result.healed()) {
            HealingSuggestion s = result.suggestion();
            double cost = s.usage() == null ? 0 : s.usage().costUsd();
            msg("ok", "trace.healed", s.source().name(), s.confidence(), s.selector(), LlmPricing.format(cost),
                    ref("source." + s.source()));
            if (s.changes() != null && !s.changes().isEmpty()) {
                msg("detail", "trace.changes", s.changes().entrySet().stream()
                        .map(e -> e.getKey() + " '" + e.getValue()[0] + "' -> '" + e.getValue()[1] + "'")
                        .collect(Collectors.joining(", ")));
            }
        } else {
            msg("error", "trace.failed", result.failureReason());
        }
    }

    private static String signals(Map<String, Double> signals) {
        return signals.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue(Comparator.reverseOrder()))
                .map(e -> String.format(Locale.ROOT, "%s %.2f", e.getKey(), e.getValue()))
                .collect(Collectors.joining(" | "));
    }
}
