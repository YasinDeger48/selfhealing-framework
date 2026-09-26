package com.selfhealing.healer.playwright;

/** Thrown when a selector is broken and no trustworthy replacement was found (or suggest mode is on). */
public class HealingFailedException extends RuntimeException {

    public HealingFailedException(String message) {
        super(message);
    }
}
