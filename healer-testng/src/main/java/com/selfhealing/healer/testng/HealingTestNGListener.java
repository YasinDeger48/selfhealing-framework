package com.selfhealing.healer.testng;

import com.selfhealing.healer.core.HealingRun;
import com.selfhealing.healer.core.HealingRuntime;
import org.testng.IExecutionListener;
import org.testng.IInvokedMethod;
import org.testng.IInvokedMethodListener;
import org.testng.ITestListener;
import org.testng.ITestResult;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * TestNG integration: WARN blocks for healed tests, failure analysis while the page is still open, and the reports,
 * code fixes and locator quality at the end of the run. Registered automatically through
 * META-INF/services when healer-testng is on the classpath (or add {@code @Listeners(HealingTestNGListener.class)}).
 * Works with healer-playwright and healer-selenium.
 */
public class HealingTestNGListener implements ITestListener, IInvokedMethodListener, IExecutionListener {

    private static volatile HealingRun run;

    private static HealingRun run() {
        if (run == null) {
            synchronized (HealingTestNGListener.class) {
                if (run == null) run = new HealingRun(HealingRuntime.discover());
            }
        }
        return run;
    }

    @Override
    public void onTestStart(ITestResult result) {
        run().testStarted(testId(result), result.getTestClass().getRealClass().getSimpleName(),
                result.getMethod().getMethodName(), description(result));
    }

    /** Right after the test method, before @AfterMethod closes the browser. */
    @Override
    public void afterInvocation(IInvokedMethod method, ITestResult result) {
        if (!method.isTestMethod()) return;
        String id = testId(result);
        if (result.getStatus() == ITestResult.FAILURE && result.getThrowable() != null) {
            run().analyse(id, result.getThrowable(), true);
        }
        try {
            run().testBodyFinished(id);
        } catch (AssertionError failOnHeal) {
            if (result.getStatus() == ITestResult.SUCCESS) {
                result.setStatus(ITestResult.FAILURE);
                result.setThrowable(failOnHeal);
            }
        }
    }

    @Override
    public void onTestSuccess(ITestResult result) {
        run().testPassed(testId(result));
        run().testEnded();
    }

    @Override
    public void onTestFailure(ITestResult result) {
        run().testFailed(testId(result), result.getThrowable());
        run().testEnded();
    }

    @Override
    public void onTestSkipped(ITestResult result) {
        run().testSkipped(testId(result), result.getThrowable() == null ? null : result.getThrowable().getMessage());
        run().testEnded();
    }

    @Override
    public void onTestFailedButWithinSuccessPercentage(ITestResult result) {
        onTestFailure(result);
    }

    @Override
    public void onExecutionFinish() {
        if (run != null) run.finish();
    }

    /** Class.method, plus the parameters of data-driven tests (each invocation is its own test in the report). */
    static String testId(ITestResult r) {
        String id = r.getTestClass().getRealClass().getSimpleName() + "." + r.getMethod().getMethodName();
        Object[] params = r.getParameters();
        if (params == null || params.length == 0) return id;
        String p = Arrays.stream(params).map(String::valueOf).collect(Collectors.joining(", "));
        return id + "[" + (p.length() > 60 ? p.substring(0, 60) + "..." : p) + "]";
    }

    private static String description(ITestResult r) {
        String d = r.getMethod().getDescription();
        return d == null || d.isBlank() ? testId(r) : d;
    }
}
