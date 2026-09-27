package com.selfhealing.healer.core;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * List prices (USD per million tokens) used to show what each LLM call cost, and the per-run budget
 * ({@code healer.llm.maxCostPerRun}). Prices can be set or corrected per model:
 * {@code healer.llm.price.claude-haiku-4-5=1.00,5.00} (input, output per million tokens).
 */
public final class LlmPricing {

    /** model -> {input, output} per million tokens. */
    private static final Map<String, double[]> PRICES = new ConcurrentHashMap<>(Map.of(
            "claude-opus-5", new double[] {5.00, 25.00},
            "claude-opus-5-5", new double[] {4.00, 20.00},
            "claude-sonnet-5", new double[] {2.00, 10.00},
            "claude-haiku-4-5", new double[] {1.00, 5.00}));

    private static final Object LOCK = new Object();
    private static double spent;
    private static double maxPerRun = -1;
    private static boolean budgetReported;

    private LlmPricing() {
    }

    /** Applies healer.llm.price.* and healer.llm.maxCostPerRun. */
    public static void configure(HealerConfig config) {
        for (String key : config.keysWithPrefix("healer.llm.price.")) apply(key, config.get(key, ""));
        String max = config.get("healer.llm.maxCostPerRun", "");
        synchronized (LOCK) {
            try {
                maxPerRun = max.isBlank() ? -1 : Double.parseDouble(max);
            } catch (NumberFormatException e) {
                System.out.println("[healer] healer.llm.maxCostPerRun is not a number: " + max);
                maxPerRun = -1;
            }
        }
    }

    public static void setPrice(String model, double inputPerMillion, double outputPerMillion) {
        PRICES.put(model, new double[] {inputPerMillion, outputPerMillion});
    }

    private static void apply(String key, String value) {
        if (value == null || value.isBlank()) return;
        String[] parts = value.split(",");
        try {
            setPrice(key.substring("healer.llm.price.".length()), Double.parseDouble(parts[0].trim()), Double.parseDouble(parts[1].trim()));
        } catch (RuntimeException e) {
            System.out.println("[healer] " + key + " must be 'input,output' USD per million tokens, e.g. 1.00,5.00");
        }
    }

    /** Cost in USD, or -1 when the model's price is unknown. */
    public static double cost(String model, long inputTokens, long outputTokens) {
        double[] p = PRICES.get(model);
        if (p == null) return -1;
        return (inputTokens * p[0] + outputTokens * p[1]) / 1_000_000.0;
    }

    /** Whether another LLM call is allowed by healer.llm.maxCostPerRun (prints once when the budget is used up). */
    public static boolean withinBudget() {
        synchronized (LOCK) {
            if (maxPerRun < 0 || spent < maxPerRun) return true;
            if (!budgetReported) {
                budgetReported = true;
                System.out.println(String.format(java.util.Locale.ROOT,
                        "[healer] Claude budget reached: $%.4f of healer.llm.maxCostPerRun=$%.2f spent - no more Claude calls in this run",
                        spent, maxPerRun));
            }
            return false;
        }
    }

    /** Adds the cost of a finished call to this run's total. */
    public static void spent(double usd) {
        if (usd <= 0) return;
        synchronized (LOCK) {
            spent += usd;
        }
    }

    public static double spentThisRun() {
        synchronized (LOCK) {
            return spent;
        }
    }

    /** Tests of the framework itself. */
    static void resetBudget() {
        synchronized (LOCK) {
            spent = 0;
            budgetReported = false;
        }
    }

    public static String format(double usd) {
        return usd < 0 ? "?" : String.format(java.util.Locale.ROOT, "$%.4f", usd);
    }
}
