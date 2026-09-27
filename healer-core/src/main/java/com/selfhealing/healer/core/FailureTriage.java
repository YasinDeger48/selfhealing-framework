package com.selfhealing.healer.core;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Sorts a failed test into a likely cause from the evidence at the moment of failure - the exception chain, the failed
 * step, console and network errors, and heals made earlier in the test. Free and local; {@link FailureExplainer}
 * (Claude) can add a short explanation on top.
 */
public final class FailureTriage {

    public enum Category {
        /** An element could not be found, not even by healing: removed, or changed beyond recognition. */
        LOCATOR,
        /** Another element covered the target and could not be closed. */
        POPUP,
        /** The page behaved differently than the test expects. */
        ASSERTION,
        /** Something did not happen in time (slow page, missing element state, endless spinner). */
        TIMEOUT,
        /** The application or an API could not be reached / answered with errors. */
        NETWORK,
        /** Browser, driver or machine problem, not the application. */
        ENVIRONMENT,
        /** A bug in the test code itself. */
        TEST_CODE,
        UNKNOWN
    }

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public static class Result {
        public Category category;
        /** Extra observations as codes, optionally with a count: healedBefore, popupBefore, consoleErrors:3 ... */
        public List<String> signals = new ArrayList<>();
        public String exception;
        public String message;
        public String failedStep;
        public String pageUrl;
        public String screenshot;
        public List<String> consoleErrors = new ArrayList<>();
        public List<String> networkErrors = new ArrayList<>();
        /** Claude's explanation and next step (optional). */
        public String explanation;
        public String suggestion;
        public String llmModel;
        public long llmInputTokens;
        public long llmOutputTokens;
        public double llmCostUsd;
    }

    private static final Pattern NETWORK = Pattern.compile(
            "net::ERR_|ECONNREFUSED|ECONNRESET|ENOTFOUND|getaddrinfo|NS_ERROR_|ERR_CONNECTION|ERR_NAME_NOT_RESOLVED|ERR_INTERNET_DISCONNECTED");
    private static final Pattern ENVIRONMENT = Pattern.compile(
            "Executable doesn't exist|Failed to launch|Browser has been closed|browser has been closed|Target page, context or browser has been closed"
            + "|Host system is missing dependencies|No space left on device|OutOfMemoryError");
    private static final Pattern TIMEOUT = Pattern.compile("Timeout \\d+ms exceeded|timed out", Pattern.CASE_INSENSITIVE);
    private static final Pattern COVERED = Pattern.compile("intercepts pointer events|is covered by|element is obscured|not receiving pointer events");
    private static final List<String> TEST_CODE_ERRORS = List.of("java.lang.NullPointerException", "java.lang.ClassCastException",
            "java.lang.IllegalArgumentException", "java.lang.IllegalStateException", "java.lang.IndexOutOfBoundsException",
            "java.lang.ArrayIndexOutOfBoundsException", "java.lang.StringIndexOutOfBoundsException",
            "java.lang.NumberFormatException", "java.lang.UnsupportedOperationException", "java.util.NoSuchElementException");

    /** A broken test setup: missing dependency, wrong glue, bad constructor, undefined step ... */
    private static final List<String> SETUP_ERRORS = List.of("CucumberException", "UndefinedStepException",
            "AmbiguousStepDefinitionsException", "TestNGException", "ParameterResolutionException", "PreconditionViolationException",
            "NoSuchMethodException", "ClassNotFoundException", "NoClassDefFoundError", "ExceptionInInitializerError",
            "InstantiationException", "NoSuchFieldException");

    private FailureTriage() {
    }

    public static Result classify(Throwable error, HealingRecorder.TestRecord test, List<HealingEvent> events, String pageUrl) {
        Result r = new Result();
        Throwable root = error;
        StringBuilder chain = new StringBuilder();
        List<String> classes = new ArrayList<>();
        for (Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
            classes.add(t.getClass().getName());
            chain.append(t.getClass().getName()).append(": ").append(t.getMessage()).append('\n');
            root = t;
        }
        String text = chain.toString();
        r.exception = error.getClass().getName();
        r.message = firstLines(error.getMessage(), 6);
        r.pageUrl = pageUrl;
        if (test != null) {
            synchronized (test.steps) {
                test.steps.stream().filter(s -> "FAILED".equals(s.status)).reduce((a, b) -> b)
                        .ifPresent(s -> r.failedStep = s.action + " " + s.element + (s.detail == null || s.detail.isBlank() ? "" : " " + s.detail));
            }
            r.consoleErrors.addAll(test.consoleErrors);
            r.networkErrors.addAll(test.networkErrors);
        }

        if (classes.stream().anyMatch(c -> c.endsWith("HealingFailedException"))) r.category = Category.LOCATOR;
        else if (COVERED.matcher(text).find()) r.category = Category.POPUP;
        else if (classes.stream().anyMatch(c -> c.equals("java.lang.AssertionError") || c.startsWith("org.opentest4j.")
                || c.endsWith("ComparisonFailure") || c.startsWith("org.assertj.") || c.startsWith("org.hamcrest."))) r.category = Category.ASSERTION;
        else if (ENVIRONMENT.matcher(text).find()) r.category = Category.ENVIRONMENT;
        else if (NETWORK.matcher(text).find()) r.category = Category.NETWORK;
        else if (classes.stream().anyMatch(c -> c.endsWith("TimeoutError") || c.endsWith("TimeoutException")) || TIMEOUT.matcher(text).find()) {
            r.category = Category.TIMEOUT;
        } else if (TEST_CODE_ERRORS.contains(root.getClass().getName()) && !text.contains("com.microsoft.playwright")) {
            r.category = Category.TEST_CODE;
        } else if (classes.stream().anyMatch(c -> SETUP_ERRORS.stream().anyMatch(c::endsWith))
                || text.contains("does not have a public zero-argument constructor")) {
            r.category = Category.TEST_CODE;
        } else r.category = Category.UNKNOWN;

        // Observations that change how to read the failure.
        long heals = events.stream().filter(e -> e.kind == null && e.status == HealingEvent.Status.HEALED).count();
        if (heals > 0 && r.category != Category.LOCATOR) r.signals.add("healedBefore:" + heals);
        if (events.stream().anyMatch(e -> "popup".equals(e.kind))) r.signals.add("popupBefore");
        long server = r.networkErrors.stream().filter(n -> n.matches("^HTTP 5\\d\\d.*")).count();
        if (server > 0) r.signals.add("serverErrors:" + server);
        long failed = r.networkErrors.stream().filter(n -> n.startsWith("FAILED")).count();
        if (failed > 0) r.signals.add("failedRequests:" + failed);
        if (!r.consoleErrors.isEmpty()) r.signals.add("consoleErrors:" + r.consoleErrors.size());
        // A timeout while the server returns errors is a server problem more often than a slow page.
        if (r.category == Category.TIMEOUT && (server > 0 || failed > 0)) r.category = Category.NETWORK;
        return r;
    }

    /** One line for the console, in English. */
    public static String consoleLine(String testId, Result r) {
        return "[healer] FAILURE " + testId + ": " + r.category + " - " + describe(r.category)
                + (r.signals.isEmpty() ? "" : " " + r.signals)
                + (r.explanation == null ? "" : "\n[healer]    Claude: " + r.explanation + (r.suggestion == null ? "" : " Next: " + r.suggestion));
    }

    public static String describe(Category c) {
        return switch (c) {
            case LOCATOR -> "element not found and not healable (removed or changed beyond recognition)";
            case POPUP -> "another element covered the target";
            case ASSERTION -> "the page behaved differently than expected";
            case TIMEOUT -> "something did not happen in time";
            case NETWORK -> "the application or an API was unreachable or failing";
            case ENVIRONMENT -> "browser / machine problem, not the application";
            case TEST_CODE -> "bug in the test code or its setup";
            case UNKNOWN -> "no known pattern";
        };
    }

    private static String firstLines(String s, int n) {
        if (s == null) return null;
        String[] lines = s.split("\n");
        return String.join("\n", java.util.Arrays.copyOf(lines, Math.min(n, lines.length))).strip();
    }
}
