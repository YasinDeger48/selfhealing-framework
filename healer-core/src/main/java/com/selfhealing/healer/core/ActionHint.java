package com.selfhealing.healer.core;

/**
 * The action a locator is resolved for (fill, select, check, click ...), set by the adapters around resolving.
 * A cold start knows little about the element - but "fill" says it is a field: a div or a form cannot be the answer.
 */
public final class ActionHint {

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private ActionHint() {
    }

    public static void set(String action) {
        CURRENT.set(action);
    }

    public static void clear() {
        CURRENT.remove();
    }

    /** The action being resolved on this thread, or null. */
    public static String current() {
        return CURRENT.get();
    }

    /** The kind of element the action needs: "editable", "select", "checkable" - or null when any element will do. */
    public static String requiredKind(String action) {
        if (action == null) return null;
        return switch (action) {
            case "fill" -> "editable";
            case "select" -> "select";
            case "check", "uncheck" -> "checkable";
            default -> null;
        };
    }
}
