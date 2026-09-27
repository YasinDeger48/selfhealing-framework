package com.selfhealing.healer.core;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Collects healing events, test results and test steps for the whole run. */
public final class HealingRecorder {

    /** One user-visible action of a test (fill, click, navigate ...). */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public static class Step {
        public int index;
        /** ms since the test started */
        public long atMs;
        public String element;
        public String action;
        public String detail;
        public String status = "OK"; // OK | HEALED | FAILED
        public Integer healId;
        public String error;
    }

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public static class TestRecord {
        public String id;
        public String className;
        public String method;
        public String displayName;
        public String status = "RUNNING"; // PASSED | FAILED | SKIPPED
        public String error;
        public Instant startedAt = Instant.now();
        public long durationMs;
        public final List<Step> steps = new ArrayList<>();
        /** Browser console errors and failed / erroring requests seen during the test (first 20 each). */
        public final List<String> consoleErrors = Collections.synchronizedList(new ArrayList<>());
        public final List<String> networkErrors = Collections.synchronizedList(new ArrayList<>());
        /** Last page URL (main frame). */
        public String lastUrl;
        /** Failure analysis, for failed tests. */
        public FailureTriage.Result triage;
        /** Report-relative paths of a Playwright video and trace of this test (browser.video / browser.trace). */
        public String video;
        public String trace;
    }

    private static final int MAX_NOTES = 20;

    private static final List<HealingEvent> EVENTS = Collections.synchronizedList(new ArrayList<>());
    private static final Map<String, TestRecord> TESTS = Collections.synchronizedMap(new LinkedHashMap<>());
    private static final ThreadLocal<String> CURRENT_TEST = new ThreadLocal<>();
    /** The test that last ran on this thread - still known in TestNG's @AfterMethod, which runs after the test ended. */
    private static final ThreadLocal<String> LAST_TEST = new ThreadLocal<>();
    private static final Map<String, List<java.util.function.Consumer<String>>> ON_FINISH = new java.util.concurrent.ConcurrentHashMap<>();
    private static final AtomicInteger EVENT_IDS = new AtomicInteger();
    private static final Instant RUN_STARTED = Instant.now();

    private HealingRecorder() {
    }

    public static Instant runStartedAt() {
        return RUN_STARTED;
    }

    public static void startTest(String testId, String className, String method, String displayName) {
        CURRENT_TEST.set(testId);
        LAST_TEST.set(testId);
        TestRecord t = new TestRecord();
        t.id = testId;
        t.className = className;
        t.method = method;
        t.displayName = displayName;
        TESTS.put(testId, t);
    }

    public static void startTest(String testId) {
        startTest(testId, null, null, testId);
    }

    public static void endTest() {
        CURRENT_TEST.remove();
    }

    public static void finishTest(String testId, String status, String error) {
        TestRecord t = TESTS.get(testId);
        if (t == null) return;
        t.status = status;
        t.error = error;
        t.durationMs = java.time.Duration.between(t.startedAt, Instant.now()).toMillis();
        List<java.util.function.Consumer<String>> waiting = ON_FINISH.remove(testId);
        if (waiting != null) {
            for (java.util.function.Consumer<String> action : waiting) {
                try {
                    action.accept(outcome(testId));
                } catch (RuntimeException ignored) {
                    // clean-up must never fail the test
                }
            }
        }
    }

    /** The running test, or the one that just ended on this thread (TestNG runs @AfterMethod after the test ended). */
    public static String currentOrLastTest() {
        String t = CURRENT_TEST.get();
        if (t == null) t = LAST_TEST.get();
        return t == null ? "(outside test)" : t;
    }

    /**
     * FAILED, PASSED or SKIPPED - or null while it is not known yet: JUnit 4 runs @After inside the test, before
     * the failure reaches the rule.
     */
    public static String outcome(String testId) {
        TestRecord t = TESTS.get(testId);
        if (t == null) return null;
        if (t.triage != null || "FAILED".equals(t.status)) return "FAILED";
        return "RUNNING".equals(t.status) ? null : t.status;
    }

    /** Runs the action with the outcome once the test is finished (for decisions that need the outcome). */
    public static void whenFinished(String testId, java.util.function.Consumer<String> action) {
        ON_FINISH.computeIfAbsent(testId, k -> Collections.synchronizedList(new ArrayList<>())).add(action);
    }

    /** The running (or just ended) test failed - e.g. to keep a video only then. */
    public static boolean currentTestFailed() {
        return "FAILED".equals(outcome(currentOrLastTest()));
    }

    /** Attaches a video or trace to the running (or just ended) test (kind: video | trace). */
    public static void attach(String kind, String reportRelativePath) {
        attach(currentOrLastTest(), kind, reportRelativePath);
    }

    public static void attach(String testId, String kind, String reportRelativePath) {
        TestRecord t = TESTS.get(testId);
        if (t == null) return;
        if ("video".equals(kind)) t.video = reportRelativePath;
        else t.trace = reportRelativePath;
    }

    public static String currentTest() {
        String t = CURRENT_TEST.get();
        return t == null ? "(outside test)" : t;
    }

    public static void record(HealingEvent event) {
        if (event.test == null) event.test = currentTest();
        event.id = EVENT_IDS.incrementAndGet();
        EVENTS.add(event);
    }

    /** Adds a step to the running test and returns it so the caller can update its status. */
    public static Step step(String element, String action, String detail) {
        Step s = new Step();
        s.element = element;
        s.action = action;
        s.detail = detail;
        TestRecord t = TESTS.get(currentTest());
        if (t != null) {
            synchronized (t.steps) {
                s.index = t.steps.size() + 1;
                s.atMs = java.time.Duration.between(t.startedAt, Instant.now()).toMillis();
                t.steps.add(s);
            }
        }
        return s;
    }

    public static TestRecord test(String testId) {
        return TESTS.get(testId);
    }

    public static void consoleError(String text) {
        TestRecord t = TESTS.get(currentTest());
        if (t != null && t.consoleErrors.size() < MAX_NOTES) t.consoleErrors.add(text);
    }

    public static void networkError(String text) {
        TestRecord t = TESTS.get(currentTest());
        if (t != null && t.networkErrors.size() < MAX_NOTES) t.networkErrors.add(text);
    }

    public static void pageUrl(String url) {
        TestRecord t = TESTS.get(currentTest());
        if (t != null) t.lastUrl = url;
    }

    public static List<HealingEvent> eventsFor(String testId) {
        synchronized (EVENTS) {
            return EVENTS.stream().filter(e -> testId.equals(e.test)).toList();
        }
    }

    public static List<HealingEvent> all() {
        synchronized (EVENTS) {
            return List.copyOf(EVENTS);
        }
    }

    public static List<TestRecord> tests() {
        synchronized (TESTS) {
            return List.copyOf(TESTS.values());
        }
    }

    public static void clear() {
        EVENTS.clear();
        TESTS.clear();
    }
}
