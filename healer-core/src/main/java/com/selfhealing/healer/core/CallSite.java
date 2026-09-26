package com.selfhealing.healer.core;

import java.util.List;

/** Where user code declared a locator: the first stack frame outside the framework, the JDK and language runtimes. */
public record CallSite(String file, int line) {

    private static final StackWalker WALKER = StackWalker.getInstance();
    private static final List<String> NOT_USER_CODE = List.of("com.selfhealing.healer.", "java.", "javax.", "jdk.", "sun.",
            "com.sun.", "kotlin.", "scala.", "groovy.", "org.codehaus.groovy.", "org.openqa.selenium.", "com.microsoft.playwright.",
            "org.junit.", "org.apache.maven.", "org.gradle.", "worker.org.gradle.", "com.intellij.");

    /** The caller's source file relative to its source root (com/acme/pages/LoginPage.java) and line; null if unknown. */
    public static CallSite find() {
        return WALKER.walk(frames -> frames
                .filter(f -> NOT_USER_CODE.stream().noneMatch(f.getClassName()::startsWith))
                .filter(f -> f.getFileName() != null && f.getLineNumber() > 0)
                .findFirst()
                .map(f -> {
                    String cls = f.getClassName();
                    String pkg = cls.contains(".") ? cls.substring(0, cls.lastIndexOf('.')).replace('.', '/') + "/" : "";
                    return new CallSite(pkg + f.getFileName(), f.getLineNumber());
                })
                .orElse(null));
    }
}
