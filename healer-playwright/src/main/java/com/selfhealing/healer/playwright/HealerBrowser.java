package com.selfhealing.healer.playwright;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Tracing;
import com.microsoft.playwright.Video;
import com.selfhealing.healer.core.HealerConfig;
import com.selfhealing.healer.core.HealingRecorder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Browser setup from the settings, so test projects do not hard-code it (healer.properties, profiles, environment,
 * -D - see {@link HealerConfig}):
 * <pre>
 * browser.name=msedge          # chromium | msedge | chrome | firefox | webkit
 * browser.headless=true
 * browser.slowmo=0             # ms between actions
 * browser.viewport=1280x900
 * browser.timeoutMs=15000      # default timeout of actions
 * browser.video=failures       # off | failures | all
 * browser.trace=failures       # off | failures | all  (open with: npx playwright show-trace file.zip)
 * app.baseUrl=https://my.app
 * </pre>
 * <pre>{@code
 * Browser browser = HealerBrowser.launch(playwright);
 * BrowserContext context = HealerBrowser.newContext(browser);   // in @BeforeEach
 * ...
 * HealerBrowser.close(context);                                  // in @AfterEach: keeps video/trace per the settings
 * page.navigate(HealerBrowser.url("/login"));
 * }</pre>
 */
public final class HealerBrowser {

    private record Recording(boolean video, boolean trace) {
    }

    private static final Map<BrowserContext, Recording> RECORDING = new ConcurrentHashMap<>();
    /** Set when video recording failed because Playwright's ffmpeg is missing: not tried again in this run. */
    private static volatile boolean videoUnavailable;
    /** Set once a page with video recording opened: ffmpeg is there. */
    private static volatile boolean videoVerified;

    private HealerBrowser() {
    }

    private static HealerConfig config() {
        return SelfHealingPage.engine().config();
    }

    public static Browser launch(Playwright playwright) {
        HealerConfig c = config();
        String name = c.get("browser.name", "msedge").toLowerCase(Locale.ROOT);
        BrowserType.LaunchOptions o = new BrowserType.LaunchOptions()
                .setHeadless(c.getBoolean("browser.headless", true))
                .setSlowMo(c.getLong("browser.slowmo", 0));
        return switch (name) {
            case "firefox" -> playwright.firefox().launch(o);
            case "webkit", "safari" -> playwright.webkit().launch(o);
            case "chromium" -> playwright.chromium().launch(o);
            default -> playwright.chromium().launch(o.setChannel(name));   // msedge, chrome, chrome-beta ...
        };
    }

    public static BrowserContext newContext(Browser browser) {
        HealerConfig c = config();
        int[] size = viewport(c.get("browser.viewport", "1280x900"));
        boolean video = !"off".equalsIgnoreCase(c.get("browser.video", "off"));
        boolean trace = !"off".equalsIgnoreCase(c.get("browser.trace", "off"));
        Browser.NewContextOptions o = new Browser.NewContextOptions().setViewportSize(size[0], size[1]);
        if (video && !videoUnavailable) o.setRecordVideoDir(c.reportDir().resolve("videos").resolve(".recording")).setRecordVideoSize(size[0], size[1]);
        BrowserContext context = null;
        try {
            context = browser.newContext(o);
            if (video && !videoVerified) {
                // Some Playwright versions only look for ffmpeg when the first page opens - try one before the test does.
                Page probe = context.newPage();
                Video recording = probe.video();
                probe.close();
                if (recording != null) recording.delete();
                videoVerified = true;
            }
        } catch (com.microsoft.playwright.PlaywrightException e) {
            if (!video || e.getMessage() == null || !e.getMessage().contains("ffmpeg")) throw e;
            // Video needs Playwright's ffmpeg, which is not downloaded when tests use an installed browser.
            videoUnavailable = true;
            // The failed context is left open (closed with the browser): closing it drops the whole connection in
            // older Playwright versions (1.45).
            System.out.println("[healer] browser.video needs Playwright's ffmpeg - recording without video. Install it once:"
                    + " mvn exec:java -e -Dexec.mainClass=com.microsoft.playwright.CLI -Dexec.args=\"install ffmpeg\"");
            context = browser.newContext(o.setRecordVideoDir(null).setRecordVideoSize(null));
            video = false;
        }
        if (videoUnavailable) video = false;
        context.setDefaultTimeout(c.getLong("browser.timeoutMs", 15_000));
        if (trace) context.tracing().start(new Tracing.StartOptions().setScreenshots(true).setSnapshots(true));
        RECORDING.put(context, new Recording(video, trace));
        return context;
    }

    /** Closes the context; keeps the video and trace if browser.video / browser.trace say so for this test's result. */
    public static void close(BrowserContext context) {
        if (context == null) return;   // newContext failed - nothing to close
        Recording r = RECORDING.remove(context);
        HealerConfig c = config();
        String test = HealingRecorder.currentOrLastTest();
        String outcome = HealingRecorder.outcome(test);
        boolean failed = "FAILED".equals(outcome);
        // JUnit 4: @After runs before the failure is known - keep the files for now, drop them if the test passes.
        boolean pending = outcome == null;
        Map<String, Path> provisional = new java.util.LinkedHashMap<>();   // kind -> file
        String name = safe(test);
        String traceMode = c.get("browser.trace", "off");
        if (r != null && r.trace()) {
            if (keep(traceMode, failed) || (pending && "failures".equalsIgnoreCase(traceMode))) {
                Path file = c.reportDir().resolve("traces").resolve(name + ".zip");
                context.tracing().stop(new Tracing.StopOptions().setPath(file));
                HealingRecorder.attach(test, "trace", "traces/" + file.getFileName());
                if (!keep(traceMode, failed)) provisional.put("trace", file);
            } else {
                context.tracing().stop();
            }
        }
        List<Video> videos = r != null && r.video() ? context.pages().stream().map(Page::video).filter(Objects::nonNull).toList() : List.of();
        context.close();
        String videoMode = c.get("browser.video", "off");
        boolean videoOnlyIfFailed = !keep(videoMode, failed) && pending && "failures".equalsIgnoreCase(videoMode);
        boolean keepVideo = r != null && r.video() && (keep(videoMode, failed) || videoOnlyIfFailed);
        int n = 0;
        for (Video v : videos) {
            try {
                Path recorded = v.path();
                if (keepVideo) {
                    String file = name + (n++ == 0 ? "" : "-" + n) + ".webm";
                    Path target = c.reportDir().resolve("videos").resolve(file);
                    Files.createDirectories(target.getParent());
                    Files.move(recorded, target, StandardCopyOption.REPLACE_EXISTING);
                    if (n == 1) HealingRecorder.attach(test, "video", "videos/" + file);
                    if (videoOnlyIfFailed) provisional.put("video", target);
                } else {
                    Files.deleteIfExists(recorded);
                }
            } catch (IOException | RuntimeException e) {
                System.out.println("[healer] video not kept: " + e.getMessage());
            }
        }
        if (!provisional.isEmpty()) {
            HealingRecorder.whenFinished(test, result -> {
                if ("FAILED".equals(result)) return;
                provisional.forEach((kind, p) -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                        // best effort
                    }
                    HealingRecorder.attach(test, kind, null);
                });
            });
        }
        try {   // the recording folder is only a work area
            Path recording = c.reportDir().resolve("videos").resolve(".recording");
            if (Files.isDirectory(recording)) {
                try (var left = Files.list(recording)) {
                    if (left.findAny().isEmpty()) Files.delete(recording);
                }
                Path videosDir = recording.getParent();
                try (var left = Files.list(videosDir)) {
                    if (left.findAny().isEmpty()) Files.delete(videosDir);
                }
            }
        } catch (IOException ignored) {
            // another context may still be recording
        }
    }

    /** app.baseUrl */
    public static String baseUrl() {
        return config().get("app.baseUrl", "");
    }

    /** app.baseUrl + path, with exactly one slash between them. */
    public static String url(String path) {
        String base = baseUrl();
        if (base.endsWith("/") && path.startsWith("/")) return base + path.substring(1);
        if (!base.endsWith("/") && !path.startsWith("/") && !base.isEmpty()) return base + "/" + path;
        return base + path;
    }

    static boolean keep(String mode, boolean failed) {
        return "all".equalsIgnoreCase(mode) || (failed && !"off".equalsIgnoreCase(mode));
    }

    static int[] viewport(String value) {
        try {
            String[] p = value.toLowerCase(Locale.ROOT).split("x");
            return new int[] {Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim())};
        } catch (RuntimeException e) {
            return new int[] {1280, 900};
        }
    }

    private static String safe(String test) {
        return test.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
