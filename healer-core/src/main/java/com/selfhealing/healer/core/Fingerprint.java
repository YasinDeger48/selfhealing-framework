package com.selfhealing.healer.core;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

/** A known-good element, captured the last time its original selector matched. */
@JsonIgnoreProperties(ignoreUnknown = true)
public class Fingerprint {

    private String key;
    private String selector;
    private String pageUrl;
    private Instant capturedAt;
    private ElementSnapshot element;

    public Fingerprint() {
    }

    public Fingerprint(String key, String selector, String pageUrl, ElementSnapshot element) {
        this.key = key;
        this.selector = selector;
        this.pageUrl = pageUrl;
        this.element = element;
        this.capturedAt = Instant.now();
    }

    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }
    public String getSelector() { return selector; }
    public void setSelector(String selector) { this.selector = selector; }
    public String getPageUrl() { return pageUrl; }
    public void setPageUrl(String pageUrl) { this.pageUrl = pageUrl; }
    public Instant getCapturedAt() { return capturedAt; }
    public void setCapturedAt(Instant capturedAt) { this.capturedAt = capturedAt; }
    public ElementSnapshot getElement() { return element; }
    public void setElement(ElementSnapshot element) { this.element = element; }
}
