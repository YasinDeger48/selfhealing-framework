package check;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.selfhealing.healer.junit5.HealingExtension;
import com.selfhealing.healer.playwright.HealerBrowser;
import com.selfhealing.healer.playwright.SelfHealingPage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The framework under Gradle: a heal and a plain-language step; CI then checks the report. */
@ExtendWith(HealingExtension.class)
class GradleHealingTest {

    static Playwright playwright;
    static Browser browser;
    BrowserContext context;
    Page page;
    SelfHealingPage healer;

    static String url(String name) throws Exception {
        return GradleHealingTest.class.getResource("/pages/" + name).toURI().toString();
    }

    @BeforeAll
    static void launch() {
        playwright = Playwright.create();
        browser = HealerBrowser.launch(playwright);
    }

    @AfterAll
    static void quit() {
        playwright.close();
    }

    @BeforeEach
    void open() {
        context = HealerBrowser.newContext(browser);
        page = context.newPage();
        healer = SelfHealingPage.wrap(page);
    }

    @AfterEach
    void close() {
        HealerBrowser.close(context);
    }

    @Test
    void healsRenamedButton() throws Exception {
        page.navigate(url("v1.html"));
        healer.locator("Form.save", "#save").click();
        page.navigate(url("v2.html"));
        healer.locator("Form.save", "#save").click();
        assertEquals("Saved", page.locator("#out").textContent());
    }

    @Test
    void plainLanguageStep() throws Exception {
        page.navigate(url("v2.html"));
        healer.find("Form.email", "the email field").fill("jane@example.com");
        assertEquals("jane@example.com", page.locator("#mail").inputValue());
    }
}
