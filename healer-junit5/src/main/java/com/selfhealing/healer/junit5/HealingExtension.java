package com.selfhealing.healer.junit5;

import com.selfhealing.healer.core.HealingRuntime;
import com.selfhealing.healer.core.junit.AbstractHealingExtension;

/**
 * JUnit 5 (Jupiter) integration for either adapter - healer-playwright or healer-selenium is found on the classpath:
 * WARN blocks for healed tests, failure analysis while the page is still open, and the reports at the end of the run.
 *
 * <pre>{@code @ExtendWith(HealingExtension.class)}</pre>
 *
 * Or without any annotation: set {@code junit.jupiter.extensions.autodetection.enabled=true} (e.g. in
 * {@code src/test/resources/junit-platform.properties}) - the extension is registered in META-INF/services.
 */
public class HealingExtension extends AbstractHealingExtension {

    private static volatile HealingRuntime runtime;

    @Override
    protected HealingRuntime runtime() {
        if (runtime == null) {
            synchronized (HealingExtension.class) {
                if (runtime == null) runtime = HealingRuntime.discover();
            }
        }
        return runtime;
    }
}
