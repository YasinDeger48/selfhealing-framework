package com.selfhealing.healer.core;

import java.util.List;

/** What the healing engine needs from a browser driver (Playwright, Selenium ...). */
public interface PageAdapter {

    String url();

    /** Snapshots of the elements on the current page, each with a unique selector. */
    List<ElementSnapshot> collectCandidates();

    /** Number of elements the selector matches right now. */
    int count(String selector);

    /** Snapshot of the first element the selector matches, or null. */
    ElementSnapshot snapshot(String selector);
}
