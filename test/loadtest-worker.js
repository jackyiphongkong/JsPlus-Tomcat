// ============================================================================
// loadtest-worker.js — one load-test worker executed inside its OWN JsPlus
// console (created via Console.newBuilder() from the master LoadTest.js).
//
// Each worker:
//   * reads its slice from JVM system properties set by the master right
//     before con.eval():
//        LT_WORKER_ID   worker index (0-based)
//        LT_URL         target URL
//        LT_TOTAL       number of requests this worker must send
//        LT_INFLIGHT    batch size (concurrency inside this worker)
//        LT_TIMEOUT     per-request / overall budget in seconds
//        LT_OUT         absolute path of this worker's result JSON
//   * fires LT_TOTAL requests in batches of LT_INFLIGHT via
//     HttpClient.sendAsync + CompletableFuture.allOf().get() (pure Java
//     blocking — the only concurrency model that is safe on this GraalVM
//     build, which rejects multi-threaded access to JS callbacks)
//   * writes its own result JSON to LT_OUT
//
// It must NOT call System.exit(): the master console (LoadTest.js) owns
// JVM shutdown. Every request records its wall-clock completion time
// (System.currentTimeMillis()) so the master can merge workers onto one
// shared timeline.
// ============================================================================

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
var Files        = Java.type("java.nio.file.Files");
var Paths        = Java.type("java.nio.file.Paths");
var StandardCharsets = Java.type("java.nio.charset.StandardCharsets");

function esc2(s) {
    // no RegExp literals — this GraalVM build throws
    // "No language for id regex found" on any regex literal.
    return String(s).split("\\").join("\\\\").split('"').join('\\"').split("\n").join("\\n");
}

function main() {
    var id         = System.getProperty("LT_WORKER_ID", "0");
    var url        = System.getProperty("LT_URL", "https://localhost:8443/default/");
    var total      = parseInt(System.getProperty("LT_TOTAL", "100"), 10);
    var inFlight   = parseInt(System.getProperty("LT_INFLIGHT", "10"), 10);
    var timeoutSec = parseInt(System.getProperty("LT_TIMEOUT", "30"), 10);
    var out        = System.getProperty("LT_OUT", "");

    // --- trust store via system properties (JVM-global, harmless to set
    // again in each worker) ---
    var PathsT = Java.type("java.nio.file.Paths");
    var jppRaw = System.getenv("JS_PLUS_PATH");
    var jppBase = "";
    if (jppRaw !== null && jppRaw !== undefined && jppRaw !== "") {
        try { jppBase = PathsT.get(jppRaw).normalize().toString(); } catch (e) { jppBase = jppRaw; }
    }
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

    var client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();

    var request = HttpRequest.newBuilder(URI.create(url))
        .timeout(Duration.ofSeconds(timeoutSec))
        .GET()
        .build();

    // --- warm-up: a few unmeasured requests (SSL session + DB pools) ---
    for (var w = 0; w < Math.min(3, total); w++) {
        try { client.send(request, HttpResponse.BodyHandlers.ofString()); } catch (e) {}
    }

    // --- measured run: batches of inFlight sendAsync + allOf().get() ---
    var startAtomic = new AtomicLong(System.nanoTime());
    var list = [];           // {status, latencyMs, doneAtMs, error?}
    var sent = 0;
    var timedOut = false;

    while (sent < total) {
        var n = Math.min(inFlight, total - sent);
        var batch = [];
        var t0s = [];
        for (var i = 0; i < n; i++) {
            t0s.push(System.nanoTime());
            batch.push(client.sendAsync(request, HttpResponse.BodyHandlers.ofString()));
        }
        var allFut = CompletableFuture.allOf(Java.to(batch, "java.util.concurrent.CompletableFuture[]"));
        try {
            allFut.get(timeoutSec, TimeUnit.SECONDS);
        } catch (e) {
            timedOut = true;
        }
        for (var j = 0; j < batch.length; j++) {
            var status = 0, err = null;
            for (var attempt = 0; attempt < 3; attempt++) {
                try {
                    var resp = batch[j].get(5, TimeUnit.SECONDS);
                    status = resp.statusCode();
                    break;
                } catch (e2) {
                    if (attempt < 2) {
                        try { Thread.sleep(300); } catch (ie) {}
                        try {
                            var retryFut = client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
                            var resp2 = retryFut.get(5, TimeUnit.SECONDS);
                            status = resp2.statusCode();
                            break;
                        } catch (e3) { continue; }
                    }
                    err = String(e2);
                    break;
                }
            }
            list.push({
                status: status,
                latencyMs: (System.nanoTime() - t0s[j]) / 1000000,
                doneAtMs: System.currentTimeMillis(),
                error: err
            });
        }
        sent += n;
        if (timedOut && (System.nanoTime() - startAtomic.get()) / 1000000000 > timeoutSec) {
            break;
        }
    }
    var elapsedMs = (System.nanoTime() - startAtomic.get()) / 1000000;

    var success = 0, failed = 0;
    for (var i = 0; i < list.length; i++) {
        if (list[i].status > 0) success++; else failed++;
    }

    var json = "{";
    json += "\"workerId\": " + id + ",";
    json += "\"elapsedMs\": " + Math.round(elapsedMs) + ",";
    json += "\"success\": " + success + ",";
    json += "\"failed\": " + failed + ",";
    json += "\"list\": [";
    for (var i = 0; i < list.length; i++) {
        if (i > 0) json += ",";
        json += "{\"st\":" + list[i].status + ",\"ms\":" + Math.round(list[i].latencyMs)
              + ",\"t\":" + list[i].doneAtMs;
        if (list[i].error !== null) json += ",\"e\":\"" + esc2(list[i].error) + "\"";
        json += "}";
    }
    json += "]}";

    if (out !== "") {
        Files.writeString(Paths.get(out), json, StandardCharsets.UTF_8);
    }
    print("worker " + id + " done: success=" + success + " failed=" + failed
          + " elapsed=" + Math.round(elapsedMs) + "ms");
}

main();
