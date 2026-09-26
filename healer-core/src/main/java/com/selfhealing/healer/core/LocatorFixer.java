package com.selfhealing.healer.core;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Turns heals into source-code fixes: finds where each broken selector is written (page objects, locator
 * files) and replaces it with the healed one - as a unified diff ({@code locator-fixes.patch}, for review or
 * {@code git apply}) or, with {@code healer.fix=apply}, directly in the files.
 *
 * <p>A selector is changed only when its location is unambiguous: the line that declared the locator, or the
 * only occurrence in the sources. Anything else is listed for manual review, never guessed.
 *
 * <p>Settings: {@code healer.fix} = patch (default) | apply | off; {@code healer.fix.sourceDirs} (comma separated,
 * relative to the project); {@code healer.fix.extensions}.
 */
public final class LocatorFixer {

    public static final String PATCH_FILE = "locator-fixes.patch";
    private static final String DEFAULT_DIRS = "src/test/java,src/main/java,src/test/kotlin,src/main/kotlin,"
            + "src/test/groovy,src/test/scala,src/test/resources,src/main/resources";
    private static final String DEFAULT_EXTENSIONS = "java,kt,groovy,scala,properties,json,yaml,yml";
    private static final Set<String> RAW_VALUE_FILES = Set.of("properties", "yaml", "yml");

    public enum Status {
        /** Location found; the patch changes it. */
        READY,
        /** Written to the source file (healer.fix=apply). */
        APPLIED,
        /** Not changed automatically: not found, found several times, or conflicting heals. */
        MANUAL
    }

    /**
     * One selector to change. {@code file} is relative to the project, {@code line} is 1-based (0 = unknown),
     * {@code note} explains a MANUAL fix: notFound | ambiguous:&lt;count&gt; | conflict | changed.
     */
    public record Fix(String key, String originalSelector, String healedSelector, String file, int line,
                      Status status, String note, List<String> tests) {
        /** The note in English, for the console ({@code note} itself is a code the report translates). */
        public String noteText() {
            if (note == null) return null;
            if (note.startsWith("ambiguous:")) return "written " + note.substring(10) + " times - change it by hand";
            return switch (note) {
                case "notFound" -> "selector not found in the sources";
                case "conflict" -> "different replacements in different tests";
                case "changed" -> "the file changed during the run";
                default -> note;
            };
        }

        Fix with(Status s) {
            return new Fix(key, originalSelector, healedSelector, file, line, s, note, tests);
        }
    }

    public record Plan(List<Fix> fixes, String patch) {
        public boolean hasChanges() {
            return fixes.stream().anyMatch(f -> f.status() != Status.MANUAL);
        }
    }

    private record Occurrence(Path file, int line, int start, int end, String form) {
    }

    private LocatorFixer() {
    }

    public static boolean enabled(HealerConfig config) {
        return !"off".equalsIgnoreCase(config.get("healer.fix", "patch"));
    }

    public static boolean applyMode(HealerConfig config) {
        return "apply".equalsIgnoreCase(config.get("healer.fix", "patch"));
    }

    /** Works out the fixes for the healed (or suggested) locators of a run. */
    public static Plan plan(List<HealingEvent> events, HealerConfig config, Path projectDir) {
        List<Path> sources = sourceFiles(config, projectDir);
        Map<Path, List<String>> contents = new LinkedHashMap<>();

        // One fix per broken selector, however many tests used it.
        Map<String, List<HealingEvent>> bySelector = events.stream()
                .filter(e -> e.kind == null)   // popups are not selector changes
                .filter(e -> (e.status == HealingEvent.Status.HEALED || e.status == HealingEvent.Status.SUGGESTED)
                        && e.healedSelector != null && e.originalSelector != null)
                .collect(Collectors.groupingBy(e -> e.originalSelector, LinkedHashMap::new, Collectors.toList()));
        // (Selenium: the literal in the source is the By value, e.g. "login-username" of By.id("login-username"))

        List<Fix> fixes = new ArrayList<>();
        Map<Path, List<Replacement>> edits = new LinkedHashMap<>();
        for (List<HealingEvent> group : bySelector.values()) {
            HealingEvent first = group.get(0);
            List<String> tests = group.stream().map(e -> e.test).filter(t -> t != null).distinct().toList();
            Set<String> choices = group.stream().map(e -> e.healedSelector).collect(Collectors.toCollection(TreeSet::new));
            if (choices.size() > 1) {
                fixes.add(new Fix(first.key, first.originalSelector, String.join(" | ", choices), first.sourceFile,
                        first.sourceLine, Status.MANUAL, "conflict", tests));
                continue;
            }
            List<Occurrence> found = new ArrayList<>();
            String written = first.sourceLiteral != null ? first.sourceLiteral : first.originalSelector;
            for (Path file : sources) found.addAll(find(file, lines(file, contents), written));
            Occurrence target = choose(found, first, projectDir);
            if (target == null) {
                String note = found.isEmpty() ? "notFound" : "ambiguous:" + found.size();
                fixes.add(new Fix(first.key, first.originalSelector, first.healedSelector,
                        found.isEmpty() ? projectPath(first.sourceFile, sources, projectDir) : relative(projectDir, found.get(0).file()),
                        found.isEmpty() ? first.sourceLine : found.get(0).line() + 1, Status.MANUAL, note, tests));
                continue;
            }
            String replacement = encode(first.healedSelector, target.form());
            edits.computeIfAbsent(target.file(), k -> new ArrayList<>())
                    .add(new Replacement(target.line(), target.start(), target.end(), replacement));
            fixes.add(new Fix(first.key, first.originalSelector, first.healedSelector, relative(projectDir, target.file()),
                    target.line() + 1, Status.READY, null, tests));
        }

        StringBuilder patch = new StringBuilder();
        for (Map.Entry<Path, List<Replacement>> e : edits.entrySet()) {
            List<String> before = contents.get(e.getKey());
            patch.append(unifiedDiff(relative(projectDir, e.getKey()), before, edited(before, e.getValue())));
        }
        return new Plan(fixes, patch.toString());
    }

    /** Writes the READY fixes into the source files; returns the plan with them marked APPLIED. */
    public static Plan apply(Plan plan, List<HealingEvent> events, HealerConfig config, Path projectDir) {
        // Re-read and re-plan against the current files so nothing is applied to stale content.
        Map<Path, List<Replacement>> edits = new LinkedHashMap<>();
        Map<Path, List<String>> contents = new LinkedHashMap<>();
        List<Fix> result = new ArrayList<>();
        for (Fix f : plan.fixes()) {
            if (f.status() != Status.READY) {
                result.add(f);
                continue;
            }
            Path file = projectDir.resolve(f.file());
            String written = events.stream().filter(e -> f.originalSelector().equals(e.originalSelector) && e.sourceLiteral != null)
                    .map(e -> e.sourceLiteral).findFirst().orElse(f.originalSelector());
            List<Occurrence> at = find(file, lines(file, contents), written).stream()
                    .filter(o -> o.line() + 1 == f.line()).toList();
            if (at.size() != 1) {
                result.add(new Fix(f.key(), f.originalSelector(), f.healedSelector(), f.file(), f.line(), Status.MANUAL,
                        "changed", f.tests()));
                continue;
            }
            Occurrence o = at.get(0);
            edits.computeIfAbsent(file, k -> new ArrayList<>())
                    .add(new Replacement(o.line(), o.start(), o.end(), encode(f.healedSelector(), o.form())));
            result.add(f.with(Status.APPLIED));
        }
        for (Map.Entry<Path, List<Replacement>> e : edits.entrySet()) {
            try {
                Files.writeString(e.getKey(), String.join("\n", edited(contents.get(e.getKey()), e.getValue())),
                        StandardCharsets.UTF_8);
            } catch (IOException ex) {
                throw new UncheckedIOException("Cannot update " + e.getKey(), ex);
            }
        }
        return new Plan(result, plan.patch());
    }

    public static Path writePatch(Path reportDir, Plan plan) {
        return writePatch(reportDir, plan, PATCH_FILE);
    }

    public static Path writePatch(Path reportDir, Plan plan, String fileName) {
        try {
            Files.createDirectories(reportDir);
            Path file = reportDir.resolve(fileName);
            if (plan.patch().isEmpty()) {
                Files.deleteIfExists(file);
                return null;
            }
            Files.writeString(file, plan.patch(), StandardCharsets.UTF_8);
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write " + fileName, e);
        }
    }

    // ---- locating -------------------------------------------------------------------------------

    private static List<Path> sourceFiles(HealerConfig config, Path projectDir) {
        Set<String> extensions = Arrays.stream(config.get("healer.fix.extensions", DEFAULT_EXTENSIONS).split(","))
                .map(s -> s.trim().toLowerCase(Locale.ROOT)).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
        List<Path> out = new ArrayList<>();
        for (String dir : config.get("healer.fix.sourceDirs", DEFAULT_DIRS).split(",")) {
            Path root = projectDir.resolve(dir.trim());
            if (dir.isBlank() || !Files.isDirectory(root)) continue;
            try (Stream<Path> walk = Files.walk(root)) {
                walk.filter(Files::isRegularFile).filter(p -> extensions.contains(extension(p))).sorted().forEach(out::add);
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot scan " + root, e);
            }
        }
        return out;
    }

    /** Every place the selector is written as a string literal (or as a properties/YAML value). */
    static List<Occurrence> find(Path file, List<String> lines, String selector) {
        List<Occurrence> out = new ArrayList<>();
        List<String> forms = new ArrayList<>(List.of("double", "single"));
        if (RAW_VALUE_FILES.contains(extension(file))) forms.add("raw");
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            for (String form : forms) {
                if (form.equals("raw")) {
                    Matcher m = Pattern.compile("^\\s*[^#!\\s][^=:]*[=:]\\s*[\"']?(" + Pattern.quote(selector) + ")[\"']?\\s*\\r?$")
                            .matcher(line);
                    if (m.find()) out.add(new Occurrence(file, i, m.start(1), m.end(1), form));
                    continue;
                }
                String literal = literal(selector, form);
                for (int at = line.indexOf(literal); at >= 0; at = line.indexOf(literal, at + 1)) {
                    out.add(seleniumCall(file, i, line, at, at + literal.length(), form));
                }
            }
        }
        return out;
    }

    private static final Pattern BY_CALL = Pattern.compile("By\\.(id|name|className|tagName|xpath|linkText|partialLinkText|cssSelector)\\(\\s*$");
    private static final Pattern FIND_BY_ATTR = Pattern.compile("\\b(id|name|className|tagName|xpath|linkText|partialLinkText|css)\\s*=\\s*$");

    /**
     * A literal inside Selenium's By.id("x") or @FindBy(id = "x"): the occurrence then covers the whole call or attribute,
     * so the fix can switch it to CSS (By.cssSelector / css =), since healed selectors are CSS.
     */
    private static Occurrence seleniumCall(Path file, int line, String text, int start, int end, String form) {
        if (!form.equals("double")) return new Occurrence(file, line, start, end, form);
        String before = text.substring(0, start);
        String after = text.substring(end);
        Matcher by = BY_CALL.matcher(before);
        Matcher close = Pattern.compile("^\\s*\\)").matcher(after);
        if (by.find() && close.find() && !by.group(1).equals("cssSelector")) {
            return new Occurrence(file, line, by.start(), end + close.end(), "by");
        }
        Matcher attr = FIND_BY_ATTR.matcher(before);
        if (attr.find() && before.contains("@FindBy") && !attr.group(1).equals("css")) {
            return new Occurrence(file, line, attr.start(), end, "findBy");
        }
        return new Occurrence(file, line, start, end, form);
    }

    /** The declaring line if the selector is there, else the only occurrence in that file, else the only one at all. */
    private static Occurrence choose(List<Occurrence> found, HealingEvent e, Path projectDir) {
        if (found.isEmpty()) return null;
        if (e.sourceFile != null) {
            String declared = e.sourceFile.replace('\\', '/');
            List<Occurrence> inFile = found.stream()
                    .filter(o -> relative(projectDir, o.file()).endsWith("/" + declared) || relative(projectDir, o.file()).equals(declared))
                    .toList();
            List<Occurrence> onLine = inFile.stream().filter(o -> o.line() + 1 == e.sourceLine).toList();
            if (onLine.size() == 1) return onLine.get(0);
            if (inFile.size() == 1) return inFile.get(0);
            if (inFile.size() > 1) return null;
        }
        return found.size() == 1 ? found.get(0) : null;
    }

    /** A source-root relative path (com/acme/LoginPage.java) as a project path, for display. */
    public static String projectPath(String sourceFile, HealerConfig config, Path projectDir) {
        return projectPath(sourceFile, sourceFiles(config, projectDir), projectDir);
    }

    /** com/acme/LoginPage.java -> src/test/java/com/acme/LoginPage.java when that file is among the sources. */
    private static String projectPath(String sourceFile, List<Path> sources, Path projectDir) {
        if (sourceFile == null) return null;
        String declared = "/" + sourceFile.replace('\\', '/');
        return sources.stream().map(p -> relative(projectDir, p)).filter(r -> ("/" + r).endsWith(declared))
                .findFirst().orElse(sourceFile);
    }

    // ---- editing --------------------------------------------------------------------------------

    private record Replacement(int line, int start, int end, String text) {
    }

    private static List<String> edited(List<String> lines, List<Replacement> replacements) {
        List<String> out = new ArrayList<>(lines);
        Map<Integer, List<Replacement>> byLine = new TreeMap<>();
        replacements.forEach(r -> byLine.computeIfAbsent(r.line(), k -> new ArrayList<>()).add(r));
        byLine.forEach((line, rs) -> {
            StringBuilder sb = new StringBuilder(out.get(line));
            rs.stream().sorted(Comparator.comparingInt(Replacement::start).reversed())
                    .forEach(r -> sb.replace(r.start(), r.end(), r.text()));
            out.set(line, sb.toString());
        });
        return out;
    }

    /** The healed selector in the same kind of literal; quotes inside are swapped where that avoids escaping. */
    private static String encode(String selector, String form) {
        String s = selector;
        if (form.equals("double") && s.contains("\"") && !s.contains("'")) s = s.replace('"', '\'');
        if (form.equals("single") && s.contains("'") && !s.contains("\"")) s = s.replace('\'', '"');
        if (form.equals("by")) return "By.cssSelector(" + encode(selector, "double") + ")";
        if (form.equals("findBy")) return "css = " + encode(selector, "double");
        return form.equals("raw") ? s : literal(s, form);
    }

    /** A string literal: "..." with Java/JSON escaping, or '...'. */
    private static String literal(String s, String form) {
        String escaped = s.replace("\\", "\\\\");
        return form.equals("double")
                ? "\"" + escaped.replace("\"", "\\\"") + "\""
                : "'" + escaped.replace("'", "\\'") + "'";
    }

    // ---- diff -----------------------------------------------------------------------------------

    /** Unified diff for same-length edits (each line replaced in place), 3 lines of context, git-apply compatible. */
    static String unifiedDiff(String path, List<String> before, List<String> after) {
        List<Integer> changed = new ArrayList<>();
        for (int i = 0; i < before.size(); i++) if (!before.get(i).equals(after.get(i))) changed.add(i);
        if (changed.isEmpty()) return "";
        // The file's last element is "" when it ends with a newline (split keeps it); it is not a real line.
        int realLines = before.get(before.size() - 1).isEmpty() ? before.size() - 1 : before.size();
        boolean noFinalNewline = realLines == before.size();

        StringBuilder sb = new StringBuilder("--- a/").append(path).append('\n').append("+++ b/").append(path).append('\n');
        int i = 0;
        while (i < changed.size()) {
            int from = Math.max(0, changed.get(i) - 3);
            int j = i;
            while (j + 1 < changed.size() && changed.get(j + 1) - changed.get(j) <= 6) j++;
            int to = Math.min(realLines - 1, changed.get(j) + 3);
            int len = to - from + 1;
            sb.append("@@ -").append(from + 1).append(',').append(len).append(" +").append(from + 1).append(',')
              .append(len).append(" @@\n");
            StringBuilder removed = new StringBuilder();
            StringBuilder added = new StringBuilder();
            for (int k = from; k <= to; k++) {
                boolean last = noFinalNewline && k == realLines - 1;
                String marker = last ? "\n\\ No newline at end of file\n" : "\n";
                if (before.get(k).equals(after.get(k))) {
                    sb.append(removed).append(added);
                    removed.setLength(0);
                    added.setLength(0);
                    sb.append(' ').append(before.get(k)).append(marker);
                } else {
                    removed.append('-').append(before.get(k)).append(marker);
                    added.append('+').append(after.get(k)).append(marker);
                }
            }
            sb.append(removed).append(added);
            i = j + 1;
        }
        return sb.toString();
    }

    // ---- helpers --------------------------------------------------------------------------------

    /** Lines split on \n only, so a CRLF file keeps its \r and is written back byte for byte. */
    private static List<String> lines(Path file, Map<Path, List<String>> cache) {
        return cache.computeIfAbsent(file, f -> {
            try {
                return Arrays.asList(Files.readString(f, StandardCharsets.UTF_8).split("\n", -1));
            } catch (IOException | UncheckedIOException e) {
                return List.of();   // unreadable or not UTF-8: skip
            }
        });
    }

    private static String relative(Path projectDir, Path file) {
        return projectDir.toAbsolutePath().normalize().relativize(file.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    private static String extension(Path p) {
        String n = p.getFileName().toString();
        int dot = n.lastIndexOf('.');
        return dot < 0 ? "" : n.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
