package com.selfhealing.healer.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static com.selfhealing.healer.core.FailureTriage.Category.ASSERTION;
import static com.selfhealing.healer.core.FailureTriage.Category.ENVIRONMENT;
import static com.selfhealing.healer.core.FailureTriage.Category.LOCATOR;
import static com.selfhealing.healer.core.FailureTriage.Category.NETWORK;
import static com.selfhealing.healer.core.FailureTriage.Category.POPUP;
import static com.selfhealing.healer.core.FailureTriage.Category.TEST_CODE;
import static com.selfhealing.healer.core.FailureTriage.Category.TIMEOUT;
import static com.selfhealing.healer.core.FailureTriage.Category.UNKNOWN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FailureTriageTest {

    /** Stand-ins with the same simple names as the Playwright / framework exceptions (matched by name). */
    static class HealingFailedException extends RuntimeException {
        HealingFailedException(String m) { super(m); }
    }

    static class TimeoutError extends RuntimeException {
        TimeoutError(String m) { super(m); }
    }

    static class PlaywrightException extends RuntimeException {
        PlaywrightException(String m) { super(m); }
    }

    static class CucumberException extends RuntimeException {
        CucumberException(String m, Throwable cause) { super(m, cause); }
    }

    private static HealingRecorder.TestRecord record(String... network) {
        HealingRecorder.TestRecord t = new HealingRecorder.TestRecord();
        t.id = "T.x";
        HealingRecorder.Step ok = new HealingRecorder.Step();
        ok.action = "fill";
        ok.element = "Login.user";
        HealingRecorder.Step failed = new HealingRecorder.Step();
        failed.action = "click";
        failed.element = "Login.submit";
        failed.status = "FAILED";
        t.steps.addAll(List.of(ok, failed));
        t.networkErrors.addAll(List.of(network));
        return t;
    }

    private static FailureTriage.Result classify(Throwable t, String... network) {
        return FailureTriage.classify(t, record(network), List.of(), "http://app/login");
    }

    @Test
    void sortsFailuresByCause() {
        assertEquals(LOCATOR, classify(new HealingFailedException("FAILED Cart.add: '#add' could not be healed")).category);
        assertEquals(POPUP, classify(new TimeoutError("locator.click: Timeout 30000ms exceeded.\n"
                + "<div class=\"modal-backdrop\"></div> intercepts pointer events")).category);
        assertEquals(ASSERTION, classify(new AssertionError("expected: <3> but was: <2>")).category);
        assertEquals(TIMEOUT, classify(new TimeoutError("locator.waitFor: Timeout 15000ms exceeded.")).category);
        assertEquals(NETWORK, classify(new PlaywrightException("page.navigate: net::ERR_CONNECTION_REFUSED at http://localhost:8080/")).category);
        assertEquals(ENVIRONMENT, classify(new PlaywrightException("Executable doesn't exist at C:\\ms-playwright\\chromium")).category);
        assertEquals(TEST_CODE, classify(new NullPointerException("Cannot invoke \"String.trim()\" because \"name\" is null")).category);
        assertEquals(TEST_CODE, classify(new CucumberException("class Hooks does not have a public zero-argument constructor",
                new NoSuchMethodException("Hooks.<init>()"))).category, "a broken setup is a test-code problem");
        assertEquals(UNKNOWN, classify(new RuntimeException("something odd")).category);
    }

    @Test
    void timeoutWithServerErrorsIsANetworkProblemAndEvidenceIsKept() {
        FailureTriage.Result r = classify(new TimeoutError("locator.click: Timeout 30000ms exceeded."),
                "HTTP 503 POST http://app/api/login", "FAILED GET http://app/api/me - net::ERR_FAILED");
        assertEquals(NETWORK, r.category);
        assertTrue(r.signals.contains("serverErrors:1"), r.signals.toString());
        assertTrue(r.signals.contains("failedRequests:1"), r.signals.toString());
        assertEquals("click Login.submit", r.failedStep);
        assertEquals("http://app/login", r.pageUrl);
    }

    @Test
    void healEarlierInTheTestIsFlagged() {
        HealingEvent heal = new HealingEvent();
        heal.status = HealingEvent.Status.HEALED;
        heal.key = "Login.user";
        FailureTriage.Result r = FailureTriage.classify(new AssertionError("expected: <Welcome> but was: <Error>"),
                record(), List.of(heal), null);
        assertEquals(ASSERTION, r.category);
        assertEquals(List.of("healedBefore:1"), r.signals);
    }
}
