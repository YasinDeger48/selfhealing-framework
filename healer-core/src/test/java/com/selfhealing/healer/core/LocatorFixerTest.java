package com.selfhealing.healer.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocatorFixerTest {

    private static final String PAGE = String.join("\r\n",
            "package com.acme.pages;",
            "",
            "public class LoginPage {",
            "    LoginPage(SelfHealingPage healer) {",
            "        user = healer.locator(\"Login.user\", \"#login-username\");",
            "        save = healer.locator(\"Login.save\", \"[data-testid=\\\"save-btn\\\"]\");",
            "        help = healer.locator(\"Login.help\", \".help\");",
            "        also = healer.locator(\"Login.help2\", \".help\");",
            "    }",
            "}",
            "");

    private static HealingEvent healed(String key, String original, String healed, String file, int line, String test) {
        HealingEvent e = new HealingEvent();
        e.key = key;
        e.status = HealingEvent.Status.HEALED;
        e.originalSelector = original;
        e.healedSelector = healed;
        e.sourceFile = file;
        e.sourceLine = line;
        e.test = test;
        return e;
    }

    private static HealerConfig config() {
        return HealerConfig.from(new Properties());
    }

    private static Path project(Path dir) throws IOException {
        Path page = dir.resolve("src/test/java/com/acme/pages/LoginPage.java");
        Files.createDirectories(page.getParent());
        Files.writeString(page, PAGE);
        Path locators = dir.resolve("src/test/resources/locators.properties");
        Files.createDirectories(locators.getParent());
        Files.writeString(locators, "# checkout\ncheckout.pay = button.pay-now\n");
        return page;
    }

    @Test
    void fixesTheDeclaringLineAndLeavesAmbiguousOnesForReview(@TempDir Path dir) throws IOException {
        Path page = project(dir);
        List<HealingEvent> events = List.of(
                healed("Login.user", "#login-username", "[data-testid=\"user-name\"]", "com/acme/pages/LoginPage.java", 5, "T.a"),
                healed("Login.user", "#login-username", "[data-testid=\"user-name\"]", "com/acme/pages/LoginPage.java", 5, "T.b"),
                healed("Login.save", "[data-testid=\"save-btn\"]", "#store", "com/acme/pages/LoginPage.java", 6, "T.a"),
                healed("Login.help", ".help", ".support", null, 0, "T.a"),
                healed("Checkout.pay", "button.pay-now", "button:text-is(\"Pay now\")", null, 0, "T.c"),
                healed("Login.missing", "#gone", "#here", null, 0, "T.a"),
                healed("Login.built", "#row-7", "#line-7", "com/acme/pages/LoginPage.java", 8, "T.a"));

        LocatorFixer.Plan plan = LocatorFixer.plan(events, config(), dir);
        Map<String, LocatorFixer.Fix> byKey = plan.fixes().stream()
                .collect(Collectors.toMap(LocatorFixer.Fix::key, Function.identity()));

        LocatorFixer.Fix user = byKey.get("Login.user");
        assertEquals(LocatorFixer.Status.READY, user.status());
        assertEquals("src/test/java/com/acme/pages/LoginPage.java", user.file());
        assertEquals(5, user.line());
        assertEquals(List.of("T.a", "T.b"), user.tests());
        assertEquals(LocatorFixer.Status.READY, byKey.get("Login.save").status());
        assertEquals(LocatorFixer.Status.READY, byKey.get("Checkout.pay").status(), "locator files are fixed too");
        assertEquals(LocatorFixer.Status.MANUAL, byKey.get("Login.help").status(), "written twice: never guess");
        assertEquals(LocatorFixer.Status.MANUAL, byKey.get("Login.missing").status());
        assertEquals(LocatorFixer.Status.MANUAL, byKey.get("Login.built").status());
        assertEquals("src/test/java/com/acme/pages/LoginPage.java", byKey.get("Login.built").file(), "points at the declaring file");

        String patch = plan.patch();
        assertTrue(patch.contains("--- a/src/test/java/com/acme/pages/LoginPage.java"), patch);
        // Double quotes inside the healed selector become single quotes: no escaping in Java.
        assertTrue(patch.contains("+        user = healer.locator(\"Login.user\", \"[data-testid='user-name']\");\r\n"), patch);
        assertTrue(patch.contains("+checkout.pay = button:text-is(\"Pay now\")\n"), patch);

        LocatorFixer.Plan applied = LocatorFixer.apply(plan, events, config(), dir);
        assertEquals(3, applied.fixes().stream().filter(f -> f.status() == LocatorFixer.Status.APPLIED).count());
        String after = Files.readString(page);
        assertTrue(after.contains("healer.locator(\"Login.user\", \"[data-testid='user-name']\");\r\n"), after);
        assertTrue(after.contains("healer.locator(\"Login.save\", \"#store\");"), after);
        assertTrue(after.contains("healer.locator(\"Login.help\", \".help\");"), "ambiguous one untouched");
        assertEquals(PAGE.length() - PAGE.replace("\r\n", "").length(), after.length() - after.replace("\r\n", "").length(),
                "line endings preserved");
    }

    @Test
    void conflictingHealsAreNotApplied(@TempDir Path dir) throws IOException {
        project(dir);
        List<HealingEvent> events = List.of(
                healed("Login.user", "#login-username", "#a", "com/acme/pages/LoginPage.java", 5, "T.a"),
                healed("Login.user", "#login-username", "#b", "com/acme/pages/LoginPage.java", 5, "T.b"));
        LocatorFixer.Plan plan = LocatorFixer.plan(events, config(), dir);
        assertEquals(LocatorFixer.Status.MANUAL, plan.fixes().get(0).status());
        assertTrue(plan.patch().isEmpty());
    }
}
