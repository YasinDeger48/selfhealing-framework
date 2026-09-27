package com.selfhealing.healer.core;

import java.awt.Desktop;
import java.awt.GraphicsEnvironment;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Opens the HTML report after the run ({@code healer.report.open}): never (default) | always | onFailure (a test failed)
 * | onWarn (a test failed, or a locator was healed / a popup closed). Never on a CI server or a headless machine.
 */
public final class ReportOpener {

    private ReportOpener() {
    }

    public static void openIfWanted(Path html, HealerConfig config, boolean failures, boolean warnings) {
        if (!wanted(config.get("healer.report.open", "never"), failures, warnings)) return;
        if (config.ci()) {
            System.out.println("[healer] healer.report.open is ignored on CI");
            return;
        }
        open(html);
    }

    static boolean wanted(String mode, boolean failures, boolean warnings) {
        return switch (mode.trim().toLowerCase(Locale.ROOT)) {
            case "always", "true" -> true;
            case "onfailure", "failure", "failures" -> failures;
            case "onwarn", "warn", "warnings" -> failures || warnings;
            default -> false;
        };
    }

    private static void open(Path html) {
        try {
            if (!GraphicsEnvironment.isHeadless() && Desktop.isDesktopSupported()
                    && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(html.toAbsolutePath().toUri());
                return;
            }
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            String file = html.toAbsolutePath().toString();
            ProcessBuilder pb = os.contains("win") ? new ProcessBuilder("cmd", "/c", "start", "\"\"", file)
                    : os.contains("mac") ? new ProcessBuilder("open", file)
                    : new ProcessBuilder("xdg-open", file);
            pb.inheritIO().start();
        } catch (Exception e) {
            System.out.println("[healer] could not open the report: " + e.getMessage());
        }
    }
}
