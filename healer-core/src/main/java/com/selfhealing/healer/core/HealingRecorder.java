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
    }

    private static final List<HealingEvent> EVENTS = Collections.synchronizedList(new ArrayList<>());
    private static final Map<String, TestRecord> TESTS = Collections.synchronizedMap(new LinkedHashMap<>());
    private static final ThreadLocal<String> CURRENT_TEST = new ThreadLocal<>();
    private static final AtomicInteger EVENT_IDS = new AtomicInteger();
    private static final Instant RUN_STARTED = Instant.now();

    private HealingRecorder() {
    }

    public static Instant runStartedAt() {
        return RUN_STARTED;
    }

    public static void startTest(String testId, String className, String method, String displayName) {
        CURRENT_TEST.set(testId);
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
