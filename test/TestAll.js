// ============================================================================
// JsPlus-Tomcat TestAll — unified console entry for the 6 test suites
// ----------------------------------------------------------------------------
// Run      : Inside the JsPlus console (server already running):
//              Console.eval(System.getenv('JS_PLUS_PATH') + '/../test/TestAll.js')
//            No bat/sh launcher needed — each suite loads the shared harness
//            (TestBase.js) itself and runs against the live console's Tomcat,
//            H2 data source and global `tomcat` object.
// Output   : Runs, in order:
//              1) TestDomainProxy.js    -> docs/latest/report/TestDomainProxy-Report.html
//              2) TestSecurityFilter.js -> docs/latest/report/TestSecurityFilter-Report.html
//              3) TestIpFilter.js       -> docs/latest/report/TestIpFilter-Report.html
//              4) TestIpAttackDefense.js-> docs/latest/report/TestIpAttackDefense-Report.html
//              5) TestTomcatServer.js    -> docs/latest/report/TestTomcatServer-Report.html
//              6) TestAuthLockout.js     -> docs/latest/report/TestAuthLockout-Report.html
//              7) TestFreed.js           -> docs/latest/report/TestFreed-Report.html
//              8) TestServerLog.js       -> docs/latest/report/TestServerLog-Report.html
//            (each is also mirrored to the legacy test_reports/ folder)
//            This entry point then prints an aggregate pass/fail summary.
// NOTE     : Each suite calls beginTest() and reports its own counters, so
//            TestAll captures the counters right after each load() completes.
//            The suites NEVER call System.exit() (that would shut down the
//            ghost console); the aggregate summary is printed instead.
// PREREQ   : See the per-suite headers and README Automated Testing section:
//              SecurityFilter.cacheSeconds=0
//              IpFilter.cacheSeconds=0
//              IpAttackDefense.ruleCacheSeconds=0
// ============================================================================

var System = Java.type("java.lang.System");
var Thread = Java.type("java.lang.Thread");
var basePath = System.getProperty("JS_PLUS_PATH");
var testStart = Date.now();

// Standalone (-Env script -Task) bootstrap: when no global `tomcat` exists,
// boot TomcatEmbed in THIS JVM (mirror of script/StartTomcat.js) so the suites
// see the global tomcat object and TomcatScriptConsole.MasterConsole
// (initialised by TomcatServer.initConsole() during start, before tomcat.start()).
// In the live ghost console `tomcat` already exists and this block is skipped.
var BOOTSTRAPPED = false;
if (typeof tomcat === "undefined") {
    var OriginConsole = Console;
    var TomcatConsole = Console.newBuilder();
    TomcatConsole.getContext().put("OriginConsole", OriginConsole);
    TomcatConsole.eval(basePath + '/script/TomcatEmbed.js');
    BOOTSTRAPPED = true;
    try {
        tomcat.start();
    } catch (e) {
        print("[WARN] tomcat.start(): " + e);
    }
}

// Wait for the embedded Tomcat (port 8080) to come up before running suites.
function waitForPort(port, timeoutSec) {
    var Socket = Java.type("java.net.Socket");
    var InetSocketAddress = Java.type("java.net.InetSocketAddress");
    var deadline = Date.now() + timeoutSec * 1000;
    while (Date.now() < deadline) {
        var s = null;
        try {
            s = new Socket();
            s.connect(new InetSocketAddress("127.0.0.1", port), 1000);
            s.close();
            return true;
        } catch (e) {
            try { if (s !== null) s.close(); } catch (e2) {}
            Thread.sleep(1000);
        }
    }
    return false;
}

// Read the test-critical lines of tomcat.properties for the report snapshot —
// environment drift (a missing cacheSeconds=0 / enable flag) becomes visible
// in every report instead of silently flipping the live filter checks.
function readConfigSnapshot() {
    var lines = [];
    try {
        var Files = Java.type("java.nio.file.Files");
        var Paths = Java.type("java.nio.file.Paths");
        var propsPath = basePath + "config/tomcat.properties";
        var content = Files.readString(Paths.get(propsPath));
        var arr = content.split("\n");
        for (var i = 0; i < arr.length; i++) {
            var ln = arr[i].trim();
            if (ln.length === 0 || ln.charAt(0) === "#") {
                continue;
            }
            if (ln.indexOf("SecurityFilter.") === 0 || ln.indexOf("IpFilter.") === 0
                || ln.indexOf("IpAttackDefense.") === 0 || ln.indexOf("DomainProxy.") === 0
                || ln.indexOf("TomcatScriptConsole.") === 0 || ln.indexOf("SSL.") === 0
                || ln.indexOf("port=") === 0 || ln.indexOf("sslPort=") === 0) {
                lines.push(ln);
            }
        }
    } catch (e) {
        lines.push("(cannot read tomcat.properties: " + e + ")");
    }
    return lines;
}

// Config self-check: the live filter checks read filter state immediately, so
// a missing enable / cacheSeconds=0 key silently makes them fail — verify the
// keys exist up front and surface the result in the console + report.
function checkTomcatConfig() {
    var required = [
        ["SecurityFilter.cacheSeconds", "0"],
        ["IpFilter.enable", "true"],
        ["IpFilter.cacheSeconds", "0"],
        ["IpAttackDefense.enable", "true"],
        ["IpAttackDefense.ruleCacheSeconds", "0"],
        ["DomainProxy.enable", "true"]
    ];
    var content = "";
    var propsPath = basePath + "config/tomcat.properties";
    try {
        var Files = Java.type("java.nio.file.Files");
        var Paths = Java.type("java.nio.file.Paths");
        content = Files.readString(Paths.get(propsPath));
        print("[Config] reading " + propsPath + " (" + content.split("\n").length + " lines)");
    } catch (e) {
        return { ok: false, missing: ["<cannot read tomcat.properties: " + e + ">"] };
    }
    var missing = [];
    // Line-by-line comparison (no JS RegExp: GraalVM 25 without the regex
    // polyglot language throws "No language for id regex found").
    var lines = content.split("\n");
    for (var i = 0; i < required.length; i++) {
        var key = required[i][0];
        var val = required[i][1];
        var found = false;
        for (var j = 0; j < lines.length; j++) {
            var ln = lines[j];
            if (ln.length > 0 && ln.charAt(ln.length - 1) === "\r") {
                ln = ln.substring(0, ln.length - 1);
            }
            ln = ln.trim();
            if (ln.length === 0 || ln.charAt(0) === "#") {
                continue;
            }
            var eq = ln.indexOf("=");
            if (eq < 0) {
                continue;
            }
            var k = ln.substring(0, eq).trim();
            var v = ln.substring(eq + 1).trim();
            if (k === key && v === val) {
                found = true;
                break;
            }
        }
        if (!found) {
            missing.push(key + "=" + val);
        }
    }
    if (missing.length > 0) {
        print("[Config] DEBUG missing=" + missing.join(", "));
        print("[Config] DEBUG file=" + propsPath + " lines=" + lines.length + " bytes=" + content.length);
        var shown = 0;
        for (var j = 0; j < lines.length && shown < 20; j++) {
            var t = lines[j];
            if (t.length > 0 && t.charAt(t.length - 1) === "\r") {
                t = t.substring(0, t.length - 1);
            }
            var tt = t.trim();
            if (tt.length === 0 || tt.charAt(0) === "#") {
                continue;
            }
            print("[Config] DEBUG |" + tt + "|");
            shown++;
        }
    }
    return { ok: missing.length === 0, missing: missing };
}

try {
    if (basePath === null || basePath === undefined || basePath.length === 0) {
        throw "JS_PLUS_PATH is not set — cannot locate the test suites";
    }
} catch (e) {
    print("[ERROR] " + e);
    throw e;
}

print("=== JsPlus-Tomcat TestAll (unified entry) ===");
print("JS_PLUS_PATH: " + System.getProperty("JS_PLUS_PATH"));
print("Suites       : TestDomainProxy -> TestSecurityFilter -> TestIpFilter -> TestIpAttackDefense -> TestTomcatServer -> TestAuthLockout -> TestFreed -> TestServerLog");

var suites = [
    { file: "TestDomainProxy.js",      title: "TestDomainProxy" },
    { file: "TestSecurityFilter.js",   title: "TestSecurityFilter" },
    { file: "TestIpFilter.js",         title: "TestIpFilter" },
    { file: "TestIpAttackDefense.js",  title: "TestIpAttackDefense" },
    { file: "TestTomcatServer.js",     title: "TestTomcatServer" },
    { file: "TestAuthLockout.js",      title: "TestAuthLockout" },
    { file: "TestFreed.js",            title: "TestFreed" },
    { file: "TestServerLog.js",        title: "TestServerLog" }
];

var totalPass = 0;
var totalFail = 0;
var allFailures = [];
var suiteResults = [];

if (BOOTSTRAPPED) {
    print("Waiting for embedded Tomcat :8080...");
    if (!waitForPort(8080, 90)) {
        print("[WARN] Tomcat :8080 did not become ready within 90s - continuing (CRUD checks still work).");
    } else {
        print("Tomcat :8080 ready.");
    }
}

// ---- Config self-check (fail fast & visibly) ----
var cfgCheck = checkTomcatConfig();
var cfgLines = readConfigSnapshot();
print("");
print("=== Config self-check (tomcat.properties) ===");
if (cfgCheck.ok) {
    totalPass++;
    print("[PASS] filter config present (SecurityFilter/IpFilter/IpAttackDefense/DomainProxy)");
} else {
    totalFail++;
    allFailures.push("Config self-check - tomcat.properties missing: " + cfgCheck.missing.join(", "));
    print("[FAIL] filter config -> missing: " + cfgCheck.missing.join(", "));
}

// NOTE: loop variable must NOT be named `i`/`j` — suites load into the same
// global scope and their top-level `var i` (e.g. TestFreed's stress loop)
// overwrites it, silently skipping the remaining suites.
for (var suiteIdx = 0; suiteIdx < suites.length; suiteIdx++) {
    var s = suites[suiteIdx];
    print("");
    print("===== Running " + s.title + " =====");
    load(basePath + "../test/" + s.file);
    // Capture the suite's own counters immediately after it finishes
    // (the next suite's beginTest() would reset them).
    totalPass += passCount;
    totalFail += failCount;
    suiteResults.push({ title: s.title, pass: passCount, fail: failCount });
    for (var fl = 0; fl < failures.length; fl++) {
        allFailures.push(s.title + " - " + failures[fl]);
    }
    print(s.title + " => " + passCount + " passed, " + failCount + " failed");
}

print("");
print("========================================");
print("TestAll result: " + totalPass + " passed, " + totalFail + " failed");
if (totalFail > 0) {
    print("FAILED checks:");
    for (var k = 0; k < allFailures.length; k++) {
        print("  " + allFailures[k]);
    }
} else {
    print("ALL TESTS PASSED");
}

// Aggregate markdown summary into docs/latest/report/TestAll-Report.md
// (JsPlus-Core docs layout) plus a legacy copy under test_reports/.
try {
    var File = Java.type("java.io.File");
    var primaryDir = new File(basePath + "../docs/latest/report");
    if (!primaryDir.exists()) {
        primaryDir.mkdirs();
    }
    var legacyDir = new File(basePath + "../test_reports");
    if (!legacyDir.exists()) {
        legacyDir.mkdirs();
    }
    var md = "# JsPlus-Tomcat TestAll Report\n\n";
    md += "Generated: " + new Date().toString() + "\n\n";
    md += "**" + totalPass + " passed / " + totalFail + " failed**\n\n";
    md += "## Config Self-Check\n\n"
        + (totalFail === 0 ? "- PASS: filter config present (SecurityFilter/IpFilter/IpAttackDefense/DomainProxy)"
                           : "- FAIL: missing " + (cfgCheck.missing || []).join(", ")) + "\n\n";
    if ((cfgLines || []).length > 0) {
        md += "## Config Snapshot (tomcat.properties)\n\n```\n" + (cfgLines || []).join("\n") + "\n```\n\n";
    }
    md += "| Suite | Passed | Failed |\n";
    md += "| --- | --- | --- |\n";
    for (var m = 0; m < suiteResults.length; m++) {
        md += "| " + suiteResults[m].title + " | " + suiteResults[m].pass + " | " + suiteResults[m].fail + " |\n";
    }
    var reportPath = basePath + "../docs/latest/report/TestAll-Report.md";
    var report = new DiskFile(reportPath);
    report.write(md, "UTF-8");
    // Legacy copy (kept for existing CI artifact paths / local shortcuts).
    var legacyMd = new DiskFile(basePath + "../test_reports/TestAll-Report.md");
    legacyMd.write(md, "UTF-8");
    print("Markdown report written to " + reportPath);

    // Aggregate HTML summary into docs/latest/report/TestAll-Report.html
    // (plus the legacy test_reports/ copy).
    var html = "<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n<meta charset=\"UTF-8\">\n<title>JsPlus-Tomcat TestAll Report</title>\n"
        + "<style>\n"
        + "  body { font-family: Consolas, 'Courier New', monospace; background: #f5f6f8; margin: 24px; }\n"
        + "  h1 { font-size: 20px; color: #24292f; }\n"
        + "  .meta { color: #57606a; margin-bottom: 12px; }\n"
        + "  .summary-pass { display: inline-block; padding: 8px 16px; border-radius: 6px; background: #e6ffec; color: #1a7f37; font-weight: bold; margin-bottom: 16px; }\n"
        + "  .summary-fail { display: inline-block; padding: 8px 16px; border-radius: 6px; background: #ffebe9; color: #b42318; font-weight: bold; margin-bottom: 16px; }\n"
        + "  table { border-collapse: collapse; width: 100%; background: #fff; }\n"
        + "  th { background: #eaeef2; text-align: left; padding: 8px 10px; border: 1px solid #d0d7de; }\n"
        + "  td { padding: 6px 10px; border: 1px solid #d0d7de; vertical-align: top; }\n"
        + "  pre.cfg { background: #fff; border: 1px solid #d0d7de; padding: 10px; font-size: 12px; overflow-x: auto; }\n"
        + "</style>\n</head>\n<body>\n"
        + "<h1>JsPlus-Tomcat TestAll Report</h1>\n"
        + "<div class=\"meta\">Generated: " + new Date().toString() + "</div>\n"
        + "<div class=\"" + (totalFail === 0 ? "summary-pass" : "summary-fail") + "\">" + totalPass + " passed / " + totalFail + " failed</div>\n"
        + "<h2>Config Self-Check</h2>\n"
        + "<div class=\"" + (totalFail === 0 ? "summary-pass" : "summary-fail") + "\">"
        + (totalFail === 0 ? "PASS: filter config present (SecurityFilter/IpFilter/IpAttackDefense/DomainProxy)"
                           : "FAIL: missing " + (cfgCheck.missing || []).join(", ")) + "</div>\n";
    if (cfgLines.length > 0) {
        html += "<h2>Config Snapshot (tomcat.properties)</h2>\n<pre class=\"cfg\">";
        for (var cl = 0; cl < cfgLines.length; cl++) {
            html += Java.type("java.lang.String").valueOf(cfgLines[cl])
                .replaceAll("&", "&amp;").replaceAll("<", "&lt;").replaceAll(">", "&gt;") + "\n";
        }
        html += "</pre>\n";
    }
    html += "<table>\n<tr><th>Suite</th><th>Passed</th><th>Failed</th></tr>\n";
    for (var m2 = 0; m2 < suiteResults.length; m2++) {
        html += "<tr><td>" + suiteResults[m2].title + "</td><td>" + suiteResults[m2].pass + "</td><td>" + suiteResults[m2].fail + "</td></tr>\n";
    }
    html += "</table>\n</body>\n</html>\n";
    var htmlPath = basePath + "../docs/latest/report/TestAll-Report.html";
    var report2 = new DiskFile(htmlPath);
    report2.write(html, "UTF-8");
    var legacyHtml = new DiskFile(basePath + "../test_reports/TestAll-Report.html");
    legacyHtml.write(html, "UTF-8");
    print("HTML report written to " + htmlPath);

    // CI summary (from TestBase.writeCiSummary) — compact Markdown that the
    // workflow appends to $GITHUB_STEP_SUMMARY (visible on the Actions run page)
    // and that ships inside the uploaded docs/latest/report/ artifact (also
    // archived as docs/reports/<version>.jar).
    load(basePath + "../test/TestBase.js");
    writeCiSummary({
        title: "JsPlus-Tomcat CI Summary",
        reportName: "TestAll-CI-Summary.md",
        pass: totalPass,
        fail: totalFail,
        failures: allFailures,
        suiteTable: suiteResults,
        configOk: totalFail === 0,
        configMissing: cfgCheck.missing,
        configLines: cfgLines,
        durationMs: Date.now() - testStart
    });
} catch (e) {
    print("[WARN] Could not write TestAll report: " + e);
    if (e !== null && e !== undefined && e.stack !== undefined && e.stack !== null) {
        print("[WARN] stack: " + e.stack);
    }
}

// Stop the embedded Tomcat started by this bootstrap (harmless when skipped).
// NOTE: tomcat.stop() is deliberately NOT called here. In Task mode the JVM
// exits right below via Runtime.halt(0), which terminates the embedded Tomcat
// together with this process. Calling stop() first has been observed to hang
// (e.g. while IpAttackDefense's background DB batch retries a dead H2
// connection), which would stall TestAll.bat at Step 2 and never reach the
// load-test step.
// Script/Task mode (TestAll.bat/.sh): exit this JVM directly WITHOUT
// writing the shared exit.tmp signal file — ScriptConsole.exit() writes
// <JS_PLUS_PATH>/.tmp/exit.tmp, which every other JsPlus JVM watching it
// (including the H2 ghost started by BG_StartH2.bat / StartH2.js) would
// detect and shut down together with this JVM. Runtime.halt(0) only
// terminates this process (no shutdown hooks), leaving H2 running for
// TestAll's load-test step.
// Interactive console eval keeps BOOTSTRAPPED=false and must NOT kill the console.
// TestAll.bat/.sh additionally export TESTALL_TASK=1 so this file hard-halts
// even when jsplus auto-bootstraps a global `tomcat` in this JVM (which makes
// `typeof tomcat !== "undefined"` and BOOTSTRAPPED=false) — without the halt,
// the non-daemon TomcatServer await thread keeps the JVM alive and Step 3
// (load test) would never start.
var SysOut = Java.type("java.lang.System").out;
var System2 = Java.type("java.lang.System");
var inTaskMode = System2.getenv("TESTALL_TASK") !== null && System2.getenv("TESTALL_TASK") !== undefined;
SysOut.println("[TestAll] END-OF-SCRIPT reached. BOOTSTRAPPED=" + BOOTSTRAPPED + " TASK_MODE=" + inTaskMode);
SysOut.flush();
if (BOOTSTRAPPED || inTaskMode) {
    SysOut.println("[TestAll] halting now (halt(0))" + (inTaskMode ? " [task mode]" : " [bootstrapped]"));
    SysOut.flush();
    // Runtime.halt(0) instead of System.exit(0): System.exit runs shutdown
    // hooks, and Tomcat's hook (stop -> close datasource) has been observed to
    // hang while the datasource pool retries a dead H2 connection (all pool
    // threads BLOCKED on the pool monitor lock), leaving this JVM alive and
    // stalling TestAll.bat at Step 2 forever. halt(0) terminates this JVM
    // immediately without hooks; the embedded Tomcat dies with it, and the H2
    // ghost (a separate JVM) is left for the load-test step to reuse/restart.
    Java.type("java.lang.Runtime").getRuntime().halt(0);
}
