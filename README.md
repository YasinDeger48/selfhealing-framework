# Self-Healing Locator Framework

[![CI](https://github.com/YasinDeger48/selfhealing-framework/actions/workflows/ci.yml/badge.svg)](https://github.com/YasinDeger48/selfhealing-framework/actions/workflows/ci.yml)

A Java library for **Playwright + JUnit 5** test suites. When a locator stops matching because the
application's markup changed, it finds the element again, keeps the test running and reports every
repair as a **WARN** with the suggested page-object fix. Works with any web application and any test
project — add it as a dependency, wrap your locators, done.

## Modules

| Artifact | What it is |
|---|---|
| `com.selfhealing:healer-core` | Driver-independent engine: element fingerprints, local matching, healing cache, events, reports |
| `com.selfhealing:healer-playwright` | Playwright adapter (`SelfHealingPage`, `HealingLocator`), JUnit 5 extension, HTML/PDF report |
| `com.selfhealing:healer-claude` | Optional last healing stage backed by the Claude API — enabled by adding it to the classpath |

Requirements: Java 17+, Playwright for Java, JUnit 5.

## Getting started in your project

**1. Build and install the framework** (until it is published to a Maven repository):

```bash
cd healing-framework
mvn install
```

**2. Add the dependencies** to your test project:

```xml
<dependency>
  <groupId>com.selfhealing</groupId>
  <artifactId>healer-playwright</artifactId>
  <version>2.0.0</version>
  <scope>test</scope>
</dependency>
<!-- optional: Claude stage -->
<dependency>
  <groupId>com.selfhealing</groupId>
  <artifactId>healer-claude</artifactId>
  <version>2.0.0</version>
  <scope>test</scope>
</dependency>
```

**3. Wrap your Playwright page and give each element a stable name:**

```java
@ExtendWith(HealingExtension.class)
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

**4. Run the suite once against a working build** — this records the fingerprints (`.healer/`).
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

## Configuration — `healer.properties` on the test classpath (or `-D<name>=<value>`)

| Setting | Default | Meaning |
|---|---|---|
| `healer.language` | `en` | Language of console trace, demo overlay and the report's default: `en`, `de`, `ru`, `ja`, `tr`, `ar` |
| `healer.mode` | `auto` | `off` / `suggest` (report the replacement, fail the step - for teams that want no automatic heals) / `auto` (heal, continue, WARN) |
| `healer.runId` | (empty) | Same value for all JVMs of one build → one merged report (forkCount > 1) |
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
| `healer.screenshots` | `true` | Screenshot of each healed element for the report |
| `healer.report.pdf` | `true` | Also write the PDF report |
| `healer.report.pdfBrowser` | `msedge` | Browser for PDF printing: `msedge`, `chrome`, `chromium` |
| `healer.llm.enabled` | `false` | Enable the Claude stage (needs `healer-claude` + `ANTHROPIC_API_KEY`) |
| `healer.llm.model` | `claude-haiku-4-5` | First model asked |
| `healer.llm.escalateTo` | `claude-opus-5` | Asked when the first model is not confident (empty = off) |
| `healer.llm.escalateOnNoMatch` | `true` | Also escalate when the first model answers "no match"; `false` keeps the cost of genuinely removed elements at one cheap call |
| `healer.llm.logPrompts` | `false` | Save every Claude request (after masking) for audit |
| `healer.privacy.mask` | `true` | Mask personal/secret data before it is sent to Claude |
| `healer.privacy.patterns` | (empty) | Extra masking rules: `NAME=regex;NAME2=regex` |
| `healer.llm.effort` | `low` | `low` … `max` (ignored by Haiku) |
| `healer.llm.language` | from `healer.language` | Language of Claude's reasoning |
| `healer.llm.candidates` | `10` | Candidates sent to Claude — fewer is cheaper |
| `healer.llm.maxCallsPerRun` | `50` | Claude call budget per run |
| `healer.llm.timeoutSeconds` | `90` | Per-call timeout; on error the step simply fails |

## Parallel runs

- Parallel JUnit threads in one JVM are supported (per-thread test context, synchronized stores).
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
look-alike (must not heal) and suggest mode. Browser: installed Edge by default, `-Dbrowser.channel=chromium`
for Playwright's bundled Chromium (e.g. on Linux CI, after `mvn exec:java -e -D exec.mainClass=com.microsoft.playwright.CLI -D exec.args="install chromium"`).

## Example project

`../shoplab-tests` is a sample test project for the ShopLab demo site. It uses this framework only
through the Maven dependencies above — like any other project would.

## Maven note

`.mvn/maven.config` resolves dependencies from Maven Central directly (a corporate mirror configured in
`~/.m2/settings.xml` may be reachable only on VPN). Delete it to use your global settings.
