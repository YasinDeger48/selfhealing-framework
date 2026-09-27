package com.selfhealing.healer.playwright;

import com.selfhealing.healer.core.HealingRuntime;
import com.selfhealing.healer.core.junit.AbstractHealingExtension;

/**
 * JUnit 5 extension for Playwright tests (kept for existing projects; new projects can use
 * {@code com.selfhealing.healer.junit5.HealingExtension} from healer-junit5, which works with either adapter): WARN blocks for healed tests, failure analysis, and the JSON, HTML and PDF
 * reports at the end of the run.
 *
 * <pre>{@code @ExtendWith(HealingExtension.class)}</pre>
 *
 * @deprecated use {@code com.selfhealing.healer.junit5.HealingExtension} from healer-junit5 (same behaviour, either adapter)
 */
@Deprecated(since = "2.2.0")
public class HealingExtension extends AbstractHealingExtension {

    private static final HealingRuntime RUNTIME = new PlaywrightRuntime();

    @Override
    protected HealingRuntime runtime() {
        return RUNTIME;
    }
}
