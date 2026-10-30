// ============================================================================
// JsPlus-Tomcat TestBase — shared test harness for the console-mode test suites
// ----------------------------------------------------------------------------
// Provides the common plumbing used by the console-mode API test suite:
//   - Java type bindings (TomcatServer, SecurityFilter, IpFilter, ...)
//   - H2 column-name case fix (COLUMN_NAME_CASE_MODE = LOWER)
//   - pass/fail counters + beginTest() reset
//   - check({name, ok, detail, objective}), isOk(), msg() helpers
//   - runCmd() / curlStatus() process helpers for the live curl checks
//   - dbVerify() — append a follow-up DB query result to the detail line
//   - writeReport() -> docs/latest/report/<REPORT_NAME>.html (JsPlus-Core
//                      docs layout) + a legacy copy under test_reports/
//   - writeMd()     -> docs/latest/report/<REPORT_NAME>.md + legacy copy
//   - writeCiSummary() -> docs/latest/report/TestAll-CI-Summary.md + copy
//   - writeReportFile() — the dual-location writer used by all of the above
// Loaded by each suite (TestDomainProxy.js / TestSecurityFilter.js / ...):
//   load(System.getProperty("JS_PLUS_PATH") + "../test/TestBase.js")
// NOTE: Re-loading this file is harmless (var redeclaration is legal in JS);
// always call beginTest() at the start of each run to reset counters.
// ============================================================================

var System = Java.type("java.lang.System");
var Thread = Java.type("java.lang.Thread");
var HashMap = Java.type("java.util.HashMap");
var TomcatServer = Java.type("com.killcoding.jsplus.tomcat.TomcatServer");
var SecurityFilter = Java.type("com.killcoding.jsplus.tomcat.SecurityFilter");
var IpFilter = Java.type("com.killcoding.jsplus.tomcat.IpFilter");
var IpAttackDefense = Java.type("com.killcoding.jsplus.tomcat.IpAttackDefense");
var DomainProxy = Java.type("com.killcoding.jsplus.tomcat.DomainProxy");
var CacheDriverExecutor = Java.type("com.killcoding.datasource.CacheDriverExecutor");
var Db = Java.type("com.killcoding.jsplus.tomcat.Db");
var DiskFile = Java.type("com.killcoding.file.DiskFile");
var ProcessBuilder = Java.type("java.lang.ProcessBuilder");
var BufferedReader = Java.type("java.io.BufferedReader");
var InputStreamReader = Java.type("java.io.InputStreamReader");
var StringBuilder = Java.type("java.lang.StringBuilder");

// The ghost console already sets this to LOWER; set it again defensively
// (via reflection — a plain JS field assignment on a Java static field is
// silently ignored) so the suite also behaves if it is ever run headless.
var Field = Java.type("java.lang.reflect.Field");
var caseModeField = CacheDriverExecutor.class.getField("COLUMN_NAME_CASE_MODE");
// NOTE: access inherited static constant via the parent class name is unreliable
// in GraalVM JS (returns null), so pass the literal value instead.
caseModeField.set(null, "LOWER");

// Console mode = the suite is running inside the live ghost console, where the
// global `tomcat` object exists and System.exit() must never be called.
var CONSOLE_MODE = (typeof tomcat !== "undefined");

// Curl output sink: Windows uses the NUL device, POSIX uses /dev/null — the
// suites share this via NULL_DEVICE so "-o" never writes a stray file on Linux.
var OS_NAME = (System.getProperty("os.name") || "").toLowerCase();
var NULL_DEVICE = (OS_NAME.indexOf("win") >= 0) ? "NUL" : "/dev/null";

var suiteStart = Date.now();
var passCount = 0;
var failCount = 0;
var failures = [];
var results = [];
// Structured dbVerify outcome {sql, columns, rows} filled by dbVerify() and
// consumed by check() into the current result row (for the HTML table render).
var dbvResult = null;

// Reset counters — call at the start of every test run (from each suite).
function beginTest() {
    suiteStart = Date.now();
    passCount = 0;
    failCount = 0;
    failures = [];
    results = [];
    dbvResult = null;
}

function escapeHtml(s) {
    if (s === null || s === undefined) {
        return "";
    }
    return Java.type("java.lang.String").valueOf(s)
        .replaceAll("&", "&amp;")
        .replaceAll("<", "&lt;")
        .replaceAll(">", "&gt;")
        .replaceAll('"', "&quot;");
}

// Markdown escaping for table cells: escape pipes (column separator) and
// backslashes so embedded values never break the report table; newlines
// become " / " (raw HTML like <br> is avoided — GitHub's table renderer is
// unreliable with inline HTML inside cells).
function escapeMd(s) {
    if (s === null || s === undefined) {
        return "";
    }
    return Java.type("java.lang.String").valueOf(s)
        .replaceAll("\\", "\\\\")
        .replaceAll("|", "\\|")
        .replaceAll("\r\n", " / ")
        .replaceAll("\r", " / ")
        .replaceAll("\n", " / ");
}

function check(params) {
    var name = params.name;
    var ok = params.ok;
    var detail = params.detail;
    var objective = params.objective;
    var objectiveZh = params.objectiveZh;
    var command = params.command;
    var caseId = params.caseId;
    var d = (detail === null || detail === undefined) ? (ok ? "" : "unexpected result") : ("" + detail);
    // Long messages (e.g. queryIpRequestLogs listing every row) would flood the
    // console; truncate the console line but keep the full detail in the HTML
    // report (results[] below stores d untruncated).
    var line = (ok ? "[PASS] " : "[FAIL] ") + name + (d.length > 0 ? " -> " + d : "");
    if (line.length > 500) {
        line = line.substring(0, 500) + " ... (truncated, see HTML report)";
    }
    if (ok) {
        passCount++;
    } else {
        failCount++;
        failures.push(name);
    }
    print(line);
    results.push({
        caseId: (caseId === null || caseId === undefined) ? "" : ("" + caseId),
        name: name,
        ok: ok,
        detail: d,
        objective: (objective === null || objective === undefined) ? "" : ("" + objective),
        objectiveZh: (objectiveZh === null || objectiveZh === undefined) ? "" : ("" + objectiveZh),
        command: (command === null || command === undefined) ? "" : ("" + command),
        dbv: dbvResult
    });
    dbvResult = null;
}

function isOk(res) {
    return res !== null && res !== undefined && res.code === 1;
}

// Public API calls must go through the TomcatEmbed console entry — the global
// `tomcat` object exposed by JsPlus/script/TomcatEmbed.js — never by touching
// the module classes (DomainProxy/SecurityFilter/IpFilter/IpAttackDefense)
// directly. TomcatEmbed returns the result message as a string (it prints the
// message and drops the code map), so callTomcat wraps the string back into
// {code, msg} semantics: code=1 unless the message is empty or carries an
// ERROR/EXCEPTION marker. Exceptions are caught and reported as code=0.
function callTomcat(fn) {
    var msg = "";
    var code = 1;
    try {
        msg = fn();
    } catch (e) {
        msg = "EXCEPTION: " + e;
        code = 0;
    }
    if (msg === null || msg === undefined) {
        msg = "";
        code = 0;
    }
    msg = "" + msg;
    if (msg.indexOf("ERROR") >= 0 || msg.indexOf("EXCEPTION") >= 0 || msg.indexOf("Failed") >= 0) {
        code = 0;
    }
    return { code: code, msg: msg };
}

function msg(res) {
    if (res === null || res === undefined) {
        return "";
    }
    if (typeof res === "object" && res.msg !== undefined && res.msg !== null) {
        return "" + res.msg;
    }
    return "" + res;
}

// Run an external process (e.g. curl) and return its combined stdout+stderr.
// Shared by all suites for the live HTTP checks; no check is attached here.
function runCmd(args) {
    var pb = new ProcessBuilder(args);
    pb.redirectErrorStream(true);
    var proc = pb.start();
    var reader = new BufferedReader(new InputStreamReader(proc.getInputStream()));
    var sb = new StringBuilder();
    var line = reader.readLine();
    while (line !== null) {
        sb.append(line).append("\n");
        line = reader.readLine();
    }
    proc.waitFor();
    return sb.toString();
}

// curl status-code helper (built on runCmd); suites use it for live HTTP
// checks (e.g. expect 200 / 401 / 403). A real browser User-Agent is sent
// because the upstream proxy targets (e.g. zh.wikipedia.org) rate-limit the
// default curl/8.x agent aggressively, which used to surface as flaky 429s
// in the live suites.
var CURL_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36";
function curlStatus(url) {
    return parseInt(runCmd(["curl", "-k", "-s", "--max-time", "10", "-o", NULL_DEVICE, "-w", "%{http_code}", "-A", CURL_USER_AGENT, url]).trim());
}

// Optional DB verification: run a native SQL SELECT through the project's
// standard CacheDriverExecutor.find() (the same data-access path the APIs
// use) after a check, so testers can verify the persisted table state
// directly. sqlFn must return a SELECT statement. The structured outcome
// {sql, columns, rows} (max 30 rows) is stored in dbvResult for writeReport
// to render as an HTML table; the returned inline text keeps the console
// output readable.
function dbVerify(sqlFn) {
    var cde = null;
    var sql;
    var result = { sql: "", columns: [], rows: [] };
    try {
        sql = sqlFn();
        cde = new CacheDriverExecutor(Db.getConnection());
        var list = cde.find(sql, new HashMap());
        var size = list.size();
        if (size > 0) {
            var keys = list.get(0).keySet().toArray();
            for (var k = 0; k < keys.length; k++) {
                result.columns.push(keys[k] + "");
            }
        }
        for (var i = 0; i < size && i < 30; i++) {
            var item = list.get(i);
            var row = [];
            for (var k = 0; k < result.columns.length; k++) {
                row.push(item.get(result.columns[k]) + "");
            }
            result.rows.push(row);
        }
        result.sql = sql;
        dbvResult = result;
        var sb = new StringBuilder();
        for (var i = 0; i < result.rows.length; i++) {
            sb.append((i > 0 ? "\n" : "") + "[" + result.rows[i].join(" ") + "]");
        }
        return "\n | dbVerify: " + sql + "\n   -> " + (result.rows.length > 0 ? sb.toString() : "(0 rows)");
    } catch (e) {
        result.sql = (sql !== undefined ? sql : "<sqlFn failed>");
        result.error = "" + e;
        dbvResult = result;
        return "\n | dbVerify: " + result.sql + "\n   -> (query failed) " + e;
    } finally {
        Db.closeQuietly(cde);
    }
}

// Read the test-critical lines of JsPlus/config/tomcat.properties so every
// report carries a config snapshot — environment drift (a missing
// cacheSeconds=0 / enable flag) becomes visible instead of silently flipping
// the live filter checks.
function configSnapshot() {
    var lines = [];
    try {
        var Files = Java.type("java.nio.file.Files");
        var Paths = Java.type("java.nio.file.Paths");
        var propsPath = System.getProperty("JS_PLUS_PATH") + "config/tomcat.properties";
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

// Writes one report file into both report locations (JsPlus-Core docs layout):
//   1) docs/latest/report/ — the standard browsable newest-reports site, and
//      the source folder that TestAll archives as docs/reports/<version>.jar;
//   2) test_reports/       — the legacy in-tree location, kept so existing
//      CI artifact paths and local shortcuts keep working.
// Returns the primary path written (docs/latest/report/...).
function writeReportFile(reportName, content) {
    var File = Java.type("java.io.File");
    var basePath = System.getProperty("JS_PLUS_PATH");
    var primaryPath = basePath + "../docs/latest/report/" + reportName;
    var legacyPath = basePath + "../test_reports/" + reportName;
    var primaryDir = new File(basePath + "../docs/latest/report");
    if (!primaryDir.exists()) {
        primaryDir.mkdirs();
    }
    var legacyDir = new File(basePath + "../test_reports");
    if (!legacyDir.exists()) {
        legacyDir.mkdirs();
    }
    var report = new DiskFile(primaryPath);
    report.write(content, "UTF-8");
    try {
        var legacy = new DiskFile(legacyPath);
        legacy.write(content, "UTF-8");
    } catch (e) {
        print("[WARN] Could not write legacy report copy: " + e);
    }
    return primaryPath;
}

function writeReport() {
    var durationMs = Date.now() - suiteStart;
    var summaryClass = failCount === 0 ? "summary-pass" : "summary-fail";
    // REPORT_TITLE may be set by the test suite (e.g. TestDomainProxy.js);
    // fall back to a generic title so TestBase works standalone too.
    var reportTitle = (typeof REPORT_TITLE !== "undefined" && REPORT_TITLE !== null && REPORT_TITLE.length > 0)
        ? REPORT_TITLE : "JsPlus-Tomcat Test Report";
    var rows = "";
    for (var i = 0; i < results.length; i++) {
        var cls = results[i].ok ? "pass" : "fail";
        var badge = results[i].ok ? "PASS" : "FAIL";
        var detail = escapeHtml(results[i].detail);
        var dbv = results[i].dbv;
        // If structured dbVerify data is available, render the SQL + result
        // rows as an HTML table inside the small yellow block; otherwise fall
        // back to the inline text block (query-failed cases keep the text).
        var dbvIdx = detail.indexOf(" | dbVerify: ");
        if (dbvIdx >= 0) {
            // Capture the inline dbVerify text before trimming the main detail.
            var dbvText = detail.substring(dbvIdx + 2).replaceAll("\n", "<br>");
            detail = detail.substring(0, dbvIdx);
            if (dbv !== null && dbv !== undefined && dbv.sql.length > 0) {
                var dbvHtml = "dbVerify: " + escapeHtml(dbv.sql) + "<br>";
                if (dbv.error !== undefined && dbv.error !== null && ("" + dbv.error).length > 0) {
                    dbvHtml += "(query failed) " + escapeHtml("" + dbv.error);
                } else if (dbv.rows.length === 0) {
                    dbvHtml += "(0 rows)";
                } else {
                    dbvHtml += "<table class=\"dbv-table\"><tr><th>row num</th>";
                    for (var c = 0; c < dbv.columns.length; c++) {
                        dbvHtml += "<th>" + escapeHtml(dbv.columns[c]) + "</th>";
                    }
                    dbvHtml += "</tr>";
                    for (var r = 0; r < dbv.rows.length; r++) {
                        dbvHtml += "<tr><td>" + (r + 1) + "</td>";
                        for (var c = 0; c < dbv.columns.length; c++) {
                            dbvHtml += "<td>" + escapeHtml(dbv.rows[r][c]) + "</td>";
                        }
                        dbvHtml += "</tr>";
                    }
                    dbvHtml += "</table>";
                }
                detail += " <span class=\"dbv\">" + dbvHtml + "</span>";
            } else {
                detail += " <span class=\"dbv\">" + dbvText + "</span>";
            }
        }
        var objective = escapeHtml(results[i].objective);
        var objectiveZh = escapeHtml(results[i].objectiveZh);
        var command = escapeHtml(results[i].command);
        // Sequential, per-row case id: first row = C1, second = C2, ...
        var caseCell = "C" + (i + 1);
        rows += "<tr class=\"" + cls + "\"><td>" + caseCell + "</td><td class=\"name\">" + escapeHtml(results[i].name)
              + (objective.length > 0 ? "<br><span class=\"obj\">" + objective + "</span>" : "")
              + (objectiveZh.length > 0 ? "<br><span class=\"objZh\">" + objectiveZh + "</span>" : "")
              + (command.length > 0 ? "<br><span class=\"cmd\">" + command + "</span>" : "")
              + "</td><td class=\"badge\">" + badge + "</td><td>" + detail + "</td></tr>\n";
    }
    var cfgLines = configSnapshot();
    var cfgSection = "";
    if (cfgLines.length > 0) {
        cfgSection = "<h2>Config Snapshot (tomcat.properties)</h2>\n<pre class=\"cfg\">" + escapeHtml(cfgLines.join("\n")) + "</pre>\n";
    }
    var html = "<!DOCTYPE html>\n"
        + "<html lang=\"en\">\n"
        + "<head>\n"
        + "<meta charset=\"UTF-8\">\n"
        + "<title>" + escapeHtml(reportTitle) + "</title>\n"
        + "<style>\n"
        + "  body { font-family: Consolas, 'Courier New', monospace; background: #f5f6f8; margin: 24px; }\n"
        + "  h1 { font-size: 20px; color: #24292f; }\n"
        + "  .meta { color: #57606a; margin-bottom: 12px; }\n"
        + "  .summary-pass { display: inline-block; padding: 8px 16px; border-radius: 6px; background: #e6ffec; color: #1a7f37; font-weight: bold; margin-bottom: 16px; }\n"
        + "  .summary-fail { display: inline-block; padding: 8px 16px; border-radius: 6px; background: #ffebe9; color: #b42318; font-weight: bold; margin-bottom: 16px; }\n"
        + "  table { border-collapse: collapse; width: 100%; background: #fff; }\n"
        + "  th { background: #eaeef2; text-align: left; padding: 8px 10px; border: 1px solid #d0d7de; }\n"
        + "  td { padding: 6px 10px; border: 1px solid #d0d7de; vertical-align: top; }\n"
        + "  tr.pass td { color: #24292f; }\n"
        + "  tr.pass td.badge { color: #1a7f37; font-weight: bold; }\n"
        + "  tr.fail { background: #ffebe9; }\n"
        + "  tr.fail td.name { color: #b42318; font-weight: bold; }\n"
        + "  tr.fail td.badge { color: #b42318; font-weight: bold; }\n"
        + "  td.name { width: 45%; }\n"
        + "  td.name span.obj { display: block; color: #57606a; font-size: 12px; margin-top: 2px; }\n"
        + "  td.name span.objZh { display: block; color: #8a5a00; font-size: 12px; margin-top: 2px; }\n"
        + "  td.name span.cmd { display: block; color: #8c959f; font-size: 11px; margin-top: 2px; font-family: Consolas, 'Courier New', monospace; word-break: break-all; }\n"
        + "  td .dbv { display: block; color: #9a6700; font-size: 12px; margin-top: 8px; word-break: break-all; }\n"
        + "  td .dbv table.dbv-table { border-collapse: collapse; width: auto; margin-top: 4px; font-size: 11px; }\n"
        + "  td .dbv table.dbv-table th { background: #fef3c7; color: #9a6700; padding: 2px 8px; border: 1px solid #d0d7de; text-align: left; font-weight: normal; }\n"
        + "  td .dbv table.dbv-table td { background: #fffdf5; color: #24292f; padding: 2px 8px; border: 1px solid #d0d7de; }\n"
        + "  td.badge { width: 70px; }\n"
        + "  pre.cfg { background: #fff; border: 1px solid #d0d7de; padding: 10px; font-size: 12px; overflow-x: auto; }\n"
        + "</style>\n"
        + "</head>\n"
        + "<body>\n"
        + "<h1>" + escapeHtml(reportTitle) + "</h1>\n"
        + "<div class=\"meta\">Generated: " + escapeHtml(new Date().toString()) + " &nbsp;|&nbsp; Duration: " + durationMs
        + " ms &nbsp;|&nbsp; Mode: " + (CONSOLE_MODE ? "console" : "standalone") + "</div>\n"
        + "<div class=\"" + summaryClass + "\">" + passCount + " passed / " + failCount + " failed</div>\n"
        + cfgSection
        + "<table>\n"
        + "<tr><th>Case Id</th><th>Test Objective</th><th>Result</th><th>Detail</th></tr>\n"
        + rows
        + "</table>\n"
        + "</body>\n"
        + "</html>\n";
    try {
        // REPORT_NAME may be set by the test suite (e.g. TestDomainProxy.js);
        // fall back to a generic name so TestBase works standalone too.
        var reportName = (typeof REPORT_NAME !== "undefined" && REPORT_NAME !== null && REPORT_NAME.length > 0)
            ? REPORT_NAME : "TestAll-Report.html";
        var reportPath = writeReportFile(reportName, html);
        print("Test report written to " + reportPath);
    } catch (e) {
        print("[WARN] Could not write test report: " + e);
    }
}

// Markdown test report — mirrors writeReport() but emits a GitHub-friendly
// Markdown file into docs/latest/report/<REPORT_NAME>.md (plus a legacy copy
// under test_reports/ via writeReportFile).
function writeMd() {
    var durationMs = Date.now() - suiteStart;
    var reportTitle = (typeof REPORT_TITLE !== "undefined" && REPORT_TITLE !== null && REPORT_TITLE.length > 0)
        ? REPORT_TITLE : "JsPlus-Tomcat Test Report";
    var md = "# " + escapeMd(reportTitle) + "\n\n";
    md += "Generated: " + escapeMd(new Date().toString()) + " | Duration: " + durationMs
        + " ms | Mode: " + (CONSOLE_MODE ? "console" : "standalone") + "\n\n";
    md += "**" + passCount + " passed / " + failCount + " failed**\n\n";
    var cfgLines = configSnapshot();
    if (cfgLines.length > 0) {
        md += "## Config Snapshot (tomcat.properties)\n\n```\n" + cfgLines.join("\n") + "\n```\n\n";
    }
    md += "| Case Id | Test Objective | Result | Detail |\n";
    md += "| --- | --- | --- | --- |\n";
    for (var i = 0; i < results.length; i++) {
        var badge = results[i].ok ? "**PASS**" : "**FAIL**";
        // Split the inline " | dbVerify: " appendix on the RAW detail text,
        // before any escaping (same split point as writeReport).
        var rawDetail = ("" + results[i].detail);
        var dbvIdx = rawDetail.indexOf(" | dbVerify: ");
        var detail = escapeMd(dbvIdx >= 0 ? rawDetail.substring(0, dbvIdx) : rawDetail);
        var dbv = results[i].dbv;
        if (dbvIdx >= 0) {
            var dbvText = rawDetail.substring(dbvIdx + 2);
            if (dbvText.length > 0) {
                if (detail.endsWith(" / ")) {
                    detail = detail.substring(0, detail.length - 3);
                }
                detail += " / " + escapeMd(dbvText);
            }
        }
        var nameCell = escapeMd(results[i].name)
            + (results[i].objective.length > 0 ? " — " + escapeMd(results[i].objective) : "")
            + (results[i].objectiveZh.length > 0 ? " — " + escapeMd(results[i].objectiveZh) : "")
            + (results[i].command.length > 0 ? " / `" + escapeMd(results[i].command) + "`" : "");
        md += "| C" + (i + 1) + " | " + nameCell + " | " + badge + " | " + detail + " |\n";
    }
    try {
        var reportName = (typeof REPORT_NAME !== "undefined" && REPORT_NAME !== null && REPORT_NAME.length > 0)
            ? REPORT_NAME : "TestAll-Report.html";
        if (reportName.indexOf(".html") >= 0) {
            reportName = reportName.substring(0, reportName.length - 5) + ".md";
        }
        var reportPath = writeReportFile(reportName, md);
        print("Markdown report written to " + reportPath);
    } catch (e) {
        print("[WARN] Could not write markdown report: " + e);
    }
}

// CI-oriented summary — writes a compact Markdown file into
// docs/latest/report/ (and a legacy copy into test_reports/) so the CI job
// can surface it after a run:
//   1) it is included in the uploaded docs/latest/report/ artifact
//      (downloadable) and archived as docs/reports/<version>.jar,
//   2) the workflow can append it to $GITHUB_STEP_SUMMARY so the GitHub
//      Actions run page shows the result without opening the artifact.
// Params (all optional):
//   title          — report heading (default "JsPlus-Tomcat CI Summary")
//   reportName     — output file name (default "TestAll-CI-Summary.md")
//   pass / fail    — counters (default: current passCount / failCount)
//   failures       — array of failed check names (default: current failures)
//   suiteTable     — [{title, pass, fail}] rendered as a Suites table
//   configOk       — true/false for the Config Self-Check section
//   configMissing  — array of missing keys (shown when configOk is false)
//   configLines    — config snapshot lines (default: configSnapshot())
//   note           — optional extra paragraph (e.g. environment notes)
//   durationMs     — override the duration (default: since suiteStart)
// Returns the Markdown text so callers can also print / post it (GitHub).
function writeCiSummary(params) {
    var p = params || {};
    var reportTitle = (p.title !== undefined && p.title !== null && p.title.length > 0)
        ? p.title : "JsPlus-Tomcat CI Summary";
    var pass = (p.pass !== undefined && p.pass !== null) ? p.pass : passCount;
    var fail = (p.fail !== undefined && p.fail !== null) ? p.fail : failCount;
    var failList = p.failures || failures;
    var cfgLines = p.configLines || configSnapshot();
    var note = p.note || "";
    var durationMs = (p.durationMs !== undefined && p.durationMs !== null)
        ? p.durationMs : (Date.now() - suiteStart);

    var md = "# " + escapeMd(reportTitle) + "\n\n";
    md += "Generated: " + escapeMd(new Date().toString()) + " | Duration: " + durationMs
        + " ms | Mode: " + (CONSOLE_MODE ? "console" : "standalone") + "\n\n";
    md += "## Result\n\n" + (fail === 0 ? ":white_check_mark:" : ":x:")
        + " **" + pass + " passed / " + fail + " failed**\n\n";
    if (p.configOk !== undefined && p.configOk !== null) {
        md += "## Config Self-Check\n\n" + (p.configOk ? ":white_check_mark: PASS"
            : ":x: FAIL - missing " + escapeMd((p.configMissing || []).join(", "))) + "\n\n";
    }
    if (p.suiteTable !== undefined && p.suiteTable !== null && p.suiteTable.length > 0) {
        md += "## Suites\n\n| Suite | Passed | Failed |\n| --- | --- | --- |\n";
        for (var i = 0; i < p.suiteTable.length; i++) {
            md += "| " + escapeMd("" + p.suiteTable[i].title) + " | " + p.suiteTable[i].pass
                + " | " + p.suiteTable[i].fail + " |\n";
        }
        md += "\n";
    }
    if (fail > 0 && failList.length > 0) {
        md += "## Failed Checks\n\n";
        for (var j = 0; j < failList.length; j++) {
            md += "- " + escapeMd("" + failList[j]) + "\n";
        }
        md += "\n";
    }
    if (note.length > 0) {
        md += "## Note\n\n" + escapeMd(note) + "\n\n";
    }
    if ((cfgLines || []).length > 0) {
        md += "## Config Snapshot (tomcat.properties)\n\n```\n" + (cfgLines || []).join("\n") + "\n```\n";
    }
    try {
        var reportName = (p.reportName !== undefined && p.reportName !== null && p.reportName.length > 0)
            ? p.reportName : "TestAll-CI-Summary.md";
        var reportPath = writeReportFile(reportName, md);
        print("CI summary written to " + reportPath);
    } catch (e) {
        print("[WARN] Could not write CI summary: " + e);
    }
    return md;
}
