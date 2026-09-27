package com.selfhealing.healer.junit4;

import com.selfhealing.healer.core.HealingRun;
import org.junit.AssumptionViolatedException;
import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

/**
 * JUnit 4 integration: every test method becomes a test in the report with its steps and heals, failures are
 * analysed, and the reports are written when the JVM ends (JUnit 4 has no "all tests finished" hook for rules).
 * Works with healer-playwright and healer-selenium.
 *
 * <pre>{@code
 * @Rule public HealingRule healing = new HealingRule();   // e.g. in your base test class
 * }</pre>
 *
 * <p>Rules wrap {@code @Before} and {@code @After}: a failure is analysed after {@code @After} has run, so the
 * screenshot is only taken if the browser is still open then. To write the report before the JVM ends (e.g. from a
 * suite), call {@link #finishRun()}.
 */
public class HealingRule implements TestRule {

    @Override
    public Statement apply(Statement base, Description description) {
        return new Statement() {
            @Override
            public void evaluate() throws Throwable {
                HealingRun run = HealingRun.shared();
                String id = testId(description);
                String cls = description.getTestClass() == null ? description.getClassName() : description.getTestClass().getSimpleName();
                run.testStarted(id, cls, description.getMethodName(), description.getDisplayName());
                try {
                    base.evaluate();
                    run.testBodyFinished(id);   // AssertionError with healer.failOnHeal=true
                    run.testPassed(id);
                } catch (AssumptionViolatedException skipped) {
                    run.testSkipped(id, skipped.getMessage());
                    throw skipped;
                } catch (Throwable failure) {
                    run.analyse(id, failure, true);
                    run.testFailed(id, failure);
                    throw failure;
                } finally {
                    run.testEnded();
                }
            }
        };
    }

    /** Writes the reports now (they are written at JVM exit anyway). */
    public static void finishRun() {
        HealingRun.shared().finish();
    }

    static String testId(Description d) {
        String cls = d.getTestClass() == null ? d.getClassName() : d.getTestClass().getSimpleName();
        return cls + "." + d.getMethodName();
    }
}
