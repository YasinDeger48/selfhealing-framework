package com.selfhealing.healer.playwright;

import com.selfhealing.healer.core.HealingRuntime;
import com.selfhealing.healer.core.junit.AbstractHealingExtension;

/**
 * JUnit 5 extension for Playwright tests: WARN blocks for healed tests, failure analysis, and the JSON, HTML and PDF
 * reports at the end of the run.
 *
 * <pre>{@code @ExtendWith(HealingExtension.class)}</pre>
 */
public class HealingExtension extends AbstractHealingExtension {

    private static final HealingRuntime RUNTIME = new PlaywrightRuntime();

    @Override
    protected HealingRuntime runtime() {
        return RUNTIME;
    }
}
