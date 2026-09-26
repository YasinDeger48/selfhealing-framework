package com.selfhealing.healer.cucumber;

import com.selfhealing.healer.core.HealingRecorder;
import com.selfhealing.healer.core.HealingRun;
import com.selfhealing.healer.core.HealingRuntime;
import io.cucumber.plugin.ConcurrentEventListener;
import io.cucumber.plugin.event.EventPublisher;
import io.cucumber.plugin.event.PickleStepTestStep;
import io.cucumber.plugin.event.Result;
import io.cucumber.plugin.event.Status;
import io.cucumber.plugin.event.TestCase;
import io.cucumber.plugin.event.TestCaseFinished;
import io.cucumber.plugin.event.TestCaseStarted;
import io.cucumber.plugin.event.TestRunFinished;
import io.cucumber.plugin.event.TestStepFinished;
import io.cucumber.plugin.event.TestStepStarted;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cucumber integration: every scenario is a test in the report, its Gherkin steps are report steps (with the healer's
 * own steps under them), failures are analysed while the page is still open, and the reports are written at the end.
 * Enable with {@code plugin = "com.selfhealing.healer.cucumber.HealingCucumberPlugin"}. Works with healer-playwright
 * and healer-selenium.
 */
public class HealingCucumberPlugin implements ConcurrentEventListener {

    private static volatile HealingRun run;
    private final Map<Object, HealingRecorder.Step> gherkinSteps = new ConcurrentHashMap<>();

    private static HealingRun run() {
        if (run == null) {
            synchronized (HealingCucumberPlugin.class) {
                if (run == null) run = new HealingRun(HealingRuntime.discover());
            }
        }
        return run;
    }

    @Override
    public void setEventPublisher(EventPublisher publisher) {
        publisher.registerHandlerFor(TestCaseStarted.class, this::scenarioStarted);
        publisher.registerHandlerFor(TestStepStarted.class, this::stepStarted);
        publisher.registerHandlerFor(TestStepFinished.class, this::stepFinished);
        publisher.registerHandlerFor(TestCaseFinished.class, this::scenarioFinished);
        publisher.registerHandlerFor(TestRunFinished.class, e -> { if (run != null) run.finish(); });
    }

    private void scenarioStarted(TestCaseStarted e) {
        TestCase tc = e.getTestCase();
        run().testStarted(testId(tc), feature(tc), tc.getName(), tc.getName());
    }

    private void stepStarted(TestStepStarted e) {
        if (e.getTestStep() instanceof PickleStepTestStep s) {
            gherkinSteps.put(s.getId(), HealingRecorder.step(s.getStep().getKeyword().trim(), "step", s.getStep().getText()));
        }
    }

    /** A failing step: the After hooks have not closed the browser yet, so the page is captured now. */
    private void stepFinished(TestStepFinished e) {
        Result r = e.getResult();
        HealingRecorder.Step step = e.getTestStep() instanceof PickleStepTestStep s ? gherkinSteps.remove(s.getId()) : null;
        if (r.getStatus() == Status.FAILED) {
            if (step != null) {
                step.status = "FAILED";
                step.error = r.getError() == null ? null : firstLine(r.getError().getMessage());
            }
            if (r.getError() != null) run().analyse(testId(e.getTestCase()), r.getError(), true);
        }
    }

    private void scenarioFinished(TestCaseFinished e) {
        String id = testId(e.getTestCase());
        Result r = e.getResult();
        try {
            run().testBodyFinished(id);
        } catch (AssertionError failOnHeal) {
            System.out.println("[healer] " + failOnHeal.getMessage() + " (" + id + ")");
        }
        switch (r.getStatus()) {
            case PASSED -> run().testPassed(id);
            case FAILED -> run().testFailed(id, r.getError());
            default -> run().testSkipped(id, r.getStatus().name());
        }
        run().testEnded();
    }

    /** Feature name.scenario name, with the line for scenario outlines (each example is its own test). */
    static String testId(TestCase tc) {
        return feature(tc) + "." + tc.getName() + (tc.getKeyword().contains("Outline") || tc.getKeyword().contains("Template")
                ? ":" + tc.getLocation().getLine() : "");
    }

    private static String feature(TestCase tc) {
        String uri = tc.getUri().toString();
        String file = uri.substring(uri.lastIndexOf('/') + 1);
        return file.endsWith(".feature") ? file.substring(0, file.length() - ".feature".length()) : file;
    }

    private static String firstLine(String s) {
        if (s == null) return null;
        int nl = s.indexOf('\n');
        return nl < 0 ? s : s.substring(0, nl);
    }
}
