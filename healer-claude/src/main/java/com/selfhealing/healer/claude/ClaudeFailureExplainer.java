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
import com.selfhealing.healer.core.FailureExplainer;
import com.selfhealing.healer.core.FailureTriage;
import com.selfhealing.healer.core.HealerConfig;
import com.selfhealing.healer.core.HealingEvent;
import com.selfhealing.healer.core.HealingRecorder;
import com.selfhealing.healer.core.HealingSuggestion;
import com.selfhealing.healer.core.Json;
import com.selfhealing.healer.core.LlmPricing;
import com.selfhealing.healer.core.PrivacyFilter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Explains a failed test in one or two sentences and names the next step, from the evidence the local
 * {@link FailureTriage} collected. The evidence is masked before it leaves the machine; one cheap call per failed test.
 */
public class ClaudeFailureExplainer implements FailureExplainer {

    private static final String SYSTEM_PROMPT = """
            You triage failed UI tests (Playwright). Input JSON: test name, local category (a hint from rules), exception, \
            failed step, the last steps (action element detail status), heals and closed popups before the failure, \
            browser console errors and failed/erroring requests.
            Answer with the most likely cause in 1-2 short sentences a tester understands, and ONE concrete next step.
            Be specific: name the element, value or request involved. Do not repeat the exception text; do not guess beyond \
            the evidence - say what to check instead. Server errors point to the application or environment, not the test.
            healsAndPopups: NOT_HEALED means the element was not found and NO replacement was used - its \
            closestRejectedCandidate was rejected as too different and never clicked; do not call it a match. \
            A LOCATOR failure with a NOT_HEALED element usually means the element was removed or redesigned: say which \
            element is gone and suggest checking whether that change was intended (possible application bug) before \
            updating the page object. HEALED_AND_USED before an ASSERTION failure: the replacement may be the wrong \
            element - suggest verifying it.
            """;

    private static final JsonOutputFormat FORMAT = JsonOutputFormat.builder()
            .schema(JsonOutputFormat.Schema.builder()
                    .putAdditionalProperty("type", JsonValue.from("object"))
                    .putAdditionalProperty("properties", JsonValue.from(Map.of(
                            "summary", Map.of("type", "string", "description", "Most likely cause, 1-2 sentences"),
                            "suggestion", Map.of("type", "string", "description", "One concrete next step"))))
                    .putAdditionalProperty("required", JsonValue.from(List.of("summary", "suggestion")))
                    .putAdditionalProperty("additionalProperties", JsonValue.from(false))
                    .build())
            .build();

    private final AnthropicClient client;
    private final String model;
    private final String language;
    private final int maxCalls;
    private final AtomicInteger calls = new AtomicInteger();
    private final PrivacyFilter privacy;

    public ClaudeFailureExplainer(AnthropicClient client, HealerConfig config) {
        this.client = client;
        this.model = config.get("healer.triage.model", config.llmModel());
        this.language = config.get("healer.llm.language", "English");
        this.maxCalls = Integer.parseInt(config.get("healer.triage.maxCalls", "20"));
        this.privacy = new PrivacyFilter(config);
    }

    @Override
    public Optional<Explanation> explain(HealingRecorder.TestRecord test, FailureTriage.Result triage, List<HealingEvent> events) {
        if (calls.incrementAndGet() > maxCalls) return Optional.empty();
        String prompt = buildPrompt(test, triage, events, privacy.session());
        MessageCreateParams params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(1000L)
                .systemOfTextBlockParams(List.of(TextBlockParam.builder()
                        .text(SYSTEM_PROMPT + "\nWrite in " + language + ".")
                        .cacheControl(CacheControlEphemeral.builder().build())
                        .build()))
                .outputConfig(OutputConfig.builder().format(FORMAT).build())
                .addUserMessage(prompt)
                .build();
        Message response;
        try {
            response = client.messages().create(params);
        } catch (AnthropicException e) {
            System.out.println("[healer] Claude failure explanation failed for " + test.id + ": " + e.getMessage());
            return Optional.empty();
        }
        long in = response.usage().inputTokens() + response.usage().cacheReadInputTokens().orElse(0L)
                + response.usage().cacheCreationInputTokens().orElse(0L);
        long out = response.usage().outputTokens();
        HealingSuggestion.LlmUsage usage = new HealingSuggestion.LlmUsage(model, in, out, Math.max(0, LlmPricing.cost(model, in, out)));
        Optional<StopReason> stop = response.stopReason();
        if (stop.isPresent() && !StopReason.END_TURN.equals(stop.get())) return Optional.empty();
        String text = response.content().stream().flatMap(b -> b.text().stream()).map(t -> t.text()).findFirst().orElse("");
        try {
            JsonNode answer = Json.MAPPER.readTree(text);
            return Optional.of(new Explanation(answer.path("summary").asText(""), answer.path("suggestion").asText(""), usage));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** Compact, masked evidence: every token is paid for. */
    static String buildPrompt(HealingRecorder.TestRecord test, FailureTriage.Result triage, List<HealingEvent> events,
                              PrivacyFilter.Session masking) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("test", test.displayName != null ? test.displayName : test.id);
        p.put("category", triage.category.name());
        p.put("exception", triage.exception);
        p.put("message", truncate(masking.mask(triage.message), 600));
        if (triage.failedStep != null) p.put("failedStep", masking.mask(triage.failedStep));
        if (triage.pageUrl != null) p.put("url", masking.mask(triage.pageUrl));
        List<HealingRecorder.Step> steps;
        synchronized (test.steps) {
            steps = List.copyOf(test.steps.subList(Math.max(0, test.steps.size() - 8), test.steps.size()));
        }
        p.put("lastSteps", steps.stream().map(s -> s.action + " " + s.element + " " + masking.mask(s.detail == null ? "" : s.detail)
                + " " + s.status).toList());
        List<Map<String, Object>> heals = events.stream().filter(e -> e.status != null).limit(6).map(e -> heal(e, masking)).toList();
        if (!heals.isEmpty()) p.put("healsAndPopups", heals);
        if (!triage.consoleErrors.isEmpty()) p.put("console", triage.consoleErrors.stream().limit(5).map(c -> truncate(masking.mask(c), 200)).toList());
        if (!triage.networkErrors.isEmpty()) p.put("network", triage.networkErrors.stream().limit(5).map(n -> truncate(masking.mask(n), 200)).toList());
        try {
            return Json.MAPPER.writer().without(com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT).writeValueAsString(p);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Explicit fields: a NOT_HEALED entry's closest candidate was rejected and never used. */
    private static Map<String, Object> heal(HealingEvent e, PrivacyFilter.Session masking) {
        Map<String, Object> h = new LinkedHashMap<>();
        boolean popup = "popup".equals(e.kind);
        h.put("result", popup ? (e.status == HealingEvent.Status.FAILED ? "POPUP_NOT_CLOSED" : "POPUP_CLOSED")
                : e.status == HealingEvent.Status.FAILED ? "NOT_HEALED" : "HEALED_AND_USED");
        h.put("element", e.key);
        h.put("selector", masking.mask(e.originalSelector));
        if (popup) {
            h.put("layer", masking.mask(e.originalElement));
        } else if (e.status == HealingEvent.Status.FAILED) {
            if (e.topCandidates != null && !e.topCandidates.isEmpty()) {
                h.put("closestRejectedCandidate", truncate(masking.mask(e.topCandidates.get(0)), 160));
            }
        } else {
            h.put("usedInstead", masking.mask(e.healedSelector));
            h.put("confidence", Math.round(e.confidence * 100) / 100.0);
        }
        return h;
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max) + "…";
    }

    /** Registered via META-INF/services; uses the same key and settings as the Claude healing stage. */
    public static class Provider implements FailureExplainer.Provider {
        @Override
        public FailureExplainer create(HealerConfig config) {
            String key = System.getenv("ANTHROPIC_API_KEY");
            if (key == null || key.isBlank()) return FailureExplainer.NONE;
            return new ClaudeFailureExplainer(ClaudeHealerProvider.client(config, key), config);
        }
    }
}
