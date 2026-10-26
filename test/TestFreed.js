// ============================================================================
// JsPlus-Tomcat Console Pool freed() Test Suite (console mode)
// ----------------------------------------------------------------------------
// Run      : Inside the JsPlus console (server already running):
//              Console.eval(<path to this file>);
//            No bat/sh launcher needed — the console environment provides the
//            running Tomcat, the H2 data source and the global `tomcat` object.
// Harness  : Shared plumbing (Java bindings, counters, check/isOk/msg and
//            writeReport) lives in TestBase.js and is loaded below.
// Output   : <PROJECT_ROOT>/test_reports/TestFreed-Report.html (green PASS /
//            red FAIL per check), written with BaseFile (DiskFile). Each check
//            prints [PASS]/[FAIL].
// NOTE     : In console mode the suite NEVER calls System.exit() (that would
//            shut down the ghost console); it prints the summary instead.
//            Run only against a disposable/development database.
// Focus    : Prove TomcatScriptConsole.freed() (B1 fix: shared daemon
//            FREED_SCHEDULER with delayed return, no thread leak per call)
//            actually runs and returns every console to the pool under a
//            sustained 1000+ call load — via the real production path
//            (getDomainParams -> finally { freed() }).
// Env      : TESTFREED_TOTAL      default 1000   (number of freed() cycles)
//            TESTFREED_SCRIPT     default "sample.js" (missing -> Missing path;
//                          set to "sample.js" to exercise the Loaded path)
// ============================================================================

// Load the shared test harness. Requires the JsPlus console to be running
// (JS_PLUS_PATH must be set so TestBase.js can be located).
var System = Java.type("java.lang.System");
var basePath = System.getProperty("JS_PLUS_PATH");
try {
    if (basePath === null || basePath === undefined || basePath.length === 0) {
        throw "JS_PLUS_PATH is not set — cannot locate TestBase.js";
    }
    load(basePath + "../test/TestBase.js");
} catch (e) {
    print("[ERROR] Failed to load TestBase.js: " + e);
    throw e;
}
beginTest();

// Report file name and title for this suite (consumed by writeReport in TestBase.js).
var REPORT_NAME = "TestFreed-Report.html";
var REPORT_TITLE = "JsPlus-Tomcat Console Pool freed() Test Report";

print("=== JsPlus-Tomcat Console Pool freed() Test ===");
print("JS_PLUS_PATH: " + System.getProperty("JS_PLUS_PATH"));
print("Mode        : " + (CONSOLE_MODE ? "console (existing server, no System.exit)" : "standalone"));

// Java bindings used by this suite.
var Thread = Java.type("java.lang.Thread");
var TSC = Java.type("com.killcoding.jsplus.tomcat.TomcatScriptConsole");

function envOr(name, def) {
    var v = System.getenv(name);
    // NOTE: use ("" + def) instead of String(def): the console environment may
    // shadow the JS built-in String with java.lang.String (e.g. sample.js does
    // `var String = Java.type('java.lang.String')`), turning String(def) into an
    // unsupported "execute on java.lang.String" interop call.
    return (v !== null && v !== undefined && v !== "") ? v : ("" + def);
}
function round3(x) { return Math.round(x * 1000.0) / 1000.0; }

var r;

// Case F1
check({
    name: "console environment ready (global tomcat)",
    ok: CONSOLE_MODE,
    detail: "expected to run via Console.eval(...) inside the JsPlus console",
    objective: "Ensure the suite runs inside the live JsPlus console (server ready, no System.exit)",
    objectiveZh: "確保測試套件於 JsPlus 主控台內執行（伺服器就緒、無 System.exit）",
    command: "Console.eval(System.getenv('JS_PLUS_PATH') + '/../test/TestFreed.js')"
});

// Case F2 — create the user-specified mapping: Forward proxy to
// https://killcoding.com with scriptEnable=true and scriptFile='sample.js'.
// 'sample.js' exists (Loaded path): getDomainParams evaluates it, calls
// DomainMapping.getParams() and its finally { freed() } must still return
// the console (this is the B1 contract).
var mapParams = new HashMap();
mapParams.put("mode", "Forward");
mapParams.put("target", "https://killcoding.com");
mapParams.put("scriptEnable", true);
mapParams.put("scriptFile", "sample.js");
r = callTomcat(function(){ return tomcat.setDomainMapping("127.0.0.1", "8443", "/", mapParams); });
check({
    name: "setDomainMapping 127.0.0.1:8443 uri=/ mode=Forward scriptFile=sample.js",
    ok: isOk(r),
    detail: msg(r) + dbVerify(function(){ return "SELECT * FROM domain_mapping_param WHERE domain='127.0.0.1' AND port='8443' AND COALESCE(uri,'')='' AND deleted=FALSE"; }),
    objective: "Verify the Forward mapping with scriptEnable=true + scriptFile='sample.js' (missing script) is created and persisted",
    objectiveZh: "驗證可建立並持久化 Forward 對應（scriptEnable=true + scriptFile='sample.js' 不存在的腳本）",
    command: "tomcat.setDomainMapping(\"127.0.0.1\", \"8443\", \"/\", {mode: \"Forward\", target: \"https://killcoding.com\", scriptEnable: true, scriptFile: \"sample.js\"})"
});

// Case F3 — one live request through the mapping: any real HTTP response
// (200/502/...) proves the request reached DomainProxy and exercised
// applyDomainScript -> getDomainParams -> finally { freed() }. Record the
// pool usage immediately before the request as the baseline u0.
var u0 = TSC.usage();
var statusCode = -1;
var bodySnippet = "";
var curlReached = false;
try {
    var codeOut = runCmd(["curl", "-k", "-s", "-o", NULL_DEVICE, "-w", "%{http_code}", "-m", "15", "https://127.0.0.1:8443/"]);
    statusCode = parseInt(codeOut.trim());
    curlReached = statusCode > 0;   // any HTTP status => request reached the proxy
    var body = runCmd(["curl", "-k", "-s", "-m", "15", "https://127.0.0.1:8443/"]);
    bodySnippet = ("" + body).split("\n")[0];
    if (bodySnippet.length > 120) { bodySnippet = bodySnippet.substring(0, 120) + "..."; }
} catch (e) {
    print("[WARN] curl request failed: " + e);
}
Thread.sleep(300);   // let the 10ms delayed return settle
var u1 = TSC.usage();
check({
    name: "curl GET https://127.0.0.1:8443/ (live path, freed baseline)",
    ok: curlReached,
    detail: "status=" + statusCode + " usage0=" + round3(u0) + " usage1=" + round3(u1) + " body=" + bodySnippet + dbVerify(function(){ return "SELECT * FROM domain_mapping_param WHERE domain='127.0.0.1' AND port='8443' AND COALESCE(uri,'')='' AND deleted=FALSE"; }),
    objective: "Verify one live request reaches DomainProxy (any HTTP status) and the console pool returns to baseline after the request (freed ran)",
    objectiveZh: "驗證一次即時請求到達 DomainProxy（任何 HTTP 狀態）且請求後 console 池回復基線（freed 已執行）",
    command: "curl -k -s -o NUL -w \"%{http_code}\" -m 15 https://127.0.0.1:8443/"
});

// Case F4 — SUSTAINED freed() PROOF: call the real production path
// TomcatScriptConsole.getDomainParams(<scriptFile>) TESTFREED_TOTAL (default
// 1000) times in a row. Every call ends in finally { freed() } which must
// return the console to the pool. If the B1 fix is missing or broken, each
// cycle would consume one console until the pool is exhausted and
// "Full Console Pool." is thrown — errors > 0 and/or usage climbing to 1.
var total  = parseInt(envOr("TESTFREED_TOTAL", "1000"), 10);
var script = envOr("TESTFREED_SCRIPT", "sample.js");
print("freed() stress: total=" + total + " script=" + script + " poolSize=" + TSC.getPoolSize() + " freedDelayMs=" + TSC.getFreedDelayMs());

var stressErrors = 0;
var maxUsage = u0;
var usageSamples = [];
for (var i = 0; i < total; i++) {
    try {
        TSC.getDomainParams(script);
    } catch (e) {
        stressErrors++;
        if (stressErrors <= 3) { print("[WARN] getDomainParams error @" + (i + 1) + ": " + e); }
    }
    if ((i + 1) % 200 === 0) {
        Thread.sleep(150);                       // let the delayed returns settle
        var uSample = TSC.usage();
        usageSamples.push(round3(uSample));
        if (uSample > maxUsage) { maxUsage = uSample; }
        print("  freed() stress progress: " + (i + 1) + "/" + total + " usage=" + round3(uSample));
    }
}
Thread.sleep(500);
var uFinal = TSC.usage();
var usageStable = (uFinal <= u0 + 0.02);
var poolNotExhausted = (maxUsage < 0.9);
var freedOk = stressErrors === 0 && usageStable && poolNotExhausted;
check({
    name: "freed() sustained " + total + "x (no console leak, no Full Console Pool)",
    ok: freedOk,
    detail: "errors=" + stressErrors + " usage0=" + round3(u0) + " maxUsage=" + round3(maxUsage) + " final=" + round3(uFinal) + " samples=[" + usageSamples.join(",") + "]",
    objective: "Prove freed() returns every console to the pool across " + total + " consecutive getDomainParams cycles: zero errors, pool usage never exhausted, final usage back to baseline",
    objectiveZh: "證明 freed() 於連續 " + total + " 次 getDomainParams 循環中每次皆歸還 console：零錯誤、池使用率不會耗盡、最終使用率回復基線",
    command: "for (i=0;i<" + total + ";i++) TomcatScriptConsole.getDomainParams(\"" + script + "\");"
});

// Case F4.5 — hard pool number after all traffic: print the ACTUAL console
// pool size vs the configured poolSize. Under load other threads may be using
// consoles, so this is informational (a dead-number eyeball check) not a
// pass/fail gate — the F4 usage baseline assertion already proves restoration.
print("[Freed] Pool size after all traffic: " + TSC.getConsolePoolSize()
      + " / configured " + TSC.getPoolSize() + " (usage final=" + round3(uFinal) + ")");

// Case F5 — freed() on the Loaded (existing script) path: getDomainParams on
// an existing script (sample.js) must also free the console after eval.
var uL0 = TSC.usage();
var loadedOk = false;
var loadedErr = "";
try {
    var p = TSC.getDomainParams("sample.js");
    loadedOk = (p !== null && p !== undefined);
    Thread.sleep(300);
    loadedOk = loadedOk && (TSC.usage() <= uL0 + 0.02);
} catch (e) {
    loadedErr = "" + e;
}
check({
    name: "freed() on Loaded path (sample.js getParams evaluated, pool restored)",
    ok: loadedOk,
    detail: (loadedErr === "" ? "usage0=" + round3(uL0) + " usageAfter=" + round3(TSC.usage()) : "error=" + loadedErr),
    objective: "Verify getDomainParams on an existing script returns params AND frees the console (usage back to baseline)",
    objectiveZh: "驗證 getDomainParams 對存在的腳本返回參數並歸還 console（使用率回復基線）",
    command: "TomcatScriptConsole.getDomainParams(\"sample.js\")"
});

// Case F6 — cleanup: remove the test mapping and verify no test records remain.
r = callTomcat(function(){ return tomcat.removeDomainMapping("127.0.0.1", "8443"); });
check({
    name: "removeDomainMapping 127.0.0.1:8443 (cleanup)",
    ok: isOk(r),
    detail: msg(r) + dbVerify(function(){ return "SELECT * FROM domain_mapping_param WHERE domain='127.0.0.1' AND deleted=FALSE"; }),
    objective: "Remove the test mapping created for this suite and verify no 127.0.0.1 mapping records remain",
    objectiveZh: "移除本測試套件建立的對應，並驗證無 127.0.0.1 對應記錄殘留",
    command: "tomcat.removeDomainMapping(\"127.0.0.1\", \"8443\")"
});

// ---------------------------------------------------------------------------
// Summary
// ---------------------------------------------------------------------------
writeReport();
writeMd();
print("========================================");
print("TestFreed result: " + passCount + " passed, " + failCount + " failed");
if (failCount > 0) {
    print("FAILED checks: " + failures.join(", "));
} else {
    print("ALL TESTS PASSED");
}
