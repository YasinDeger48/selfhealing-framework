package com.selfhealing.healer.core;

import java.util.Map;

/** List prices (USD per million tokens) used to show what each LLM heal cost. */
public final class LlmPricing {

    /** model → {input, output} per million tokens. */
    private static final Map<String, double[]> PRICES = Map.of(
            "claude-opus-5", new double[] {5.00, 25.00},
            "claude-opus-5-5", new double[] {4.00, 20.00},
            "claude-sonnet-5", new double[] {2.00, 10.00},
            "claude-haiku-4-5", new double[] {1.00, 5.00});

    private LlmPricing() {
    }

    /** Cost in USD, or -1 when the model's price is unknown. */
    public static double cost(String model, long inputTokens, long outputTokens) {
        double[] p = PRICES.get(model);
        if (p == null) return -1;
        return (inputTokens * p[0] + outputTokens * p[1]) / 1_000_000.0;
    }

    public static String format(double usd) {
        return usd < 0 ? "?" : String.format(java.util.Locale.ROOT, "$%.4f", usd);
    }
}
