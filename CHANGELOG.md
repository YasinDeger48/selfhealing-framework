# Changelog

All notable changes. Versions follow [semantic versioning](https://semver.org); coordinates
`io.github.yasindeger48:healer-*`.

## [2.2.1] - unreleased (on GitHub, not yet on Maven Central)

### Added
- **Selenium overlay:** `healer.visual=true` now draws the healing steps on the page with Selenium too (it was
  Playwright only).
- **Better cold starts** (no recorded fingerprint - e.g. after deleting `.healer/`): the step's action tells the kind
  of element (`fill` needs a field, `select` a select, `check` a checkbox), and so does the old selector's own name
  (`login-submit-button`, `email-input`); a div or a paragraph is never taken for them. The old name is also looked
  for in all of a candidate's names, its label and a button's text (`#login-username` -> `username-input`,
  `login-submit-button` -> "Log In").

### Fixed
- Video and trace of failed tests (`browser.video/trace=failures`) were never kept with **TestNG** (its @AfterMethod
  runs after the test ended) and **JUnit 4** (its @After runs before the failure is known). The outcome is now taken
  from the test that just ran on the thread, and when it is not known yet the files are kept until it is.
- `browser.video` without Playwright's ffmpeg: older Playwright versions (1.45) only fail when the first page opens,
  and the fallback itself was rejected ("recordVideoSize is set but recordVideoDir is null") - every test failed. The
  missing ffmpeg is now detected up front and the run continues without video.
- A test failed with "Cannot write .healer/healed-locators.json" (AccessDeniedException) when a virus scanner or
  OneDrive held the file for a moment on Windows. The write is retried; if the file stays locked the change is kept
  in memory and saved with the next one.
- `HealerBrowser.close(null)` (when `newContext` had failed) threw a NullPointerException that hid the real error.
- `.healer/fingerprints.json` was still rewritten on every run with only new timestamps: an empty label or text is not
  stored in the file and came back as `null`, which did not equal the browser's `""`. Unchanged elements are now
  recognised after reading the file, and position, size and numbers in the text (counters, order numbers) no longer
  count as a change - only the element's identity and text do.
- A heal refused by a guard read like a threshold problem ("best match input#mail scored 0.82 (min 0.60) ...").
  The message now says why: the element belongs to another locator, or it is a different kind of element.

## [2.2.0]

### Added
- **Layered settings:** `healer.properties` < `healer-<profile>.properties` (`-Dhealer.profile=ci`) <
  `healer-local.properties` (personal, not committed) < environment variables (`HEALER_*`, `BROWSER_*`, `APP_*`) < `-D`.
  References `${env:NAME:-default}` / `${sys:name}`, trailing `# comments`, a warning for misspelled settings.
- `healer.enabled=false` switches the healer off completely.
- **Report:** `healer.report.open` (never / always / onFailure / onWarn - never on CI), `healer.report.pdf=auto`
  (not on CI), `healer.report.screenshots` (all / failures / none), `healer.report.history` + `historyKeep`.
- **Browser from the settings:** `HealerBrowser` (Playwright) and `HealerDriver` (Selenium) - `browser.name`,
  `headless`, `slowmo`, `viewport`, `timeoutMs`, `app.baseUrl`; Playwright video and trace of failed tests
  (`browser.video`, `browser.trace`), linked in the report.
- **Claude:** `healer.llm.apiKey` (default `ANTHROPIC_API_KEY`), `healer.llm.maxCostPerRun`,
  `healer.llm.price.<model>=input,output`.
- CI on Java 17, 21 and 25, on Linux and Windows, and with a Gradle project; parallel runs and older
  Playwright/Selenium versions in the compatibility matrix.
- GitHub Releases with the notes of this file.

### Changed
- An unchanged element no longer rewrites `.healer/fingerprints.json` (a new timestamp on every run caused noisy diffs
  and merge conflicts).
- `com.selfhealing.healer.playwright.HealingExtension` and `...selenium.SeleniumHealingExtension` are deprecated in
  favour of `healer-junit5` (`com.selfhealing.healer.junit5.HealingExtension`); they keep working.

### Fixed
- Video recording with an installed browser (no Playwright ffmpeg) failed the test; the run now continues without
  video and says how to install ffmpeg.

## [2.1.0]

### Added
- `healer-junit4`: `@Rule public HealingRule healing = new HealingRule();`
- `healer-junit5`: one JUnit 5 extension for either adapter, also without annotation (extension auto-detection).
- `[healer] N test(s) FAILED` at the end of every run.

### Fixed
- Cucumber on TestNG listed every scenario twice.
- TestNG on Maven Surefire 3.6: the report was written after test discovery and not updated after the run.
- Documented: Surefire 3.6.0+ is needed - with 3.5.x a failing Cucumber scenario on the JUnit Platform does not fail
  the build.

## [2.0.1]

### Fixed
- TestNG projects ran no test at all (the adapters brought the JUnit API, so Surefire chose JUnit): the JUnit API is
  now optional.
- Typed values of fields such as `passengerSurname` were masked as secrets: secret names match whole words only.
- Broken test setups (Cucumber glue, missing constructor ...) are analysed as TEST_CODE instead of UNKNOWN.

## [2.0.0]

First release on Maven Central: Playwright and Selenium adapters, local heuristic and Claude healing, cold start,
code fixes, popups, locator quality, failure analysis, plain-language steps, TestNG and Cucumber integrations,
accuracy benchmark, reports in six languages.

[2.2.1]: https://github.com/YasinDeger48/selfhealing-framework/releases/tag/v2.2.1
[2.2.0]: https://github.com/YasinDeger48/selfhealing-framework/releases/tag/v2.2.0
[2.1.0]: https://github.com/YasinDeger48/selfhealing-framework/releases/tag/v2.1.0
[2.0.1]: https://github.com/YasinDeger48/selfhealing-framework/releases/tag/v2.0.1
[2.0.0]: https://github.com/YasinDeger48/selfhealing-framework/releases/tag/v2.0.0
