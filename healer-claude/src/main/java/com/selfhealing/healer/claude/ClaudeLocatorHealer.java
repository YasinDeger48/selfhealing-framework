package com.selfhealing.healer.claude;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.JsonValue;
import com.anthropic.errors.AnthropicException;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.JsonOutputFormat;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlockParam;
import com.fasterxml.jackson.databind.JsonNode;
import com.selfhealing.healer.core.ElementSnapshot;
import com.selfhealing.healer.core.HealerConfig;
import com.selfhealing.healer.core.HealingSuggestion;
import com.selfhealing.healer.core.HeuristicMatcher;
import com.selfhealing.healer.core.Json;
import com.selfhealing.healer.core.LlmPricing;
import com.selfhealing.healer.core.LocatorHealer;
import com.selfhealing.healer.core.PrivacyFilter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Asks Claude which candidate element is the one the broken selector used to find.
 *
 * <p>Claude never writes a selector: it picks an index from the candidate list (or -1 for
 * "none"), so every answer maps to a real element whose selector the engine then validates.
 */
public class ClaudeLocatorHealer implements LocatorHealer {

    private static final String SYSTEM_PROMPT = """
            You repair broken UI test locators. A selector stopped matching; pick the candidate that is the SAME element.
            Input JSON: "el" = page-object name (hints the purpose), "sel" = broken selector, "fp" = the element when \
            it last worked, "c" = numbered candidates on the page now ("s" = local similarity 0-1, a hint only).
            Element keys: tag, a = attributes, t = text, l = label, p = parent elements, xy = position.
            Match by purpose, not spelling: ids, text and even the tag may have changed; synonyms and translations count \
            (Şifre = Parola, Siparişi Tamamla = Satın Al). Use type, label, parents and position.
            Pick only the same control: never a different control that merely leads to the same place (a product-name \
            link for a removed "Details" button), a container of the element, or the same control of another list item.
            If look-alikes (e.g. identical list buttons) cannot be told apart, or nothing clearly matches, answer -1: \
            a wrong pick hides real bugs, -1 only fails the test.
            confidence: 0.9+ strong, 0.7-0.9 likely, below 0.6 answer -1. reasoning: one short sentence naming what changed.
            """;

    private static final JsonOutputFormat ANSWER_FORMAT = JsonOutputFormat.builder()
            .schema(JsonOutputFormat.Schema.builder()
                    .putAdditionalProperty("type", JsonValue.from("object"))
                    .putAdditionalProperty("properties", JsonValue.from(Map.of(
                            "candidateIndex", Map.of("type", "integer",
                                    "description", "Index of the matching candidate, or -1 if none matches"),
                            "confidence", Map.of("type", "number", "description", "0 to 1"),
                            "reasoning", Map.of("type", "string"))))
                    .putAdditionalProperty("required", JsonValue.from(List.of("candidateIndex", "confidence", "reasoning")))
                    .putAdditionalProperty("additionalProperties", JsonValue.from(false))
                    .build())
            .build();

    private final AnthropicClient client;
    private final String model;
    private final String escalateTo;
    private final boolean escalateOnNoMatch;
    private final double minConfidence;
    private final OutputConfig.Effort effort;
    private final String language;
    private final int maxCalls;
    private final AtomicInteger calls = new AtomicInteger();
    private final PrivacyFilter privacy;
    private final java.nio.file.Path promptLogDir;

    public ClaudeLocatorHealer(AnthropicClient client, HealerConfig config) {
        this.client = client;
        this.model = config.llmModel();
        this.escalateTo = config.get("healer.llm.escalateTo", "");
        this.escalateOnNoMatch = Boolean.parseBoolean(config.get("healer.llm.escalateOnNoMatch", "true"));
        this.minConfidence = config.minConfidence();
        this.effort = OutputConfig.Effort.of(config.get("healer.llm.effort", "low").toLowerCase(Locale.ROOT));
        this.language = config.get("healer.llm.language", "English");
        this.maxCalls = Integer.parseInt(config.get("healer.llm.maxCallsPerRun", "50"));
        this.privacy = new PrivacyFilter(config);
        this.promptLogDir = Boolean.parseBoolean(config.get("healer.llm.logPrompts", "false"))
                ? config.reportDir().resolve("llm-requests") : null;
    }

    /**
     * Asks the configured (cheap) model first; if it finds no match or is not confident, and
     * {@code healer.llm.escalateTo} is set, asks that stronger model once. Costs are added up.
     */
    @Override
    public Answer suggest(Request request) {
        if (request.candidates().isEmpty()) return Answer.none();
        PrivacyFilter.Session masking = privacy.session();
        String prompt = buildPrompt(request, masking);
        logPrompt(request.key(), prompt);

        Attempt first = ask(model, prompt, request);
        // A clear "-1" usually means the element is really gone; escalating it doubles the cost of every real bug.
        boolean noMatch = first.suggestion().isEmpty() && first.usage(model) != null;
        if (first.acceptable(minConfidence) || escalateTo.isBlank() || escalateTo.equals(model)
                || (noMatch && !escalateOnNoMatch)) {
            return new Answer(first.suggestion(), first.usage(model), masking.counts());
        }
        System.out.println("[healer] " + com.selfhealing.healer.core.Messages.get("trace.llmEscalate", model, escalateTo));
        Attempt second = ask(escalateTo, prompt, request);
        HealingSuggestion.LlmUsage total = new HealingSuggestion.LlmUsage(model + " -> " + escalateTo,
                first.inputTokens + second.inputTokens, first.outputTokens + second.outputTokens, first.cost + second.cost);
        Optional<HealingSuggestion> chosen = second.suggestion().map(s -> new HealingSuggestion(s.selector(), s.source(),
                s.confidence(), s.reasoning(), s.element(), s.changes(), total));
        return new Answer(chosen, total, masking.counts());
    }

    /** With healer.llm.logPrompts=true every request body is saved, so reviewers can see exactly what left the machine. */
    private void logPrompt(String key, String prompt) {
        if (promptLogDir == null) return;
        try {
            java.nio.file.Files.createDirectories(promptLogDir);
            String name = key.replaceAll("[^A-Za-z0-9._-]", "_") + "-" + System.currentTimeMillis() + ".json";
            java.nio.file.Files.writeString(promptLogDir.resolve(name), prompt, java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            System.out.println("[healer] could not write prompt log: " + e.getMessage());
        }
    }

    private record Attempt(Optional<HealingSuggestion> suggestion, long inputTokens, long outputTokens, double cost) {
        boolean acceptable(double min) {
            return suggestion.isPresent() && suggestion.get().confidence() >= min;
        }

        HealingSuggestion.LlmUsage usage(String model) {
            return inputTokens == 0 && outputTokens == 0 ? null : new HealingSuggestion.LlmUsage(model, inputTokens, outputTokens, cost);
        }
    }

    private Attempt ask(String askModel, String prompt, Request request) {
        Attempt none = new Attempt(Optional.empty(), 0, 0, 0);
        if (calls.incrementAndGet() > maxCalls) {
            System.out.println("[healer] Claude call budget reached (healer.llm.maxCallsPerRun=" + maxCalls + "), skipping " + request.key());
            return none;
        }

        MessageCreateParams params = MessageCreateParams.builder()
                .model(askModel)
                .maxTokens(4000L)
                .systemOfTextBlockParams(List.of(TextBlockParam.builder()
                        .text(SYSTEM_PROMPT + "\nWrite the reasoning in " + language + ".")
                        .cacheControl(CacheControlEphemeral.builder().build())
                        .build()))
                .outputConfig(outputConfig(askModel))
                .addUserMessage(prompt)
                .build();

        Message response;
        try {
            response = client.messages().create(params);
        } catch (AnthropicException e) {
            System.out.println("[healer] Claude call failed for " + request.key() + " (" + askModel + "): " + e.getMessage());
            return none;
        }

        long in = response.usage().inputTokens() + response.usage().cacheReadInputTokens().orElse(0L)
                + response.usage().cacheCreationInputTokens().orElse(0L);
        long out = response.usage().outputTokens();
        double cost = Math.max(0, LlmPricing.cost(askModel, in, out));

        Optional<StopReason> stop = response.stopReason();
        if (stop.isPresent() && !StopReason.END_TURN.equals(stop.get())) {
            System.out.println("[healer] Claude stopped with " + stop.get() + " for " + request.key());
            return new Attempt(Optional.empty(), in, out, cost);
        }

        String text = response.content().stream()
                .flatMap(b -> b.text().stream())
                .map(t -> t.text())
                .findFirst().orElse("");
        HealingSuggestion.LlmUsage usage = new HealingSuggestion.LlmUsage(askModel, in, out, cost);
        return new Attempt(parse(text, request.candidates(), usage), in, out, cost);
    }

    /** Haiku 4.5 does not accept the effort parameter; every other current model does. */
    private OutputConfig outputConfig(String askModel) {
        OutputConfig.Builder b = OutputConfig.builder().format(ANSWER_FORMAT);
        if (!askModel.startsWith("claude-haiku")) b.effort(effort);
        return b.build();
    }

    static Optional<HealingSuggestion> parse(String text, List<HeuristicMatcher.Scored> candidates,
                                             HealingSuggestion.LlmUsage usage) {
        try {
            JsonNode answer = Json.MAPPER.readTree(text);
            int index = answer.path("candidateIndex").asInt(-1);
            if (index < 0 || index >= candidates.size()) return Optional.empty();
            ElementSnapshot chosen = candidates.get(index).candidate();
            return Optional.of(new HealingSuggestion(chosen.getSelector(), HealingSuggestion.Source.LLM,
                    answer.path("confidence").asDouble(0), answer.path("reasoning").asText(""),
                    chosen, null, usage));
        } catch (Exception e) {
            System.out.println("[healer] Could not parse Claude answer: " + e.getMessage());
            return Optional.empty();
        }
    }

    /** The per-request part, as compact JSON with short keys: every token here is paid for on each call. */
    static String buildPrompt(Request request, PrivacyFilter.Session masking) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("el", request.key());
        payload.put("sel", masking.mask(request.originalSelector()));
        payload.put("fp", request.fingerprint() == null ? null : compact(request.fingerprint().getElement(), masking));
        List<Map<String, Object>> list = new ArrayList<>();
        for (int i = 0; i < request.candidates().size(); i++) {
            HeuristicMatcher.Scored s = request.candidates().get(i);
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("i", i);
            c.put("s", Math.round(s.score() * 100) / 100.0);
            c.putAll(compact(s.candidate(), masking));
            list.add(c);
        }
        payload.put("c", list);
        try {
            return Json.MAPPER.writer().without(com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT)
                    .writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Map<String, Object> compact(ElementSnapshot e, PrivacyFilter.Session masking) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tag", e.getTag());
        if (!e.getAttributes().isEmpty()) {
            Map<String, String> attrs = new LinkedHashMap<>();
            e.getAttributes().forEach((k, v) -> attrs.put(k, masking.mask(v)));
            m.put("a", attrs);
        }
        // mask before truncating, so a cut never leaves half an e-mail address behind
        if (e.getText() != null && !e.getText().isBlank()) m.put("t", truncate(masking.mask(e.getText()), 60));
        if (e.getLabelText() != null && !e.getLabelText().isBlank()) m.put("l", truncate(masking.mask(e.getLabelText()), 40));
        if (!e.getAncestors().isEmpty()) {
            m.put("p", e.getAncestors().subList(0, Math.min(2, e.getAncestors().size())).stream().map(masking::mask).toList());
        }
        m.put("xy", List.of(Math.round(e.getX()), Math.round(e.getY())));
        return m;
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
