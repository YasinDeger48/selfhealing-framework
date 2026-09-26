package com.selfhealing.healer.selenium;

/** A locator was broken and no trustworthy replacement was found (or healer.mode=suggest). */
public class HealingFailedException extends RuntimeException {
    public HealingFailedException(String message) {
        super(message);
    }
}
