package com.selfhealing.healer.core;

import java.util.List;
import java.util.Optional;
import java.util.ServiceLoader;

/**
 * Optional LLM stage of the failure analysis: a short explanation and a next step for a failed test.
 * A module such as {@code healer-claude} registers a {@link Provider} in {@code META-INF/services}.
 */
public interface FailureExplainer {

    record Explanation(String summary, String suggestion, HealingSuggestion.LlmUsage usage) {
    }

    Optional<Explanation> explain(HealingRecorder.TestRecord test, FailureTriage.Result triage, List<HealingEvent> events);

    FailureExplainer NONE = (test, triage, events) -> Optional.empty();

    interface Provider {
        FailureExplainer create(HealerConfig config);
    }

    /** The first provider on the classpath, or {@link #NONE}. Off with {@code healer.triage.llm=false}. */
    static FailureExplainer discover(HealerConfig config) {
        boolean enabled = Boolean.parseBoolean(config.get("healer.triage.llm", String.valueOf(config.llmEnabled())));
        if (!enabled) return NONE;
        return ServiceLoader.load(Provider.class).findFirst().map(p -> p.create(config)).orElse(NONE);
    }
}
