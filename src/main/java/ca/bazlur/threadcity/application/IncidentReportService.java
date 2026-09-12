package ca.bazlur.threadcity.application;

import ca.bazlur.threadcity.domain.AnalysisResult;
import ca.bazlur.threadcity.domain.BlockingImpact;
import ca.bazlur.threadcity.domain.IncidentPattern;
import ca.bazlur.threadcity.domain.JfrAnalysis;
import ca.bazlur.threadcity.domain.JfrEventSummary;
import ca.bazlur.threadcity.domain.SynchronizerInsight;
import ca.bazlur.threadcity.domain.ThreadState;
import ca.bazlur.threadcity.domain.WaitEdge;
import ca.bazlur.threadcity.ui.support.IncidentNarrative;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;

/**
 * Produces a portable, script-free incident dossier from bounded analysis results.
 */
@Service
public final class IncidentReportService {

    public byte[] create(AnalysisResult result, Optional<JfrAnalysis> jfr) {
        StringBuilder html = new StringBuilder(24_000);
        html.append("""
                <!doctype html><html lang="en"><head><meta charset="utf-8">
                <meta name="viewport" content="width=device-width,initial-scale=1">
                <meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'">
                <title>ThreadCity incident dossier</title><style>
                :root{color-scheme:dark;--ink:#eaf2fb;--muted:#91a0b2;--line:#263345;--red:#ff5d73;--amber:#ffc857;--green:#56e39f;--blue:#72a7ff}
                *{box-sizing:border-box}body{margin:0;background:#070b12;color:var(--ink);font:14px/1.55 Inter,system-ui,sans-serif}
                main{max-width:1120px;margin:auto;padding:48px 28px 80px}header{padding:30px;border:1px solid var(--line);border-radius:20px;background:linear-gradient(135deg,#111a28,#0a1019)}
                .eyebrow,.badge{font-size:11px;font-weight:900;letter-spacing:.08em;text-transform:uppercase}.eyebrow{color:var(--green)}h1{margin:.3rem 0;font-size:42px}h2{margin:2rem 0 .7rem}h3{margin:.4rem 0}.muted{color:var(--muted)}
                .metrics,.cards{display:grid;grid-template-columns:repeat(auto-fit,minmax(180px,1fr));gap:12px}.metric,.card{padding:16px;border:1px solid var(--line);border-radius:12px;background:#0c121c}.metric strong{display:block;font-size:26px;color:var(--green)}
                .critical{border-left:4px solid var(--red)}.warning{border-left:4px solid var(--amber)}.badge{display:inline-block;padding:3px 7px;border-radius:5px;background:#182231;color:var(--blue)}
                table{width:100%;border-collapse:collapse;background:#0c121c}th,td{padding:10px;text-align:left;border:1px solid var(--line);vertical-align:top}th{color:var(--muted);font-size:11px;text-transform:uppercase}code{color:#cbd9ea;overflow-wrap:anywhere}
                .edge{display:grid;grid-template-columns:1fr auto 1fr;gap:10px;align-items:center;margin:8px 0;padding:12px;border:1px solid var(--line);border-radius:10px;background:#0c121c}.arrow{color:var(--red);font-weight:900}
                li{margin:.45rem 0}@media(max-width:620px){main{padding:20px 12px}h1{font-size:30px}.edge{grid-template-columns:1fr}.arrow{transform:rotate(90deg)}}
                </style></head><body><main>
                """);
        header(html, result);
        metrics(html, result, jfr);
        patterns(html, result);
        waitGraph(html, result);
        blockers(html, result);
        synchronizers(html, result);
        jfr(html, jfr);
        verification(html, result);
        html.append("<p class=\"muted\">Generated locally by ThreadCity. Uploaded evidence is not embedded in raw form.</p>")
                .append("</main></body></html>");
        return html.toString().getBytes(StandardCharsets.UTF_8);
    }

    private void header(StringBuilder html, AnalysisResult result) {
        html.append("<header><span class=\"eyebrow\">ThreadCity · incident evidence dossier</span>")
                .append("<h1>").append(escape(result.snapshot().sourceName())).append("</h1>")
                .append("<p>").append(escape(IncidentNarrative.diagnosis(result))).append("</p>")
                .append("<p class=\"muted\">Generated ")
                .append(escape(DateTimeFormatter.ISO_INSTANT.format(Instant.now())))
                .append(" · Parser coverage ").append(result.snapshot().parserDiagnostics().coveragePercent())
                .append("% (").append(escape(result.snapshot().parserDiagnostics().confidence().label()))
                .append(")</p></header>");
    }

    private void metrics(StringBuilder html, AnalysisResult result, Optional<JfrAnalysis> jfr) {
        long daemon = result.snapshot().threads().stream().filter(thread -> thread.metadata().daemon()).count();
        html.append("<h2>Incident at a glance</h2><div class=\"metrics\">");
        metric(html, "Threads", result.snapshot().threads().size());
        metric(html, "Non-daemon", result.snapshot().threads().size() - daemon);
        metric(html, "Blocked", result.stateCounts().get(ThreadState.BLOCKED));
        metric(html, "Wait edges", result.waitEdges().size());
        metric(html, "Deadlocks", result.deadlocks().size());
        metric(html, "JFR events", jfr.map(JfrAnalysis::relevantEvents).orElse(0L));
        html.append("</div>");
    }

    private void patterns(StringBuilder html, AnalysisResult result) {
        html.append("<h2>Pattern radar</h2>");
        if (result.patterns().isEmpty()) {
            html.append("<p class=\"muted\">No high-signal built-in incident pattern was detected.</p>");
            return;
        }
        html.append("<div class=\"cards\">");
        for (IncidentPattern pattern : result.patterns()) {
            html.append("<article class=\"card ")
                    .append(pattern.severity().name().toLowerCase(Locale.ROOT)).append("\">")
                    .append("<span class=\"badge\">").append(escape(pattern.type().label())).append(" · ")
                    .append(escape(pattern.confidence().label())).append("</span>")
                    .append("<h3>").append(escape(pattern.title())).append("</h3>")
                    .append("<p>").append(escape(pattern.explanation())).append("</p><ul>");
            pattern.evidence().forEach(line -> html.append("<li><code>").append(escape(line)).append("</code></li>"));
            html.append("</ul></article>");
        }
        html.append("</div>");
    }

    private void waitGraph(StringBuilder html, AnalysisResult result) {
        html.append("<h2>Wait-for evidence</h2>");
        if (result.waitEdges().isEmpty()) {
            html.append("<p class=\"muted\">No unambiguous waiter-to-owner edge was parsed.</p>");
            return;
        }
        for (WaitEdge edge : result.waitEdges()) {
            html.append("<div class=\"edge\"><div><strong>").append(escape(edge.waiter().name()))
                    .append("</strong><br><span class=\"muted\">").append(edge.waiter().state())
                    .append("</span></div><div class=\"arrow\">→ ").append(escape(edge.lock().shortId()))
                    .append(" →</div><div><strong>").append(escape(edge.owner().name()))
                    .append("</strong><br><code>").append(escape(edge.owner().topFrame()))
                    .append("</code></div></div>");
        }
    }

    private void blockers(StringBuilder html, AnalysisResult result) {
        html.append("<h2>Blocker blast radius</h2><table><thead><tr><th>Thread</th><th>Direct</th>")
                .append("<th>Transitive</th><th>Depth</th><th>Frame</th></tr></thead><tbody>");
        if (result.blockingImpacts().isEmpty()) {
            html.append("<tr><td colspan=\"5\" class=\"muted\">No resolved lock owner blocks another thread.</td></tr>");
        }
        for (BlockingImpact impact : result.blockingImpacts()) {
            html.append("<tr><td>").append(escape(impact.blocker().name())).append("</td><td>")
                    .append(impact.directlyBlocked()).append("</td><td>")
                    .append(impact.transitivelyBlocked()).append("</td><td>")
                    .append(impact.maximumDepth()).append("</td><td><code>")
                    .append(escape(impact.blocker().topFrame())).append("</code></td></tr>");
        }
        html.append("</tbody></table>");
    }

    private void synchronizers(StringBuilder html, AnalysisResult result) {
        html.append("<h2>Synchronizers</h2><table><thead><tr><th>Lock</th><th>Class</th><th>Risk</th>")
                .append("<th>Owners</th><th>Acquisition waiters</th></tr></thead><tbody>");
        result.synchronizers().stream().limit(30).forEach(insight -> synchronizerRow(html, insight));
        if (result.synchronizers().isEmpty()) {
            html.append("<tr><td colspan=\"5\" class=\"muted\">No synchronizer evidence was parsed.</td></tr>");
        }
        html.append("</tbody></table>");
    }

    private void synchronizerRow(StringBuilder html, SynchronizerInsight insight) {
        html.append("<tr><td><code>").append(escape(insight.lock().id())).append("</code></td><td>")
                .append(escape(insight.lock().className())).append("</td><td>")
                .append(escape(insight.risk().label())).append("</td><td>")
                .append(insight.owners().size()).append("</td><td>")
                .append(insight.acquisitionWaiters().size()).append("</td></tr>");
    }

    private void jfr(StringBuilder html, Optional<JfrAnalysis> analysis) {
        html.append("<h2>JFR correlation</h2>");
        if (analysis.isEmpty()) {
            html.append("<p class=\"muted\">No JFR recording was included in this dossier.</p>");
            return;
        }
        JfrAnalysis jfr = analysis.get();
        html.append("<p>").append(jfr.relevantEvents()).append(" relevant events retained from ")
                .append(jfr.eventsRead()).append(" events read over ")
                .append(jfr.recordingDuration().toMillis()).append(" ms.</p>")
                .append("<table><thead><tr><th>Category</th><th>Event</th><th>Count</th><th>Total duration</th>")
                .append("</tr></thead><tbody>");
        for (JfrEventSummary summary : jfr.summaries()) {
            html.append("<tr><td>").append(escape(summary.category().label())).append("</td><td>")
                    .append(escape(summary.eventLabel())).append("</td><td>").append(summary.count())
                    .append("</td><td>").append(summary.totalDuration().toMillis()).append(" ms</td></tr>");
        }
        html.append("</tbody></table>");
    }

    private void verification(StringBuilder html, AnalysisResult result) {
        html.append("<h2>Verification checklist</h2><ol>")
                .append("<li>Apply the smallest fix consistent with the exact wait-for and stack evidence.</li>")
                .append("<li>Repeat the same workload and capture at least three chronological dumps.</li>")
                .append("<li>Confirm that persistent waits and blocker blast radius trend toward zero.</li>")
                .append("<li>Use JFR CPU, monitor, park, I/O, GC, and virtual-thread events to test remaining suspects.</li>")
                .append("<li>Compare application latency, pool, database, and endpoint metrics before declaring recovery.</li>")
                .append("</ol><p class=\"muted\">Current baseline: ").append(result.waitEdges().size())
                .append(" wait edges and ").append(result.deadlocks().size()).append(" confirmed deadlocks.</p>");
    }

    private void metric(StringBuilder html, String label, long value) {
        html.append("<div class=\"metric\"><strong>").append(value).append("</strong><span>")
                .append(escape(label)).append("</span></div>");
    }

    static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
