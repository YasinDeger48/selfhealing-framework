# Self-Healing Locator Framework

[![CI](https://github.com/YasinDeger48/selfhealing-framework/actions/workflows/ci.yml/badge.svg)](https://github.com/YasinDeger48/selfhealing-framework/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.yasindeger48/healer-core?label=Maven%20Central)](https://central.sonatype.com/namespace/io.github.yasindeger48)

A Java library for **Playwright or Selenium** test suites (JUnit 5, TestNG, Cucumber). When a locator stops matching because the
application's markup changed, it finds the element again, keeps the test running and reports every
repair as a **WARN** with the suggested page-object fix. Works with any web application and any test
project — add it as a dependency, wrap your locators, done.

## Modules

| Artifact | What it is |
|---|---|
| `io.github.yasindeger48:healer-core` | Driver-independent engine: element fingerprints, local matching, healing cache, events, reports |
| `io.github.yasindeger48:healer-playwright` | Playwright adapter (`SelfHealingPage`, `HealingLocator`, `HealerBrowser`), HTML/PDF report |
| `io.github.yasindeger48:healer-selenium` | Selenium WebDriver adapter (`SelfHealingDriver`, `HealingElement`, `HealerDriver`), HTML/PDF report |
| `io.github.yasindeger48:healer-testng` | TestNG listener (registers itself) - use with either adapter instead of the JUnit 5 extension |
| `io.github.yasindeger48:healer-junit5` | JUnit 5 (Jupiter) extension for either adapter - `@ExtendWith(HealingExtension.class)` or auto-detection |
| `io.github.yasindeger48:healer-junit4` | JUnit 4 rule - `@Rule public HealingRule healing = new HealingRule();` |
| `io.github.yasindeger48:healer-cucumber` | Cucumber plugin - scenarios become report tests, Gherkin steps become report steps |
| `io.github.yasindeger48:healer-claude` | Optional last healing stage backed by the Claude API — enabled by adding it to the classpath |

Requirements: Java 17+, Playwright for Java **or** Selenium 4, JUnit 5 **or** JUnit 4 **or** TestNG 7 **or** Cucumber 7,
Maven Surefire **3.6.0+** (see the note below) or Gradle. Everything below works the same with both
adapters: healing, popups, locator quality, failure analysis, reports and code fixes.
See [Compatibility](#compatibility) for the tested versions and [CHANGELOG.md](CHANGELOG.md) for what changed.

## Getting started in your project

**1. Add the dependencies** to your test project - they are on Maven Central, nothing to build or install:

```xml
<dependency>
  <groupId>io.github.yasindeger48</groupId>
  <artifactId>healer-playwright</artifactId>
  <version>2.2.0</version>
  <scope>test</scope>
</dependency>
<dependency>
  <groupId>io.github.yasindeger48</groupId>
  <artifactId>healer-junit5</artifactId>
  <version>2.2.0</version>
  <scope>test</scope>
</dependency>
<!-- optional: Claude stage -->
<dependency>
  <groupId>io.github.yasindeger48</groupId>
  <artifactId>healer-claude</artifactId>
  <version>2.2.0</version>
  <scope>test</scope>
</dependency>
```

Use `healer-selenium` instead of `healer-playwright` for Selenium, and the module of your test runner:
`healer-junit5`, `healer-junit4`, `healer-testng` or `healer-cucumber`. (The JUnit 5 extensions inside the adapters,
`...playwright.HealingExtension` and `...selenium.SeleniumHealingExtension`, still work but are deprecated since 2.2.0.) Gradle: `testImplementation("io.github.yasindeger48:healer-playwright:2.2.0")`.
Building from source instead: `mvn install` in this repository, then use the same coordinates.

**2. Wrap your Playwright page and give each element a stable name:**

```java
@ExtendWith(HealingExtension.class)   // com.selfhealing.healer.junit5.HealingExtension
class LoginTest {

    SelfHealingPage healer = SelfHealingPage.wrap(page);   // your existing Playwright Page

    @Test
    void login() {
        healer.navigate(baseUrl + "/login");
        healer.locator("LoginPage.username", "#username").fill("jane");
        healer.locator("LoginPage.password", "input[name='password']").fill("secret");
        healer.locator("LoginPage.submit", "[data-testid='login']").click();
    }
}
```

- The first argument is the element's **logical name** (usually `PageObject.field`); fingerprints and
  heals are stored under it, so keep it stable.
- `HealingLocator` wraps the common actions (`click`, `fill`, `selectOption`, `check`, `textContent` ...)
  and records each one as a report step; `raw()` returns the resolved Playwright `Locator` for anything else.

**Shadow DOM and iframes.** Elements inside open shadow roots (web components) are found and healed
like any other - no extra code. For an iframe, scope the healer to it (the iframe selector itself is not healed):

```java
SelfHealingPage payment = healer.frame("iframe#payment");
payment.locator("Payment.cardNumber", "#card").fill("4111 1111 1111 1111");
```

**Selenium instead of Playwright:** depend on `healer-selenium` and wrap your `WebDriver`. Any `By` works
(`id`, `name`, `cssSelector`, `xpath`, `className`, `linkText` ...); healed selectors are CSS.

```java
@ExtendWith(HealingExtension.class)   // the same healer-junit5 extension
class LoginTest {

    SelfHealingDriver healer = SelfHealingDriver.wrap(driver);   // your existing WebDriver

    @Test
    void login() {
        healer.navigate(baseUrl + "/login");
        healer.element("LoginPage.username", By.id("username")).fill("jane");
        healer.element("LoginPage.password", By.name("password")).fill("secret");
        healer.element("LoginPage.submit", By.cssSelector("[data-testid='login']")).click();
    }
}
```

`HealingElement` finds the element again on every action (no stale references) and wraps `click`, `fill`,
`sendKeys`, `clear`, `check`, `selectByVisibleText`, `getText` ...; `raw()` returns the `WebElement`. `fill` and `clear`
fire the input event React, Vue and Angular listen to (plain `WebElement.clear()` does not). Code fixes rewrite
`By.id("x")` / `@FindBy(id = "x")` to `By.cssSelector("...")` / `@FindBy(css = "...")`.

**Plain-language steps (no selector).** Name an element the way a person would, in any language:

```java
healer.find("Contact.email", "the email field").fill("jane@example.com");     // Playwright
healer.find("Contact.send", "mesajı gönder butonu").click();                  // Selenium: same method
```

The description is matched locally first (visible text, label, aria-label, placeholder; "button", "field",
"checkbox" ... must fit the element's type) - free. If that is not clear, Claude picks the element (about $0.002).
The selector and fingerprint are saved, so later runs cost nothing and a changed element is healed like any other.
Look-alikes the description cannot tell apart (three identical "Add" buttons) fail the step instead of guessing.
Resolved steps are shown in the report as information, never as a WARN.

**JUnit 5 with either adapter:** `healer-junit5` offers one extension for Playwright and Selenium:
`@ExtendWith(com.selfhealing.healer.junit5.HealingExtension.class)` - or no annotation at all with
`junit.jupiter.extensions.autodetection.enabled=true` in `src/test/resources/junit-platform.properties`.

**TestNG or Cucumber instead of JUnit 5.** Everything else stays the same (adapter, `healer.locator` / `healer.element`,
`healer.properties`); only the test-framework hook changes:

- **TestNG:** add `healer-testng`. The listener registers itself through `META-INF/services` - nothing else to do
  (or declare it explicitly: `@Listeners(HealingTestNGListener.class)` / `<listener>` in testng.xml). Failures are
  analysed right after the test method, before `@AfterMethod` closes the browser.
- **Cucumber:** add `healer-cucumber` and the plugin to your runner:
  `@ConfigurationParameter(key = PLUGIN_PROPERTY_NAME, value = "com.selfhealing.healer.cucumber.HealingCucumberPlugin")`
  (JUnit Platform) or `plugin = "com.selfhealing.healer.cucumber.HealingCucumberPlugin"` (`@CucumberOptions`).
  Each scenario is a test in the report and its Gherkin steps are report steps; a failing step is analysed before the
  `@After` hooks close the browser. Works with every Cucumber runner (JUnit Platform, TestNG, JUnit 4); with Cucumber's
  TestNG runner the scenarios are listed once, not also as TestNG `runScenario` tests.
- **JUnit 4:** add `healer-junit4` and `@Rule public HealingRule healing = new HealingRule();` (e.g. in the base
  class). The report is written when the JVM ends (or call `HealingRule.finishRun()`).

> **Surefire version.** Use `maven-surefire-plugin` **3.6.0 or newer**. With 3.5.x, Cucumber on the JUnit Platform
> (`@Suite`) runs the scenarios but Surefire counts none - **a failing scenario does not fail the build**. The healer
> prints `[healer] N test(s) FAILED` at the end of every run as a safety net, whatever the build result says.

**3. Run the suite once against a working build** — this records the fingerprints (`.healer/`).
From then on, broken locators are healed. Commit `.healer/` to share the baseline with your team.

## How healing works

1. While a selector works, the healer stores the element's fingerprint: attributes, text, label,
   parent elements and position.
2. When a selector does not match within `healer.probeTimeoutMs`, it tries in order:
   **healing cache** (free) → **local heuristic** (free, milliseconds) → **Claude** (only if the heuristic is not confident).
3. A replacement must match **exactly one** element on the live page, otherwise it is rejected.
4. The test continues; the report shows old/new selector, changed attributes, confidence, reasoning,
   a screenshot and the cost. If the original selector works again (application fixed), the cached heal is dropped.

### Selectors that last

A healed selector is only useful if it still works on the next build. Values with a generated part (6+ letters/digits
including a digit, e.g. `booking-reference-fe465c1e`, `45901727`) are treated as unstable, and the new selector is
chosen in this order: stable identifiers (`data-testid`, `id`, `data-qa`, `name`) → the stable prefix of a generated one
(`[data-testid^='booking-reference-input-']`) → stable descriptive attributes (`aria-label`, `placeholder`, `title`) →
the visible text of a button or link → the generated value → a structural path.

### Cold start

An element that was never seen working (a new test, or ids that change on every page load) has no recorded fingerprint.
The healer then derives one from the selector itself (`#passenger-surname-fe465c1e` → id ≈ "passenger-surname" plus a
generated part), heals with it, and stores the healed element's real fingerprint for the next runs.

### Popups and overlays

Before a click, fill, check, select or press, the healer checks whether another layer covers the element (cookie banner,
newsletter modal, promo overlay). If so, it closes the layer with its close / accept / "not now" button - recognised in
all six languages and by icon-only close buttons - or with Escape, then continues. Buttons that delete, buy, pay,
subscribe or submit are never clicked. Each closed layer is a WARN in the report (with a screenshot of the button) so the
team can decide whether the test should handle it. Layers that do not cover the element are left alone.

### Protection against false positives

A heal that points at the wrong element would hide a real bug, so the healer prefers failing the step:

- A candidate must score at least `healer.minConfidence` **and** beat the runner-up by `healer.minMargin`.
- **Different item number = different record.** `add-to-cart-8` vs `add-to-cart-5`, or a parent
  `product-card-8` vs `product-card-5`, halves the score: a look-alike from another list item never wins,
  even when it is the only similar element left. Renames that keep the number (`add-to-cart-8` → `btn-add-8`) are not affected.
- **Opposite controls never replace each other:** `quantity-increase` vs `quantity-decrease`, next/previous,
  open/close, accept/reject, login/logout ... halve the score and are never offered to Claude.
- **Type must fit:** a field never replaces a button (or the other way round); a container never replaces either.
- **Another locator's element is not a replacement:** if `tab-reviews` is removed, `tab-specs` - known to another
  locator - is never chosen.
- Claude answers -1 when nothing on the page clearly is the element; every pick is validated on the page and must pass
  the same guards.
- Measured on the demo (removed-elements scenario): 4 of 4 deleted elements correctly reported as NOT HEALED.
  Best wrong candidates scored 0.28–0.43, correct heuristic heals 0.69–0.97, so the 0.60 threshold sits in the gap.

### Privacy

Before anything is sent to Claude, page content is masked: e-mail addresses, phone numbers, payment cards
(Luhn-checked), IBANs, Turkish national ids (checksum), JWTs/API tokens and numbers of 6+ digits become
`[EMAIL]`, `[PHONE]`, `[CARD]` ... The healing trace and the report show how many values were masked.

- Add your own rules: `healer.privacy.patterns=CUSTOMER=CUST-\d{4};ORDER=ORD-[A-Z0-9]+`
- Names and free text cannot be recognised by patterns - add rules for data specific to your application.
- Audit exactly what leaves the machine: `healer.llm.logPrompts=true` saves every request under `<reportDir>/llm-requests/`.
- Only element attributes, short texts and positions are sent - never screenshots, cookies or form values.

### Claude stage

- Claude receives the element name, the broken selector, the fingerprint and the best 10 local
  candidates as a **numbered list**. It never writes selectors: it returns a candidate number
  (or -1 for "none"), a confidence and a reason (structured JSON). It cannot invent a selector, and its
  pick is validated on the page like any other.
- It matches by meaning — synonyms and translations such as *Password ↔ Passphrase*, *Place Order ↔ Buy Now* —
  which string similarity cannot do; with look-alike elements and no distinguishing context it answers -1.
- The cheap model is asked first (`healer.llm.model`, default `claude-haiku-4-5`); only if it finds no
  match or is not confident is the stronger model asked (`healer.llm.escalateTo`, default `claude-opus-5`).
- Needs `ANTHROPIC_API_KEY` in the environment. Without it the stage switches itself off and the
  heuristic keeps working.
- Measured cost per Claude heal (~1.7–2.4K input / ~100 output tokens): Haiku 4.5 ≈ $0.002,
  Sonnet 5 ≈ $0.006, Opus 5 ≈ $0.014. Successful heals are cached, so a repeated break costs nothing.

## Measured accuracy

`HealingBenchmark` fingerprints the interactive elements of a page, simulates a release in the browser and heals every
broken selector. Each element carries a hidden marker the matcher never sees, so every heal is checked: correct element,
**wrong element** (a false positive - the dangerous case) or not healed. Levels are cumulative:

| Level | What changes |
|---|---|
| low | ids, test ids and classes renamed |
| medium | + name, placeholder and text changed, element wrapped in a new container |
| high | + one identifier removed, buttons re-tagged as links, elements moved |
| extreme | + all identifiers (id, data-testid, name) and classes removed |
| removed | 40% of the elements deleted - the correct answer is "not healable" |

ShopLab demo (4 pages, 86 elements, seed 42):

| Level | Local heuristic only ($0) | + Claude Haiku |
|---|---:|---:|
| low | 100% | 100% |
| medium | 97.7% | 100% |
| high | 87.2% | 98.8% |
| extreme | 22.1% | 97.7% |
| removed (must not heal) | 100% | 100% |
| **Wrong element picked** | **0** | **0** |

Claude cost for all 373 broken elements: $0.13. Candidates ruled out by the guards (another list item, the opposite
control, a field for a button, an element another locator owns) are never sent to Claude.

Run it on your own application:

```bash
java -cp <test classpath> com.selfhealing.healer.playwright.HealingBenchmark \
     --url https://app.example.com/login --url https://app.example.com/cart \
     --storage-state logged-in.json   # optional: Playwright storage state for pages behind a login
     [--llm true] [--levels low,medium,high,extreme,removed] [--seed 42] [--max 30] [--set healer.llm.escalateTo=]
```

Results go to `target/healer-benchmark/benchmark.md` and `benchmark.json` (per element: outcome and reason).
CI runs the benchmark on three bundled pages on every push and fails on any wrong heal or on accuracy below the
thresholds in `HealingBenchmarkTest`.

## Report

At the end of the run, in `target/healer-report/`:

| File | Content |
|---|---|
| `healing-report.html` | Self-contained **Test Automation Report**: summary, "needs review" table with page-object fixes, test steps, and per heal the healing trace, a focused screenshot, changed attributes, candidates and cost. Sections open collapsed; a language menu switches the whole report (EN, DE, RU, JA, TR, AR) in the browser |
| `healing-report.pdf` | The same report as PDF (printed with the installed Edge; the HTML also has a "Save as PDF" button) |
| `healing-report.json` | Machine-readable data for CI |
| `locator-fixes.patch` | Source-code fixes for the healed locators (unified diff) - see below |
| `locator-improvements.patch` | More stable selectors for risky locators that still work (optional) |

Claude's reasoning stays in the language of the run; everything else in the report follows the language menu.
Every heal shows its cost: local and cached heals `$0.0000`, Claude heals with tokens and dollars.
Rebuild the HTML/PDF from the JSON without re-running tests: `ReportCli [reportDir]`.

### Code fixes

The healer records where each locator is declared (`healer.locator(...)` call site) and finds the broken selector in the
sources: Java/Kotlin/Groovy/Scala string literals and locator files (`.properties`, `.json`, `.yaml`). At the end of the run:

- `locator-fixes.patch` replaces every selector whose location is unambiguous with its healed version - review it, then
  `git apply target/healer-report/locator-fixes.patch`. Or run once with `-Dhealer.fix=apply` to write the changes directly.
- Selectors that are built at runtime (`"#row-" + id`), written in several places, or healed differently by different tests
  are listed as **manual** with the declaring file and line - they are never guessed.
- The report has a **Code fixes** section, and each "needs review" row shows the file and line.

### Locator quality

Every selector used in the run is rated by how likely it is to break: absolute XPath, position (`nth-child`, `[3]`),
generated parts (`#btn-a8f3c2`), only tags or only CSS classes, deep chains, visible text. For a risky selector that
still works, the healer asks the live element for a more stable one (`[data-testid='checkout']`). The report has a
**Locator quality** section, and `locator-improvements.patch` applies the suggestions - it is never applied automatically.

### Failure analysis

When a test fails, the healer captures the evidence while the page is still open - the error, the failed step, the URL,
a screenshot, browser console errors and failed or erroring requests, and heals or popups earlier in the test - and sorts
the failure into a likely cause: **element missing**, **covered element**, **unexpected behaviour** (assertion),
**timeout**, **network / server**, **environment**, **test code**. This is local and free. With the Claude stage enabled,
Claude adds a one-or-two sentence explanation and a next step per failed test (masked evidence, about $0.001 per test).
Each failed test card in the report shows the analysis; the Failed KPI shows the causes.

## Configuration

### Where settings come from

Settings are read in layers; a later layer overrides an earlier one:

1. `src/test/resources/healer.properties` - the project's shared settings (commit it)
2. `healer-<profile>.properties` - when a profile is set: `-Dhealer.profile=ci`, `HEALER_PROFILE=ci` or
   `healer.profile=ci` in the file (e.g. headless and no report opening on CI)
3. `healer-local.properties` - **personal** settings, not committed (add it to `.gitignore`); on the classpath or in
   the project folder. E.g. `browser.headless=false` on your machine only.
4. environment variables - `HEALER_REPORT_OPEN=always` -> `healer.report.open`, `BROWSER_HEADLESS=false` ->
   `browser.headless`, `APP_BASEURL=...` -> `app.baseUrl`
5. system properties - `-Dhealer.mode=suggest`, `-Dbrowser.name=firefox`

Values can reference the environment or system properties: `${env:NAME}`, `${env:NAME:-default}`, `${sys:name}`.
A misspelled setting is reported at start-up: `unknown setting 'healer.reprot.open' - did you mean 'healer.report.open'?`

**API key:** never write the key itself into a committed file. Use the default (`ANTHROPIC_API_KEY` environment
variable), a reference (`healer.llm.apiKey=${env:MY_CLAUDE_KEY}`), or put it in the uncommitted `healer-local.properties`.

### Example `healer.properties`

```properties
# --- healing
healer.mode=auto
healer.probeTimeoutMs=3000
healer.language=en

# --- report
healer.report.open=onWarn          # never | always | onFailure | onWarn - never on CI
healer.report.pdf=auto             # auto = not on CI
healer.report.screenshots=all      # all | failures | none
healer.report.history=true         # keep every run in target/healer-report/history/<timestamp>
healer.report.historyKeep=10

# --- browser (HealerBrowser for Playwright, HealerDriver for Selenium)
browser.name=msedge                # chromium | msedge | chrome | firefox | webkit
browser.headless=true
browser.slowmo=0
browser.viewport=1280x900
browser.timeoutMs=15000
browser.video=failures             # off | failures | all   (Playwright)
browser.trace=failures             # off | failures | all   (Playwright)
app.baseUrl=https://my.app.example

# --- Claude
healer.llm.enabled=true
healer.llm.apiKey=${env:ANTHROPIC_API_KEY}
healer.llm.model=claude-haiku-4-5
healer.llm.maxCostPerRun=1.00      # USD for all Claude calls of a run; empty = no limit
# healer.llm.price.claude-haiku-4-5=1.00,5.00   (USD per million input,output tokens)
```

and `healer-ci.properties` next to it:

```properties
browser.headless=true
healer.report.open=never
healer.verbose=false
```

### Browser setup from the settings

Test projects no longer need to hard-code the browser:

```java
// Playwright
Browser browser = HealerBrowser.launch(playwright);          // browser.name, headless, slowmo
BrowserContext context = HealerBrowser.newContext(browser);  // viewport, timeout, video/trace recording
page.navigate(HealerBrowser.url("/login"));                  // app.baseUrl + path
HealerBrowser.close(context);                                // in @AfterEach: keeps video/trace for failed tests

// Selenium
WebDriver driver = HealerDriver.create();                    // browser.name, headless, viewport, timeouts
driver.get(HealerDriver.url("/login"));
```

Videos and traces of kept tests are linked on the test in the report (`npx playwright show-trace <file>` opens a
trace). Video needs Playwright's ffmpeg - with an installed browser (`PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD`) install it
once: `mvn exec:java -e -Dexec.mainClass=com.microsoft.playwright.CLI -Dexec.args="install ffmpeg"`; without it the
run continues without video.

### All settings

| Setting | Default | Meaning |
|---|---|---|
| `healer.enabled` | `true` | `false` turns the healer off completely: no healing, reports, analysis or Claude calls |
| `healer.profile` | (empty) | Loads `healer-<profile>.properties` on top |
| `healer.language` | `en` | Language of console trace, demo overlay and the report's default: `en`, `de`, `ru`, `ja`, `tr`, `ar` |
| `healer.mode` | `auto` | `off` / `suggest` (report the replacement, fail the step) / `auto` (heal, continue, WARN) |
| `healer.runId` | (empty) | Same value for all JVMs of one build -> one merged report (forkCount > 1) |
| `healer.probeTimeoutMs` | `3000` | Wait for the original selector before healing (SPA render delay) |
| `healer.minConfidence` | `0.60` | Matches below this score are rejected |
| `healer.minMargin` | `0.08` | Best match must beat the runner-up by this much |
| `healer.storeDir` | `.healer` | Fingerprints and healing cache |
| `healer.reportDir` | `target/healer-report` | Report output |
| `healer.failOnHeal` | `false` | `true`: a healed test fails (strict CI) |
| `healer.popups` | `auto` | Close layers that cover an element before interacting with it; `off` disables |
| `healer.lint` | `on` | Rate every selector and suggest stable ones (`locator-improvements.patch`); `off` disables |
| `healer.triage` | `on` | Failure analysis for failed tests; `off` disables |
| `healer.triage.llm` | = `healer.llm.enabled` | Claude explanation for each failed test |
| `healer.triage.model` | = `healer.llm.model` | Model for the explanations |
| `healer.triage.maxCalls` | `20` | Explanation budget per run |
| `healer.fix` | `patch` | Code fixes: `patch` (write `locator-fixes.patch`), `apply` (change the source files), `off` |
| `healer.fix.sourceDirs` | `src/test/java,src/main/java,…` | Where to look for selectors (also Kotlin/Groovy/Scala and `src/*/resources`) |
| `healer.fix.extensions` | `java,kt,groovy,scala,properties,json,yaml,yml` | Files searched for selectors |
| `healer.verbose` | `true` | Print every healing step to the console |
| `healer.visual` | `false` | Draw the healing steps on the page (headed demos) |
| `healer.visual.pauseMs` | `1200` | Pause between drawn steps |
| `healer.report.open` | `never` | Open the HTML report after the run: `never`, `always`, `onFailure`, `onWarn` (failures or heals). Never on CI |
| `healer.report.pdf` | `auto` | Also write the PDF report; `auto` = not on CI |
| `healer.report.pdfBrowser` | `msedge` | Browser for PDF printing: `msedge`, `chrome`, `chromium` |
| `healer.report.screenshots` | `all` | `all` (heals and failures), `failures`, `none` - keeps big runs' reports small |
| `healer.report.history` | `false` | Keep a copy of every run's report in `history/<timestamp>/` |
| `healer.report.historyKeep` | `10` | Number of history entries kept |
| `browser.name` | `msedge` | `chromium`, `msedge`, `chrome`, `firefox`, `webkit` (`HealerBrowser` / `HealerDriver`) |
| `browser.headless` | `true` | Headless browser |
| `browser.slowmo` | `0` | Milliseconds between browser actions (Playwright) |
| `browser.viewport` | `1280x900` | Window / viewport size |
| `browser.timeoutMs` | `15000` | Default action timeout (Playwright) / page load and script timeout (Selenium) |
| `browser.video` | `off` | `off`, `failures`, `all` - Playwright video, linked in the report |
| `browser.trace` | `off` | `off`, `failures`, `all` - Playwright trace, linked in the report |
| `app.baseUrl` | (empty) | Base URL of the application: `HealerBrowser.url(path)` / `HealerDriver.url(path)` |
| `healer.llm.enabled` | `false` | Enable the Claude stage (needs `healer-claude` and an API key) |
| `healer.llm.apiKey` | `ANTHROPIC_API_KEY` | The key or a reference: `${env:MY_KEY}` - never commit the key itself |
| `healer.llm.model` | `claude-haiku-4-5` | First model asked |
| `healer.llm.escalateTo` | `claude-opus-5` | Asked when the first model is not confident (empty = off) |
| `healer.llm.escalateOnNoMatch` | `true` | Also escalate when the first model answers "no match" |
| `healer.llm.maxCostPerRun` | (no limit) | USD budget for all Claude calls of a run (heals and failure explanations); then Claude is skipped |
| `healer.llm.price.<model>` | built-in list | Price per million tokens `input,output`, e.g. `healer.llm.price.claude-haiku-4-5=1.00,5.00` |
| `healer.llm.logPrompts` | `false` | Save every Claude request (after masking) for audit |
| `healer.privacy.mask` | `true` | Mask personal/secret data before it is sent to Claude |
| `healer.privacy.patterns` | (empty) | Extra masking rules: `NAME=regex;NAME2=regex` |
| `healer.llm.effort` | `low` | `low` … `max` (ignored by Haiku) |
| `healer.llm.language` | from `healer.language` | Language of Claude's reasoning |
| `healer.llm.candidates` | `10` | Candidates sent to Claude — fewer is cheaper |
| `healer.llm.maxCallsPerRun` | `50` | Claude call budget per run |
| `healer.llm.timeoutSeconds` | `90` | Per-call timeout; on error the step simply fails |

## Compatibility

Tested on every change (CI) and with the [compatibility matrix](https://github.com/YasinDeger48/selfhealing-demo/tree/main/compatibility-matrix)
(one project per combination, each run once passing and once with a deliberately failing test):

| | Tested |
|---|---|
| Java | 17, 21, 25 |
| OS | Linux, Windows (macOS: not tested in CI) |
| Build | Maven (Surefire 3.6.0+), Gradle 8 (`useJUnitPlatform()`) |
| Playwright for Java | 1.45 - 1.63 |
| Selenium | 4.21 - 4.49 |
| Test runners | JUnit 5, JUnit 4, TestNG 7, Cucumber 7 on the JUnit Platform, TestNG and JUnit 4 |
| Parallel | JUnit 5 (`junit.jupiter.execution.parallel.*`), TestNG methods, Cucumber on the JUnit Platform and TestNG |
| Browsers | Chromium, Chrome, Edge (Playwright and Selenium); Firefox and WebKit/Safari can be selected with `browser.name`, not in CI |

Older Playwright/Selenium versions may work but are not tested. The framework is built against the newest
versions in the table; your project's own Playwright/Selenium version wins (Maven's nearest definition).

## Parallel runs

- Parallel threads in one JVM are supported (per-thread test context, synchronized stores): JUnit 5 parallel
  execution, TestNG `parallel=methods`, Cucumber parallel scenarios. Every thread needs its own browser page /
  WebDriver (e.g. a `ThreadLocal`); tests on different threads may share element keys.
- Several JVMs (Surefire `forkCount > 1`): `.healer` files are written under an OS file lock and merged, so
  no JVM overwrites another's fingerprints. Give every JVM of one build the same `healer.runId` and their
  results are merged into one report (each JVM writes `<reportDir>/parts/<runId>-<pid>.json`; the last one
  to finish writes the complete report):

```xml
<properties><maven.build.timestamp.format>yyyyMMdd-HHmmss</maven.build.timestamp.format></properties>
...
<systemPropertyVariables>
  <healer.runId>${maven.build.timestamp}</healer.runId>
</systemPropertyVariables>
```

## Testing the framework

`mvn install` runs unit tests (engine, matching, false-positive protection, privacy masking, file merging)
and browser integration tests on self-contained pages: renamed element, shadow DOM, iframe, removed
look-alike (must not heal), suggest mode, popups and the accuracy benchmark - for Playwright and for Selenium.
Browser: installed Edge by default; `-Dbrowser.channel=chromium -Dselenium.browser=chrome` for Chromium/Chrome (as CI does).

## Example project

`../shoplab-tests` (Playwright) and `../shoplab-selenium-tests` (Selenium) are sample test projects for the ShopLab demo
site. They use this framework only through the Maven dependencies above — like any other project would.

## Maven note

`.mvn/maven.config` resolves dependencies from Maven Central directly (a corporate mirror configured in
`~/.m2/settings.xml` may be reachable only on VPN). Delete it to use your global settings.

## Releasing a new version

See [RELEASING.md](RELEASING.md) - in short: `mvn versions:set -DnewVersion=2.0.1 -DgenerateBackupPoms=false`,
commit, `git tag v2.0.1 && git push origin main v2.0.1`, then press **Publish** in the Central Portal.

## License

Apache License 2.0 - see [LICENSE](LICENSE) and [NOTICE](NOTICE).
