package com.selfhealing.healer.playwright;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.options.WaitForSelectorState;
import com.selfhealing.healer.core.HealingEvent;
import com.selfhealing.healer.core.HealingRecorder;
import com.selfhealing.healer.core.Messages;

import java.util.function.Function;

/**
 * A named element whose selector heals itself. Every action re-resolves the selector (so the
 * locator stays valid across navigations and re-renders) and is recorded as a test step.
 */
public class HealingLocator {

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

    public void click() { interact("click", "", l -> { l.click(); return null; }); }
    public void fill(String value) { interact("fill", "'" + mask(value) + "'", l -> { l.fill(value); return null; }); }
    public void check() { interact("check", "", l -> { l.check(); return null; }); }
    public void uncheck() { interact("uncheck", "", l -> { l.uncheck(); return null; }); }
    public void selectOption(String value) { interact("select", "'" + value + "'", l -> { l.selectOption(value); return null; }); }
    public void press(String keys) { interact("press", keys, l -> { l.press(keys); return null; }); }
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

    /** An action that needs the element on top: a covering layer (cookie banner, modal ...) is closed first. */
    private <T> T interact(String action, String detail, Function<Locator, T> body) {
        return step(action, detail, l -> {
            owner.clearObstruction(key, selector, l);
            return body.apply(l);
        });
    }

    /** Resolves (healing if needed), runs the action and records the step, linked to the heal if there was one. */
    private <T> T step(String action, String detail, Function<Locator, T> body) {
        HealingRecorder.Step step = HealingRecorder.step(key, action, detail);
        try {
            Locator locator = raw();
            T result = body.apply(locator);
            linkHeal(step);   // after the action: a popup closed during it is linked too
            return result;
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
                    // a plain-language step that was found is a normal step, not a heal
                    if (!("intent".equals(e.kind) && e.status == HealingEvent.Status.HEALED)) {
                        step.status = e.status == HealingEvent.Status.HEALED ? "HEALED" : "FAILED";
                    }
                });
    }

    private String mask(String value) {
        return com.selfhealing.healer.core.SecretNames.isSecret(key) ? "•".repeat(Math.min(8, value.length())) : value;
    }

    private static String firstLine(String message) {
        if (message == null) return "";
        int nl = message.indexOf('\n');
        return nl < 0 ? message : message.substring(0, nl);
    }
}
