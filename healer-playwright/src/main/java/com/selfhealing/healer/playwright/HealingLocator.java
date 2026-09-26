package com.selfhealing.healer.playwright;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.options.WaitForSelectorState;
import com.selfhealing.healer.core.HealingEvent;
import com.selfhealing.healer.core.HealingRecorder;
import com.selfhealing.healer.core.Messages;

import java.util.Locale;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * A named element whose selector heals itself. Every action re-resolves the selector (so the
 * locator stays valid across navigations and re-renders) and is recorded as a test step.
 */
public class HealingLocator {

    private static final Pattern SECRET = Pattern.compile("pass|parola|secret|token|pin", Pattern.CASE_INSENSITIVE);

    private final SelfHealingPage owner;
    private final String key;
    private final String selector;

    HealingLocator(SelfHealingPage owner, String key, String selector) {
        this.owner = owner;
        this.key = key;
        this.selector = selector;
    }

    /** The resolved Playwright locator, for anything not wrapped here (assertions, hover ...). Not recorded as a step. */
    public Locator raw() {
        return owner.resolve(key, selector);
    }

    public void click() { step("click", "", l -> { l.click(); return null; }); }
    public void fill(String value) { step("fill", "'" + mask(value) + "'", l -> { l.fill(value); return null; }); }
    public void check() { step("check", "", l -> { l.check(); return null; }); }
    public void uncheck() { step("uncheck", "", l -> { l.uncheck(); return null; }); }
    public void selectOption(String value) { step("select", "'" + value + "'", l -> { l.selectOption(value); return null; }); }
    public void press(String keys) { step("press", keys, l -> { l.press(keys); return null; }); }
    public String textContent() { return step("read", Messages.get("step.text"), Locator::textContent); }
    public String innerText() { return step("read", Messages.get("step.text"), Locator::innerText); }
    public String inputValue() { return step("read", Messages.get("step.value"), Locator::inputValue); }
    public String getAttribute(String name) { return step("read", name, l -> l.getAttribute(name)); }
    public boolean isVisible() { return step("verify", Messages.get("step.visible"), Locator::isVisible); }
    public boolean isChecked() { return step("verify", Messages.get("step.checked"), Locator::isChecked); }

    public void waitForVisible() {
        step("wait", Messages.get("step.untilVisible"), l -> {
            l.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE));
            return null;
        });
    }

    public String key() { return key; }
    public String selector() { return selector; }

    /** Resolves (healing if needed), runs the action and records the step, linked to the heal if there was one. */
    private <T> T step(String action, String detail, Function<Locator, T> body) {
        HealingRecorder.Step step = HealingRecorder.step(key, action, detail);
        try {
            Locator locator = raw();
            linkHeal(step);
            return body.apply(locator);
        } catch (RuntimeException e) {
            linkHeal(step);
            step.status = "FAILED";
            step.error = firstLine(e.getMessage());
            throw e;
        }
    }

    private void linkHeal(HealingRecorder.Step step) {
        HealingRecorder.eventsFor(HealingRecorder.currentTest()).stream()
                .filter(e -> e.key.equals(key))
                .reduce((a, b) -> b)
                .ifPresent(e -> {
                    step.healId = e.id;
                    step.status = e.status == HealingEvent.Status.HEALED ? "HEALED" : "FAILED";
                });
    }

    private String mask(String value) {
        return SECRET.matcher(key.toLowerCase(Locale.ROOT)).find() ? "•".repeat(Math.min(8, value.length())) : value;
    }

    private static String firstLine(String message) {
        if (message == null) return "";
        int nl = message.indexOf('\n');
        return nl < 0 ? message : message.substring(0, nl);
    }
}
