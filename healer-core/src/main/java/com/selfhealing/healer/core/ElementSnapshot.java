package com.selfhealing.healer.core;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything the healer knows about one DOM element: its attributes, visible text,
 * surroundings and position. Used both as the stored fingerprint of a known-good element
 * and as a candidate collected from the current page.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonIgnoreProperties(ignoreUnknown = true)
public class ElementSnapshot {

    /** Attributes that carry identity; compared one by one when scoring. */
    public static final List<String> TRACKED_ATTRIBUTES = List.of(
            "id", "data-testid", "data-qa", "name", "aria-label", "placeholder",
            "title", "type", "role", "class", "href");

    private String tag;
    private Map<String, String> attributes = new LinkedHashMap<>();
    private String text;
    private String labelText;
    private List<String> ancestors = new ArrayList<>();
    private String xpath;
    private double x;
    private double y;
    private double width;
    private double height;
    private boolean visible;
    /** Unique selector for this element on the page it was collected from (candidates only). */
    private String selector;

    public String attr(String name) {
        String value = attributes.get(name);
        return value == null || value.isBlank() ? null : value;
    }

    /** Short human-readable description, e.g. {@code button#login-button "Giriş Yap"}. */
    public String describe() {
        StringBuilder sb = new StringBuilder(tag == null ? "?" : tag);
        if (attr("id") != null) sb.append('#').append(attr("id"));
        if (attr("data-testid") != null) sb.append("[data-testid=").append(attr("data-testid")).append(']');
        if (text != null && !text.isBlank()) {
            String t = text.length() > 40 ? text.substring(0, 40) + "…" : text;
            sb.append(" \"").append(t).append('"');
        }
        return sb.toString();
    }

    public String getTag() { return tag; }
    public void setTag(String tag) { this.tag = tag; }
    public Map<String, String> getAttributes() { return attributes; }
    public void setAttributes(Map<String, String> attributes) { this.attributes = attributes == null ? new LinkedHashMap<>() : attributes; }
    public String getText() { return text; }
    public void setText(String text) { this.text = text; }
    public String getLabelText() { return labelText; }
    public void setLabelText(String labelText) { this.labelText = labelText; }
    public List<String> getAncestors() { return ancestors; }
    public void setAncestors(List<String> ancestors) { this.ancestors = ancestors == null ? new ArrayList<>() : ancestors; }
    public String getXpath() { return xpath; }
    public void setXpath(String xpath) { this.xpath = xpath; }
    public double getX() { return x; }
    public void setX(double x) { this.x = x; }
    public double getY() { return y; }
    public void setY(double y) { this.y = y; }
    public double getWidth() { return width; }
    public void setWidth(double width) { this.width = width; }
    public double getHeight() { return height; }
    public void setHeight(double height) { this.height = height; }
    public boolean isVisible() { return visible; }
    public void setVisible(boolean visible) { this.visible = visible; }
    public String getSelector() { return selector; }
    public void setSelector(String selector) { this.selector = selector; }
}
