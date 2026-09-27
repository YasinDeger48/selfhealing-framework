package com.selfhealing.healer.core.junit;

import com.selfhealing.healer.core.HealingEvent;
import com.selfhealing.healer.core.HealingRun;
import com.selfhealing.healer.core.HealingRuntime;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestExecutionExceptionHandler;
import org.junit.jupiter.api.extension.TestWatcher;

import java.util.Optional;

/**
 * The JUnit 5 extension of every adapter: tags healing events and steps with the running test, prints a WARN block
 * after each healed test, analyses failures while the page is still open, and writes the reports, code fixes and
 * locator-quality results once at the end of the run (see {@link HealingRun}). An adapter only supplies its
 * {@link HealingRuntime}.
 */
public abstract class AbstractHealingExtension implements BeforeEachCallback, AfterEachCallback, TestWatcher,
        TestExecutionExceptionHandler {

    private static final ExtensionContext.Namespace NS = ExtensionContext.Namespace.create(AbstractHealingExtension.class);

    /** The adapter's engine, screenshots, page URL and PDF printing. */
    protected abstract HealingRuntime runtime();

    private HealingRun run(ExtensionContext context) {
        return context.getRoot().getStore(NS).getOrComputeIfAbsent(RunCloser.class, k -> new RunCloser(HealingRun.shared(runtime())),
                RunCloser.class).run;
    }

    @Override
    public void beforeEach(ExtensionContext context) {
        run(context).testStarted(testId(context), context.getRequiredTestClass().getSimpleName(),
                context.getRequiredTestMethod().getName(), context.getDisplayName());
    }

    @Override
    public void afterEach(ExtensionContext context) {
        try {
            for (HealingEvent e : run(context).testBodyFinished(testId(context))) {
                context.publishReportEntry("healer.warn." + e.key, e.originalSelector + " -> " + e.healedSelector);
            }
        } finally {
            run(context).testEnded();
        }
    }

    /** Runs while the page is still open: the failure is classified and the page captured before @AfterEach closes it. */
    @Override
    public void handleTestExecutionException(ExtensionContext context, Throwable error) throws Throwable {
        run(context).analyse(testId(context), error, true);
        throw error;
    }

    @Override
    public void testSuccessful(ExtensionContext context) {
        run(context).testPassed(testId(context));
    }

    @Override
    public void testFailed(ExtensionContext context, Throwable cause) {
        run(context).testFailed(testId(context), cause);
    }

    @Override
    public void testAborted(ExtensionContext context, Throwable cause) {
        run(context).testSkipped(testId(context), cause == null ? null : cause.getMessage());
    }

    @Override
    public void testDisabled(ExtensionContext context, Optional<String> reason) {
        HealingRun run = run(context);
        run.testStarted(testId(context), context.getRequiredTestClass().getSimpleName(),
                context.getRequiredTestMethod().getName(), context.getDisplayName());
        run.testSkipped(testId(context), reason.orElse(null));
        run.testEnded();
    }

    protected static String testId(ExtensionContext context) {
        return context.getRequiredTestClass().getSimpleName() + "." + context.getRequiredTestMethod().getName();
    }

    /** Closed by JUnit when the root context ends, i.e. once after all tests. */
    @SuppressWarnings("deprecation")
    static final class RunCloser implements ExtensionContext.Store.CloseableResource, AutoCloseable {
        final HealingRun run;

        RunCloser(HealingRun run) {
            this.run = run;
        }

        @Override
        public void close() {
            run.finish();
        }
    }
}
