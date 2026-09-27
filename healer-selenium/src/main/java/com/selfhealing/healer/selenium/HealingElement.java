package com.selfhealing.healer.selenium;

import com.selfhealing.healer.core.ActionHint;
import com.selfhealing.healer.core.HealingEvent;
import com.selfhealing.healer.core.HealingRecorder;
import com.selfhealing.healer.core.Messages;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.Select;

import java.util.function.Function;

/**
 * A named element whose locator heals itself. Every action finds the element again (no stale references) and is
 * recorded as a test step; interactions first close a layer that covers the element.
 */
public class HealingElement {

    private final SelfHealingDriver owner;
    private final String key;
    private final By by;

    HealingElement(SelfHealingDriver owner, String key, By by) {
        this.owner = owner;
        this.key = key;
        this.by = by;
    }

    /** The resolved WebElement, for anything not wrapped here. Not recorded as a step. */
    public WebElement raw() {
        return owner.resolve(key, by);
    }

    public void click() { interact("click", "", e -> { e.click(); return null; }); }
    public void sendKeys(CharSequence... keys) {
        interact("fill", "'" + mask(String.join("", keys)) + "'", e -> { e.sendKeys(keys); return null; });
    }
    /** Clears the field, then types the value. */
    public void fill(String value) {
        interact("fill", "'" + mask(value) + "'", e -> {
            clearField(e);
            if (!value.isEmpty()) e.sendKeys(value);
            return null;
        });
    }
    public void clear() { interact("fill", "''", e -> { clearField(e); return null; }); }
    public void check() { interact("check", "", e -> { if (!e.isSelected()) e.click(); return null; }); }
    public void uncheck() { interact("uncheck", "", e -> { if (e.isSelected()) e.click(); return null; }); }
    public void selectByVisibleText(String text) { interact("select", "'" + text + "'", e -> { new Select(e).selectByVisibleText(text); return null; }); }
    public void selectByValue(String value) { interact("select", "'" + value + "'", e -> { new Select(e).selectByValue(value); return null; }); }
    public String getText() { return step("read", Messages.get("step.text"), WebElement::getText); }
    public String getAttribute(String name) { return step("read", name, e -> e.getDomAttribute(name)); }
    public String getValue() { return step("read", Messages.get("step.value"), e -> e.getDomProperty("value")); }
    public boolean isDisplayed() { return step("verify", Messages.get("step.visible"), WebElement::isDisplayed); }
    public boolean isSelected() { return step("verify", Messages.get("step.checked"), WebElement::isSelected); }
    public boolean isEnabled() { return step("verify", Messages.get("step.visible"), WebElement::isEnabled); }

    public String key() { return key; }
    public By by() { return by; }

    private <T> T interact(String action, String detail, Function<WebElement, T> body) {
        return step(action, detail, e -> {
            owner.clearObstruction(key, by, e);
            return body.apply(e);
        });
    }

    private <T> T step(String action, String detail, Function<WebElement, T> body) {
        HealingRecorder.Step step = HealingRecorder.step(key, action, detail);
        try {
            WebElement element;
            ActionHint.set(action);   // a cold start knows from "fill" that the element is a field
            try {
                element = raw();
            } finally {
                ActionHint.clear();
            }
            T result = body.apply(element);
            linkHeal(step);
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

    /**
     * WebElement.clear() empties the value without an input event, so React, Vue or Angular keep the old value.
     * The value is cleared through the native setter and an input event is dispatched, as real typing would.
     */
    private void clearField(WebElement e) {
        // WebElement.clear() first would update React's value tracker, and the input event would then look like no change.
        Object done = ((JavascriptExecutor) owner.driver()).executeScript(
                "const el = arguments[0];"
                + "const proto = el instanceof HTMLTextAreaElement ? HTMLTextAreaElement.prototype"
                + "  : el instanceof HTMLInputElement ? HTMLInputElement.prototype : null;"
                + "if (!proto) return false;"
                + "Object.getOwnPropertyDescriptor(proto, 'value').set.call(el, '');"
                + "el.dispatchEvent(new Event('input', { bubbles: true }));"
                + "el.dispatchEvent(new Event('change', { bubbles: true }));"
                + "return true;", e);
        if (!Boolean.TRUE.equals(done)) e.clear();   // contenteditable and others
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
