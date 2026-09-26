package com.selfhealing.healer.core;

import java.util.ServiceLoader;

/**
 * Service-provider hook for the LLM stage. A module such as {@code healer-claude} registers an
 * implementation in {@code META-INF/services}; adding it to the classpath is all that is needed.
 */
public interface LocatorHealerProvider {

    LocatorHealer create(HealerConfig config);

    /** The first provider on the classpath, or {@link LocatorHealer#NONE}. */
    static LocatorHealer discover(HealerConfig config) {
        return ServiceLoader.load(LocatorHealerProvider.class).findFirst()
                .map(p -> p.create(config))
                .orElse(LocatorHealer.NONE);
    }
}
