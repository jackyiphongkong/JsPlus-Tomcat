// ============================================================================
// LoadTest.js — simple HTTP load test for JsPlus-Tomcat
// JS port of the former build/loadtest/LoadTest.java, executed through the
// JsPlus console so no separate Java build step is needed.
//
// Usage (TestAll.bat/.sh Step 3, or manual):
//   java -cp <DEPEND_LIBS> com.killcoding.jsplus.Main \
//        -ScriptEngine graalvm.js -Env script -WriteHistory false \
//        -Task test/LoadTest.js < NUL
//
// Configuration via environment variables (same names as before):
//   LOADTEST_URL          default https://localhost:8443/default/
//   LOADTEST_CONCURRENCY  default 10
//   LOADTEST_TOTAL        default 1000
//   LOADTEST_TIMEOUT      default 30  (seconds; per-request + overall budget)
//
// Concurrency model: HttpClient.sendAsync() fires requests on the JDK HTTP
// thread pool; the script waits on CompletableFuture.allOf(...).get() (a pure
// Java blocking call, no JS proxies executed from host threads — GraalVM JS
// would deadlock on those). Retries are synchronous sendAsync + get().
//
// Outputs (identical to the Java version):
//   ../docs/latest/report/loadtest-result.json
//   ../docs/latest/report/loadtest-dashboard.html
//   (plus a legacy copy under ../test_reports/)
// ============================================================================

// Self-signed test certificate:
// 1) disable HttpClient hostname verification BEFORE the first HttpClient
//    is built, otherwise every handshake fails with SSLHandshakeException
//    ("No name matching localhost").
// 2) point the default trust store at the project truststore (which contains
//    the "client" trusted cert used by the 8443 connector), instead of a
//    trust-all X509TrustManager JS proxy — a JS proxy invoked from the JDK
//    HTTP thread pool throws IllegalStateException ("Multi threaded access
//    requested ... not allowed for language(s) js") on this GraalVM build.
Java.type("java.lang.System").setProperty("jdk.internal.httpclient.disableHostnameVerification", "true");

var System       = Java.type("java.lang.System");
var URI          = Java.type("java.net.URI");
var HttpClient   = Java.type("java.net.http.HttpClient");
var HttpRequest  = Java.type("java.net.http.HttpRequest");
var HttpResponse = Java.type("java.net.http.HttpResponse");
var Duration     = Java.type("java.time.Duration");
var CompletableFuture = Java.type("java.util.concurrent.CompletableFuture");
var TimeUnit     = Java.type("java.util.concurrent.TimeUnit");
var AtomicLong   = Java.type("java.util.concurrent.atomic.AtomicLong");
var Thread       = Java.type("java.lang.Thread");
var File         = Java.type("java.io.File");
var DiskFile     = Java.type("com.killcoding.file.DiskFile");
var ScriptConsole = Java.type("com.killcoding.jsplus.ScriptConsole");

function envOr(name, def) {
    var v = System.getenv(name);
    return (v !== null && v !== undefined && v !== "") ? v : String(def);
}

function round1(x) { return Math.round(x * 10.0) / 10.0; }
function esc(s) {
    // NOTE: no RegExp literals here — this GraalVM build (language "js" only)
    // throws "No language for id regex found" on any regex literal.
    return String(s).split("\\").join("\\\\").split('"').join('\\"').split("\n").join("\\n");
}
function pad2(n) { return n < 10 ? "0" + n : String(n); }
function percentile(sorted, p) {
    if (sorted.length === 0) return 0;
    var idx = Math.ceil(sorted.length * p / 100.0) - 1;
    if (idx < 0) idx = 0;
    if (idx >= sorted.length) idx = sorted.length - 1;
    return sorted[idx];
}

// NOTE: this script is intentionally NOT async. All waiting is done through
// CompletableFuture.allOf().get() (a synchronous Java blocking call); an async
// main() would return immediately and JsPlus would not wait for the pending
// promise before shutting down the script engine.
function main() {
    var url         = envOr("LOADTEST_URL", "https://localhost:8443/default/");
    var concurrency = parseInt(envOr("LOADTEST_CONCURRENCY", 10), 10);
    var total       = parseInt(envOr("LOADTEST_TOTAL", 1000), 10);
    var timeoutSec  = parseInt(envOr("LOADTEST_TIMEOUT", 30), 10);
    var inFlight    = Math.max(1, concurrency);

    print("LoadTest target=" + url + " concurrency=" + concurrency
          + " total=" + total + " timeout=" + timeoutSec + "s");

    // --- base path (normalized JS_PLUS_PATH) ---
    var PathsT = Java.type("java.nio.file.Paths");
    var jppRaw = System.getenv("JS_PLUS_PATH");
    var jppBase = "";
    if (jppRaw !== null && jppRaw !== undefined && jppRaw !== "") {
        try { jppBase = PathsT.get(jppRaw).normalize().toString(); } catch (e) { jppBase = jppRaw; }
    }

    // --- trust store via system properties (no JS proxy involved) ---
    var trustCandidates = [];
    if (jppBase !== "") {
        trustCandidates.push(jppBase + "/key/default_truststore.jks");
        trustCandidates.push(jppBase + "/../../../JsPlus/key/default_truststore.jks");
    }
    var trustStore = "";
    for (var t = 0; t < trustCandidates.length; t++) {
        try {
            if (new File(trustCandidates[t]).exists()) { trustStore = trustCandidates[t]; break; }
        } catch (e) {}
    }
    if (trustStore === "") {
        trustStore = "D:/Working/JsPlus-Tomcat/JsPlus/key/default_truststore.jks";
    }
    System.setProperty("javax.net.ssl.trustStore", trustStore);
    System.setProperty("javax.net.ssl.trustStorePassword", "changeit");
    System.setProperty("javax.net.ssl.trustStoreType", "JKS");
    print("LoadTest trustStore=" + trustStore);

    var client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();

    var request = HttpRequest.newBuilder(URI.create(url))
        .timeout(Duration.ofSeconds(timeoutSec))
        .GET()
        .build();

    // --- warm-up: a few unmeasured requests so SSL session + DB pools are
    // ready before the measured run. Async + bounded wait so a hung upstream
    // can never stall the whole run for 5x request timeout. ---
    var warmDeadline = System.nanoTime() + Math.min(timeoutSec, 10) * 1000000000;
    for (var w = 0; w < Math.min(5, total); w++) {
        try {
            var wf = client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
            wf.get(5, TimeUnit.SECONDS);
        } catch (e) {
            // warm-up failures are not counted
        }
        if (System.nanoTime() > warmDeadline) {
            print("[LoadTest] warm-up budget exceeded, starting measured run anyway");
            break;
        }
    }

    // --- measured run: fire inFlight requests at a time, wait via
    // CompletableFuture.allOf().get() (pure Java blocking call — safe on this
    // GraalVM build), then read each future. Transport-level failures
    // (connect/reset/timeout) are transient under concurrency; retry twice
    // synchronously before counting the request as failed. Any HTTP response
    // counts as success. ---
    // OVERALL BUDGET: the measured run hard-stops after `timeoutSec` seconds
    // (measured from the first request) so a hung upstream can never block
    // TestAll's Step 3 indefinitely — results so far are still written out.
    var startAtomic = new AtomicLong(System.nanoTime());
    var deadlineNanos = System.nanoTime() + timeoutSec * 1000000000;
    var list = [];           // {status, latencyMs, doneAtSec, error?}
    var sent = 0;
    var timedOut = false;
    var errors = [];

    function record(entry) { list.push(entry); }

    while (sent < total && System.nanoTime() < deadlineNanos) {
        var n = Math.min(inFlight, total - sent);
        var batch = [];
        var t0s = [];
        for (var i = 0; i < n; i++) {
            t0s.push(System.nanoTime());
            batch.push(client.sendAsync(request, HttpResponse.BodyHandlers.ofString()));
        }
        var allFut = CompletableFuture.allOf(Java.to(batch, "java.util.concurrent.CompletableFuture[]"));
        var remainMs = Math.max(1000, (deadlineNanos - System.nanoTime()) / 1000000);
        try {
            allFut.get(Math.round(Math.min(timeoutSec * 1000, remainMs)), TimeUnit.MILLISECONDS);
        } catch (e) {
            timedOut = true;
            // any futures that are not done yet stay unresolved; their .get()
            // below will throw (or block until their own request timeout).
        }
        for (var j = 0; j < batch.length; j++) {
            if (System.nanoTime() > deadlineNanos) {
                record({ status: 0, latencyMs: 0, doneAtSec: (System.nanoTime() - startAtomic.get()) / 1000000000,
                         error: "overall budget exceeded" });
                continue;
            }
            var status = 0, err = null;
            for (var attempt = 0; attempt < 3; attempt++) {
                try {
                    var resp = batch[j].get(5, TimeUnit.SECONDS);
                    status = resp.statusCode();
                    break;
                } catch (e2) {
                    if (attempt < 2) {
                        try { Thread.sleep(300); } catch (ie) {}
                        // retry with a fresh async request
                        try {
                            var retryFut = client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
                            var resp2 = retryFut.get(5, TimeUnit.SECONDS);
                            status = resp2.statusCode();
                            break;
                        } catch (e3) {
                            continue;
                        }
                    }
                    err = String(e2);
                    break;
                }
            }
            var doneAt = (System.nanoTime() - startAtomic.get()) / 1000000000;
            record({
                status: status,
                latencyMs: (System.nanoTime() - t0s[j]) / 1000000,
                doneAtSec: doneAt,
                error: err
            });
        }
        sent += n;
        if (timedOut && (System.nanoTime() - startAtomic.get()) / 1000000000 > timeoutSec) {
            break;
        }
    }
    var elapsedMs = (System.nanoTime() - startAtomic.get()) / 1000000;
    if (sent < total) {
        print("[LoadTest] OVERALL BUDGET hit after " + Math.round(elapsedMs / 1000)
              + "s (" + sent + "/" + total + " requests issued) — finishing early");
    }

    // --- aggregate ---
    var lats = [];
    for (var i = 0; i < list.length; i++) {
        if (list[i].status > 0) lats.push(list[i].latencyMs);
    }
    lats.sort(function(a, b) { return a - b; });

    var success = 0, failed = 0;
    var statusCount = {}, perSec = {}, hist = {};
    for (var i = 0; i < list.length; i++) {
        var r = list[i];
        if (r.status > 0) {
            success++;
            statusCount[r.status] = (statusCount[r.status] || 0) + 1;
        } else {
            failed++;
            if (r.error && errors.length < 20) errors.push(String(r.error));
        }
        var secKey = String(Math.round(r.doneAtSec));
        perSec[secKey] = (perSec[secKey] || 0) + 1;
        var bucket = Math.floor(r.latencyMs / 50);
        hist[bucket] = (hist[bucket] || 0) + 1;
    }

    var elapsedSec = Math.max(elapsedMs / 1000.0, 0.001);
    var qps = success / elapsedSec;
    var avg = lats.length === 0 ? 0 : lats.reduce(function(a, b) { return a + b; }, 0) / lats.length;
    var p50 = percentile(lats, 50);
    var p90 = percentile(lats, 90);
    var p95 = percentile(lats, 95);
    var p99 = percentile(lats, 99);
    var max = lats.length === 0 ? 0 : lats[lats.length - 1];

    var now = new Date();
    var ts = now.getFullYear() + "-" + pad2(now.getMonth() + 1) + "-" + pad2(now.getDate())
           + " " + pad2(now.getHours()) + ":" + pad2(now.getMinutes()) + ":" + pad2(now.getSeconds());

    // --- JSON ---
    var json = "{\n";
    json += "  \"timestamp\": \"" + ts + "\",\n";
    json += "  \"target\": \"" + esc(url) + "\",\n";
    json += "  \"concurrency\": " + concurrency + ",\n";
    json += "  \"totalRequests\": " + total + ",\n";
    json += "  \"elapsedMs\": " + Math.round(elapsedMs) + ",\n";
    json += "  \"success\": " + success + ",\n";
    json += "  \"failed\": " + failed + ",\n";
    json += "  \"qps\": " + round1(qps) + ",\n";
    json += "  \"avgMs\": " + Math.round(avg) + ",\n";
    json += "  \"p50Ms\": " + Math.round(p50) + ",\n";
    json += "  \"p90Ms\": " + Math.round(p90) + ",\n";
    json += "  \"p95Ms\": " + Math.round(p95) + ",\n";
    json += "  \"p99Ms\": " + Math.round(p99) + ",\n";
    json += "  \"maxMs\": " + Math.round(max) + ",\n";
    json += "  \"statusCount\": {";
    var keys = Object.keys(statusCount).sort(function(a, b) { return a - b; });
    for (var k = 0; k < keys.length; k++) {
        if (k > 0) json += ",";
        json += "\"" + keys[k] + "\": " + statusCount[keys[k]];
    }
    json += "},\n";
    json += "  \"errors\": [";
    for (var k = 0; k < errors.length; k++) {
        if (k > 0) json += ",";
        json += "\"" + esc(errors[k]) + "\"";
    }
    json += "]\n";
    json += "}\n";

    // --- HTML dashboard (offline, static SVG/CSS) ---
    var html = buildHtml(url, concurrency, total, ts, Math.round(elapsedMs),
        success, failed, round1(qps), Math.round(avg), Math.round(p50), Math.round(p90),
        Math.round(p95), Math.round(p99), Math.round(max), statusCount, perSec, hist, errors);

    // --- write outputs into docs/latest/report/ (the JsPlus-Core docs layout)
    //     and mirror them into the legacy test_reports/ folder ---
    var Files2 = Java.type("java.nio.file.Files");
    var Paths2 = Java.type("java.nio.file.Paths");
    var StandardCharsets2 = Java.type("java.nio.charset.StandardCharsets");
    // Resolve the first existing candidate directory; create the first
    // candidate when none exists yet. Returns a forward-slash path with a
    // trailing slash so the joins below are platform-neutral.
    var resolveReportDir = function (candidates) {
        for (var c = 0; c < candidates.length; c++) {
            var rf = new File(candidates[c]);
            try {
                if (rf.exists()) { return rf.getCanonicalPath().replace("\\", "/") + "/"; }
            } catch (e) {}
        }
        var mk = new File(candidates[0]);
        try { mk.mkdirs(); } catch (e) {}
        try { return mk.getCanonicalPath().replace("\\", "/") + "/"; } catch (e) { return candidates[0]; }
    };
    var primaryDir = resolveReportDir([
        (jppBase !== "" ? jppBase + "/../../docs/latest/report/" : "../docs/latest/report/"),
        (jppBase !== "" ? jppBase + "/../../../docs/latest/report/" : "docs/latest/report/"),
        "../docs/latest/report/",
        "docs/latest/report/"
    ]);
    var legacyDir = resolveReportDir([
        (jppBase !== "" ? jppBase + "/../../test_reports/" : "../test_reports/"),
        (jppBase !== "" ? jppBase + "/../../../test_reports/" : "test_reports/"),
        "../test_reports/",
        "test_reports/"
    ]);
    print("LoadTest reportDir=" + primaryDir);
    Files2.writeString(Paths2.get(primaryDir + "loadtest-result.json"), json, StandardCharsets2.UTF_8);
    print("JSON written to " + primaryDir + "loadtest-result.json");
    Files2.writeString(Paths2.get(primaryDir + "loadtest-dashboard.html"), html, StandardCharsets2.UTF_8);
    print("Dashboard written to " + primaryDir + "loadtest-dashboard.html");
    try {
        if (legacyDir !== primaryDir) {
            Files2.writeString(Paths2.get(legacyDir + "loadtest-result.json"), json, StandardCharsets2.UTF_8);
            Files2.writeString(Paths2.get(legacyDir + "loadtest-dashboard.html"), html, StandardCharsets2.UTF_8);
        }
    } catch (e) {
        print("[WARN] Could not write legacy loadtest copy: " + e);
    }
    print("Result: success=" + success + " failed=" + failed
          + " qps=" + round1(qps) + " avg=" + Math.round(avg)
          + "ms p50=" + Math.round(p50) + "ms p90=" + Math.round(p90)
          + "ms p99=" + Math.round(p99) + "ms max=" + Math.round(max) + "ms");

    // Exit this JVM directly WITHOUT writing the shared exit.tmp signal file
    // (same reason as TestAll.js): ScriptConsole.exit() would write
    // <JS_PLUS_PATH>/.tmp/exit.tmp, which every other JsPlus JVM watching it
    // (including the H2 ghost started by BG_StartH2.bat) would detect and
    // shut down together with this JVM.
    System.exit(0);
}
main();

// ============================================================================
// HTML dashboard builder — fully static: inline SVG/CSS only, no JS, no CDN.
// ============================================================================
function buildHtml(tUrl, tConc, tTotal, tTs, tElapsedMs, tSuccess, tFailed,
                   tQps, tAvg, tP50, tP90, tP95, tP99, tMax, tStatusCount,
                   tPerSec, tHist, tErrors) {
    var sb = "";
    sb += "<!DOCTYPE html>\n<html lang=\"zh-HK\">\n<head>\n<meta charset=\"UTF-8\">\n";
    sb += "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n";
    sb += "<title>Load Test Dashboard</title>\n";
    sb += "<style>\n";
    sb += "body{font-family:Segoe UI,Arial,sans-serif;background:#0f172a;color:#e2e8f0;margin:0;padding:24px;}\n";
    sb += "h1{font-size:22px;margin:0 0 4px;color:#f8fafc;}\n";
    sb += ".sub{color:#94a3b8;font-size:13px;margin-bottom:20px;}\n";
    sb += ".cards{display:grid;grid-template-columns:repeat(auto-fit,minmax(150px,1fr));gap:12px;margin-bottom:20px;}\n";
    sb += ".card{background:#1e293b;border:1px solid #334155;border-radius:10px;padding:14px;}\n";
    sb += ".card .k{font-size:11px;color:#94a3b8;text-transform:uppercase;letter-spacing:.5px;}\n";
    sb += ".card .v{font-size:24px;font-weight:600;margin-top:4px;color:#f8fafc;}\n";
    sb += ".card.ok .v{color:#34d399;}\n.card.bad .v{color:#f87171;}\n";
    sb += ".panel{background:#1e293b;border:1px solid #334155;border-radius:10px;padding:16px;margin-bottom:16px;}\n";
    sb += ".panel h2{font-size:15px;margin:0 0 12px;color:#cbd5e1;}\n";
    sb += "table{border-collapse:collapse;width:100%;font-size:13px;}\n";
    sb += "th,td{text-align:left;padding:6px 10px;border-bottom:1px solid #334155;}\n";
    sb += "th{color:#94a3b8;font-weight:500;}\n";
    sb += ".err{color:#f87171;font-family:Consolas,monospace;font-size:12px;}\n";
    sb += ".bar-row{display:flex;align-items:center;gap:8px;margin-bottom:4px;}\n";
    sb += ".bar-label{width:70px;font-size:11px;color:#94a3b8;text-align:right;}\n";
    sb += ".bar-track{flex:1;background:#0f172a;border-radius:4px;height:16px;overflow:hidden;}\n";
    sb += ".bar-fill{height:100%;background:#38bdf8;border-radius:4px;}\n";
    sb += "</style>\n</head>\n<body>\n";

    sb += "<h1>Load Test Dashboard</h1>\n";
    sb += "<div class=\"sub\">Target: <b>" + esc(tUrl) + "</b> &nbsp;|&nbsp; "
        + "Concurrency: <b>" + tConc + "</b> &nbsp;|&nbsp; "
        + "Total: <b>" + tTotal + "</b> &nbsp;|&nbsp; "
        + "Elapsed: <b>" + round1(tElapsedMs / 1000.0) + "s</b> &nbsp;|&nbsp; "
        + "Run at: <b>" + tTs + "</b></div>\n";

    var errRate = (tSuccess + tFailed) === 0 ? 0 : tFailed * 100.0 / (tSuccess + tFailed);
    sb += "<div class=\"cards\">\n";
    sb += card("Total", String(tSuccess + tFailed), "");
    sb += card("Success", String(tSuccess), "ok");
    sb += card("Failed", String(tFailed), tFailed > 0 ? "bad" : "");
    sb += card("Error Rate", round1(errRate) + "%", tFailed > 0 ? "bad" : "");
    sb += card("QPS", String(round1(tQps)), "");
    sb += card("Avg", tAvg + " ms", "");
    sb += card("P50", tP50 + " ms", "");
    sb += card("P90", tP90 + " ms", "");
    sb += card("P99", tP99 + " ms", "");
    sb += card("Max", tMax + " ms", "");
    sb += "</div>\n";

    // Status code distribution
    sb += "<div class=\"panel\"><h2>Status Code Distribution</h2>\n";
    var stKeys = Object.keys(tStatusCount).sort(function(a, b) { return a - b; });
    if (stKeys.length === 0) {
        sb += "<div class=\"err\">No HTTP responses received (all requests failed).</div>\n";
    } else {
        var maxStatus = 0;
        for (var s = 0; s < stKeys.length; s++) { maxStatus = Math.max(maxStatus, tStatusCount[stKeys[s]]); }
        for (var s = 0; s < stKeys.length; s++) {
            var sc = parseInt(stKeys[s], 10);
            var pct = maxStatus === 0 ? 0 : Math.round(tStatusCount[stKeys[s]] * 100.0 / maxStatus);
            var color = sc >= 500 ? "#f87171" : (sc >= 400 ? "#fbbf24" : "#34d399");
            sb += "<div class=\"bar-row\"><div class=\"bar-label\">" + sc
                + "</div><div class=\"bar-track\"><div class=\"bar-fill\" style=\"width:"
                + pct + "%;background:" + color + "\"></div></div><div style=\"font-size:12px\">"
                + tStatusCount[stKeys[s]] + "</div></div>\n";
        }
    }
    sb += "</div>\n";

    // Throughput per second (SVG bar chart)
    sb += "<div class=\"panel\"><h2>Throughput (requests per second)</h2>\n";
    sb += svgBars(tPerSec);
    sb += "</div>\n";

    // Latency histogram (SVG)
    sb += "<div class=\"panel\"><h2>Latency Distribution (50 ms buckets)</h2>\n";
    sb += svgBars2(tHist);
    sb += "</div>\n";

    // Percentile table
    sb += "<div class=\"panel\"><h2>Latency Percentiles</h2>\n";
    sb += "<table><tr><th>Metric</th><th>Value</th></tr>\n";
    sb += "<tr><td>Average</td><td>" + tAvg + " ms</td></tr>\n";
    sb += "<tr><td>P50</td><td>" + tP50 + " ms</td></tr>\n";
    sb += "<tr><td>P90</td><td>" + tP90 + " ms</td></tr>\n";
    sb += "<tr><td>P95</td><td>" + tP95 + " ms</td></tr>\n";
    sb += "<tr><td>P99</td><td>" + tP99 + " ms</td></tr>\n";
    sb += "<tr><td>Max</td><td>" + tMax + " ms</td></tr>\n";
    sb += "</table>\n</div>\n";

    // Errors
    if (tErrors.length > 0) {
        sb += "<div class=\"panel\"><h2>Errors (first 20)</h2>\n";
        sb += "<div class=\"err\">" + tErrors.join("<br>") + "</div>\n";
        sb += "</div>\n";
    }

    sb += "</body>\n</html>\n";
    return sb;
}

function card(k, v, cls) {
    return "<div class=\"card " + cls + "\"><div class=\"k\">" + k
        + "</div><div class=\"v\">" + v + "</div></div>\n";
}

/** Vertical SVG bars for per-second throughput. */
function svgBars(tPerSec) {
    var secs = Object.keys(tPerSec).sort(function(a, b) { return a - b; });
    if (secs.length === 0) return "<div class=\"err\">No completed requests.</div>";
    var maxV = 0;
    for (var s = 0; s < secs.length; s++) { maxV = Math.max(maxV, tPerSec[secs[s]]); }
    var W = 900, H = 180, pad = 10, bw = 6, gap = 2;
    var out = "";
    out += "<svg viewBox=\"0 0 " + W + " " + (H + 24)
        + "\" style=\"width:100%;height:auto\">\n";
    var x = pad;
    for (var s = 0; s < secs.length; s++) {
        var v = tPerSec[secs[s]];
        var h = maxV === 0 ? 0 : Math.max(1, Math.round(v * (H - 20) / maxV));
        var y = H - h;
        out += "<rect x=\"" + x + "\" y=\"" + y
            + "\" width=\"" + bw + "\" height=\"" + h
            + "\" fill=\"#38bdf8\" rx=\"1\"><title>t+" + secs[s]
            + "s: " + v + " req</title></rect>\n";
        x += bw + gap;
    }
    out += "<text x=\"" + pad + "\" y=\"" + (H + 16)
        + "\" fill=\"#94a3b8\" font-size=\"10\">time (s) since start</text>\n";
    out += "</svg>\n";
    return out;
}

/** Horizontal SVG bars for latency histogram. */
function svgBars2(tHist) {
    var buckets = Object.keys(tHist).sort(function(a, b) { return a - b; });
    if (buckets.length === 0) return "<div class=\"err\">No latency samples.</div>";
    var maxV = 0;
    for (var b = 0; b < buckets.length; b++) { maxV = Math.max(maxV, tHist[buckets[b]]); }
    var out = "<div style=\"max-height:240px;overflow-y:auto\">\n";
    for (var b = 0; b < buckets.length; b++) {
        var v = tHist[buckets[b]];
        var pct = maxV === 0 ? 0 : Math.round(v * 100.0 / maxV);
        out += "<div class=\"bar-row\"><div class=\"bar-label\">"
            + (parseInt(buckets[b], 10) * 50) + "+ms</div><div class=\"bar-track\"><div class=\"bar-fill\" style=\"width:"
            + pct + "%\"></div></div><div style=\"font-size:12px\">"
            + v + "</div></div>\n";
    }
    out += "</div>\n";
    return out;
}
