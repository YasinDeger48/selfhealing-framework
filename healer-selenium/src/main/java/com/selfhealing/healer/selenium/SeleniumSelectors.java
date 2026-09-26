package com.selfhealing.healer.selenium;

import org.openqa.selenium.By;

/**
 * Translates Selenium locators to the selector form the engine works with (CSS where possible) and back.
 * {@code By.id("login-username")} is healed as {@code #login-username}; the source literal "login-username" is kept
 * so a code fix can find and rewrite {@code By.id("login-username")}.
 */
final class SeleniumSelectors {

    /**
     * @param selector the selector as the engine sees it: CSS, {@code xpath=...} or {@code text=...}
     * @param literal  the value as written in the source (the argument of By.xxx / @FindBy)
     */
    record Parsed(String selector, String literal) {
    }

    private SeleniumSelectors() {
    }

    static Parsed parse(By by) {
        String s = by.toString();   // "By.id: login-username", "By.cssSelector: #a", "By.xpath: //a" ...
        int colon = s.indexOf(": ");
        if (!s.startsWith("By.") || colon < 0) return new Parsed(s, null);
        String kind = s.substring(3, colon);
        String value = s.substring(colon + 2);
        return switch (kind) {
            case "id" -> new Parsed("#" + cssIdent(value), value);
            case "cssSelector" -> new Parsed(value, value);
            case "name" -> new Parsed("[name=\"" + value.replace("\"", "\\\"") + "\"]", value);
            case "className" -> new Parsed("." + cssIdent(value), value);
            case "tagName" -> new Parsed(value, value);
            case "xpath" -> new Parsed("xpath=" + value, value);
            case "linkText", "partialLinkText" -> new Parsed("text=" + value, value);
            default -> new Parsed(s, null);
        };
    }

    /** A Selenium locator for a selector in the engine's form. */
    static By toBy(String selector) {
        if (selector.startsWith("xpath=")) return By.xpath(selector.substring(6));
        if (selector.startsWith("text=")) return By.partialLinkText(selector.substring(5));
        return By.cssSelector(selector);
    }

    private static String cssIdent(String v) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            boolean plain = Character.isLetterOrDigit(c) || c == '-' || c == '_';
            if (i == 0 && Character.isDigit(c)) sb.append("\\3").append(c).append(' ');
            else if (plain) sb.append(c);
            else sb.append('\\').append(c);
        }
        return sb.toString();
    }
}
