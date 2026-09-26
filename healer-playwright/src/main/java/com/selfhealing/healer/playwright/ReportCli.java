package com.selfhealing.healer.playwright;

import com.selfhealing.healer.core.Json;
import com.selfhealing.healer.core.Messages;
import com.selfhealing.healer.core.ReportWriter;

import java.nio.file.Path;
import java.util.Map;

/**
 * Rebuilds the HTML and PDF reports from an existing {@code healing-report.json} without
 * re-running the tests: {@code java ... ReportCli [reportDir] [pdfBrowser]}.
 */
public final class ReportCli {

    private ReportCli() {
    }

    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args.length > 0 ? args[0] : "target/healer-report");
        String browser = args.length > 1 ? args[1] : "msedge";
        Map<String, Object> model = Json.MAPPER.readValue(dir.resolve(ReportWriter.JSON_FILE).toFile(), Map.class);
        Object settings = model.get("settings");
        if (settings instanceof Map<?, ?> s && s.get("language") != null) Messages.use(s.get("language").toString());
        Path html = ReportWriter.writeHtml(dir, model);
        System.out.println("HTML: " + html.toAbsolutePath());
        PdfExporter.export(html, dir.resolve("healing-report.pdf"), browser)
                .ifPresent(pdf -> System.out.println("PDF:  " + pdf.toAbsolutePath()));
    }
}
