package com.selfhealing.healer.claude;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.selfhealing.healer.core.HealerConfig;
import com.selfhealing.healer.core.LocatorHealer;
import com.selfhealing.healer.core.LocatorHealerProvider;

import java.time.Duration;

/**
 * Registered via {@code META-INF/services}: putting healer-claude on the classpath enables the
 * Claude stage when {@code healer.llm.enabled=true} and {@code ANTHROPIC_API_KEY} is set.
 */
public class ClaudeHealerProvider implements LocatorHealerProvider {

    @Override
    public LocatorHealer create(HealerConfig config) {
        if (!config.llmEnabled()) return LocatorHealer.NONE;
        String key = config.get("healer.llm.apiKey", System.getenv("ANTHROPIC_API_KEY"));
        if (key == null || key.isBlank()) {
            System.out.println("[healer] healer.llm.enabled=true but no API key (healer.llm.apiKey / ANTHROPIC_API_KEY) - Claude stage disabled");
            return LocatorHealer.NONE;
        }
        return new ClaudeLocatorHealer(client(config, key), config);
    }

    /** One HTTP client per JVM, shared by the healing stage and the failure explanations. */
    private static volatile AnthropicClient shared;

    static AnthropicClient client(HealerConfig config, String key) {
        if (shared == null) {
            synchronized (ClaudeHealerProvider.class) {
                if (shared == null) {
                    shared = AnthropicOkHttpClient.builder()
                            .apiKey(key)
                            .timeout(Duration.ofSeconds(Long.parseLong(config.get("healer.llm.timeoutSeconds", "90"))))
                            .maxRetries(2)
                            .build();
                }
            }
        }
        return shared;
    }
}
