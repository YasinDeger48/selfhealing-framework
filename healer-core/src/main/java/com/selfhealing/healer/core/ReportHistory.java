package com.selfhealing.healer.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * {@code healer.report.history=true}: after each run the report (HTML, JSON, PDF, patches, videos, traces) is copied to
 * {@code <reportDir>/history/<yyyy-MM-dd_HH-mm-ss>/}; only the newest {@code healer.report.historyKeep} (default 10) stay.
 */
public final class ReportHistory {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    private static final List<String> FILES = List.of(ReportWriter.HTML_FILE, ReportWriter.JSON_FILE, "healing-report.pdf",
            LocatorFixer.PATCH_FILE, "locator-improvements.patch");
    private static final List<String> FOLDERS = List.of("videos", "traces");

    private ReportHistory() {
    }

    /** Copies the current report; returns the history folder, or null when history is off or nothing was copied. */
    public static Path keep(HealerConfig config) {
        if (!config.getBoolean("healer.report.history", false)) return null;
        Path reportDir = config.reportDir();
        Path history = reportDir.resolve("history");
        Path target = history.resolve(LocalDateTime.now().format(STAMP));
        try {
            Files.createDirectories(target);
            for (String f : FILES) {
                Path src = reportDir.resolve(f);
                if (Files.exists(src)) Files.copy(src, target.resolve(f), StandardCopyOption.REPLACE_EXISTING);
            }
            for (String folder : FOLDERS) copyTree(reportDir.resolve(folder), target.resolve(folder));
            prune(history, (int) config.getLong("healer.report.historyKeep", 10));
            System.out.println("[healer] report kept in " + target.toAbsolutePath());
            return target;
        } catch (IOException e) {
            System.out.println("[healer] report history skipped: " + e.getMessage());
            return null;
        }
    }

    private static void copyTree(Path from, Path to) throws IOException {
        if (!Files.isDirectory(from)) return;
        try (Stream<Path> walk = Files.walk(from)) {
            for (Path p : walk.toList()) {
                Path dest = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) Files.createDirectories(dest);
                else Files.copy(p, dest, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    /** Deletes the oldest runs so that at most {@code keep} remain (folder names sort by time). */
    static void prune(Path history, int keep) throws IOException {
        if (keep < 1) return;
        List<Path> runs;
        try (Stream<Path> list = Files.list(history)) {
            runs = list.filter(Files::isDirectory).sorted(Comparator.comparing(p -> p.getFileName().toString())).toList();
        }
        for (int i = 0; i < runs.size() - keep; i++) {
            try (Stream<Path> walk = Files.walk(runs.get(i))) {
                for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
            }
        }
    }
}
