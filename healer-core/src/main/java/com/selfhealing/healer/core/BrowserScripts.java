package com.selfhealing.healer.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * The scripts every browser adapter runs in the page: healer.js (element snapshots, candidates, stable selectors)
 * and popup.js (layers covering an element). Plain JavaScript objects; evaluate as {@code (SCRIPT).method(...)}.
 */
public final class BrowserScripts {

    public static final String HEALER = load("healer.js");
    public static final String POPUP = load("popup.js");

    private BrowserScripts() {
    }

    private static String load(String name) {
        try (InputStream in = BrowserScripts.class.getResourceAsStream(name)) {
            if (in == null) throw new IllegalStateException(name + " missing from classpath");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
