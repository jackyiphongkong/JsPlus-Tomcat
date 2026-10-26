// ============================================================================
// JsPlus-Tomcat ServerLog Web Log Test Suite (console mode)
// ----------------------------------------------------------------------------
// Run      : Inside the JsPlus console (server already running):
//              Console.eval(<path to this file>);
//            No bat/sh launcher needed — the console environment provides the
//            running Tomcat, the H2 data source and the global `tomcat` object.
// Harness  : Shared plumbing (Java bindings, counters, check/isOk/msg and
//            writeReport) lives in TestBase.js and is loaded below.
// Output   : <PROJECT_ROOT>/test_reports/TestServerLog-Report.html (green PASS /
//            red FAIL per check), written with BaseFile (DiskFile). Each check
//            prints [PASS]/[FAIL].
// NOTE     : In console mode the suite NEVER calls System.exit() (that would
//            shut down the ghost console); it prints the summary instead.
//            Run only against a disposable/development database.
// Focus    : Prove the ServerLog web-log pipeline works end to end:
//              setParams/getParams round-trip, Enable gate, Type filter with
//              per-entry trim (C3 fix: "GET, POST" matches "POST"), CSV file
//              creation with header, row content, Allowed[] URI filter and
//              ParamMaxLength truncation. The original ServerLog config is
//              captured first and restored at the end.
// Env      : TESTWEBLOG_FOLDER  default <JS_PLUS_PATH>/../test_reports/weblog_test
//            (a scratch folder inside the project; deleted on cleanup)
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
var REPORT_NAME = "TestServerLog-Report.html";
var REPORT_TITLE = "JsPlus-Tomcat ServerLog Web Log Test Report";

print("=== JsPlus-Tomcat ServerLog Web Log Test ===");
print("JS_PLUS_PATH: " + System.getProperty("JS_PLUS_PATH"));
print("Mode        : " + (CONSOLE_MODE ? "console (existing server, no System.exit)" : "standalone"));

// Java bindings used by this suite.
var ServerLog = Java.type("com.killcoding.jsplus.tomcat.ServerLog");
var Thread = Java.type("java.lang.Thread");
var File = Java.type("java.io.File");
var Files = Java.type("java.nio.file.Files");
var Paths = Java.type("java.nio.file.Paths");
var StandardCharsets = Java.type("java.nio.charset.StandardCharsets");
var SimpleDateFormat = Java.type("java.text.SimpleDateFormat");
// NOTE: must NOT shadow the JS global `Date` (TestBase writeReport calls
// Date.now()); use an alias instead.
var JDate = Java.type("java.util.Date");

var r;

// Scratch folder + the exact CSV path ServerLog derives from the date.
var testFolder = System.getenv("TESTWEBLOG_FOLDER");
if (testFolder === null || testFolder === undefined || testFolder === "") {
    testFolder = basePath + "../test_reports/weblog_test";
}
var dateStr = new SimpleDateFormat("yyyyMMdd").format(new JDate());
var csvFile = testFolder + "/" + dateStr + ".csv";

function readCsvLines() {
    if (!new File(csvFile).exists()) { return null; }
    return Files.readAllLines(Paths.get(csvFile), StandardCharsets.UTF_8);
}

function csvContains(substr) {
    var lines = readCsvLines();
    if (lines === null) { return false; }
    for (var i = 1; i < lines.size(); i++) {
        if (("" + lines.get(i)).indexOf(substr) >= 0) { return true; }
    }
    return false;
}

// The CSV flush runs on the CacheArray background thread with the LogTimer
// bound when the filter was first created (the running server's config, not
// our test LogTimer). Poll instead of sleeping: return once the file carries
// at least `minLines` data rows (or timeout).
function waitForCsv(minLines, seconds) {
    var deadline = Date.now() + seconds * 1000;
    while (Date.now() < deadline) {
        var lines = readCsvLines();
        if (lines !== null && lines.size() > minLines) { return lines; }
        Thread.sleep(300);
    }
    return readCsvLines();
}
function waitForCsvContains(substr, seconds) {
    var deadline = Date.now() + seconds * 1000;
    while (Date.now() < deadline) {
        if (csvContains(substr)) { return true; }
        Thread.sleep(300);
    }
    return csvContains(substr);
}

// Recursive delete for the scratch folder (Java 8 Files.walk is awkward in JS).
function deleteDir(f) {
    if (f.isDirectory()) {
        var children = f.listFiles();
        for (var i = 0; i < children.length; i++) { deleteDir(children[i]); }
    }
    f.delete();
}

// Case S1 — harness ready: the ServerLog class must resolve in the console.
check({
    name: "console environment ready (ServerLog resolvable)",
    ok: (typeof ServerLog !== "undefined"),
    detail: "com.killcoding.jsplus.tomcat.ServerLog loaded; mode=" + (CONSOLE_MODE ? "console" : "standalone"),
    objective: "Ensure the suite runs inside the JsPlus console and the ServerLog class is reachable",
    objectiveZh: "確保測試套件於 JsPlus 主控台內執行且 ServerLog 類別可存取",
    command: "Java.type(\"com.killcoding.jsplus.tomcat.ServerLog\")"
});

// Reset the shared static CacheArray so this suite starts with a fresh filter.
// In console mode the running server may already have bound one with its own
// LogTimer (tomcat.properties WebLog.Enable=true), whose flush thread can be
// idle — that made the CSV assertions flaky. Standalone mode has no such
// interference; the reset is harmless either way (the cache is recreated on
// the next writeWebLog call).
try {
    var JClass = Java.type("java.lang.Class");
    var wlcClass = JClass.forName("com.killcoding.jsplus.tomcat.ServerLog");
    var wlcField = wlcClass.getDeclaredField("webLogCacheArray");
    wlcField.setAccessible(true);
    wlcField.set(null, null);
    print("[ServerLog] webLogCacheArray reset to null (fresh filter for this suite)");
} catch (e) {
    print("[WARN] could not reset ServerLog.webLogCacheArray: " + e);
}

// Case S2 — capture the original config, then set a self-contained test
// config (Enable=true, Type with a trailing-space comma, short LogTimer so
// the CSV flush is quick). Verify the round-trip via getParams().
var origParams = ServerLog.getParams();   // may be null if never configured
var testParams = new HashMap();
testParams.put("Enable", true);
testParams.put("Type", "GET, POST");
testParams.put("Folder", testFolder);
testParams.put("LogTimer", 300);                 // ms; first writeWebLog binds the filter
testParams.put("ParamMaxLength", 20);
testParams.put("ArchiveDays", 31);
testParams.put("MaxFileSize", 1024 * 1024 * 10);
ServerLog.setParams(testParams);
var p = ServerLog.getParams();
check({
    name: "setParams + getParams round-trip (test config applied)",
    ok: (p !== null && p !== undefined && p.getBoolean("Enable", false)),
    detail: "Enable=" + (p !== null ? p.getBoolean("Enable", false) : "null")
        + " Type=" + (p !== null ? p.getString("Type", null) : "null")
        + " Folder=" + (p !== null ? p.getString("Folder", null) : "null")
        + " LogTimer=" + (p !== null ? p.getLong("LogTimer", 0) : "null"),
    objective: "Verify ServerLog.setParams(Map) stores the config and getParams() reads it back",
    objectiveZh: "驗證 ServerLog.setParams(Map) 儲存設定且 getParams() 可讀回",
    command: "ServerLog.setParams({Enable:true, Type:\"GET, POST\", Folder:\"...\", LogTimer:300}); ServerLog.getParams()"
});

// Case S3 — Enable=false gate: writeWebLog must return false and write nothing.
testParams.put("Enable", false);
ServerLog.setParams(testParams);
var s3 = ServerLog.writeWebLog("127.0.0.1", "8443", "/test", "GET", 1, "", null, null, "127.0.0.1");
check({
    name: "writeWebLog returns false when Enable=false",
    ok: (s3 === false),
    detail: "returned=" + s3 + " (expected false)",
    objective: "Verify the Enable gate short-circuits writeWebLog",
    objectiveZh: "驗證 Enable=false 時 writeWebLog 直接返回 false",
    command: "ServerLog.writeWebLog(\"127.0.0.1\", \"8443\", \"/test\", \"GET\", 1, \"\", null, null, \"127.0.0.1\")"
});

// Case S4 — Type filter: Enable=true but Type=\"GET\" — a POST write must be
// rejected (return false, nothing queued).
testParams.put("Enable", true);
testParams.put("Type", "GET");
ServerLog.setParams(testParams);
var s4 = ServerLog.writeWebLog("127.0.0.1", "8443", "/test", "POST", 1, "", null, null, "127.0.0.1");
check({
    name: "writeWebLog returns false when Type does not match (GET only, writing POST)",
    ok: (s4 === false),
    detail: "returned=" + s4 + " (expected false; Type=GET)",
    objective: "Verify the Type filter rejects a non-matching log type",
    objectiveZh: "驗證 Type 過濾器拒絕不匹配的日誌類型",
    command: "ServerLog.writeWebLog(\"127.0.0.1\", \"8443\", \"/test\", \"POST\", 1, \"\", null, null, \"127.0.0.1\")"
});

// Case S5 — C3 fix: Type=\"GET, POST\" (space after the comma) must match a
// bare \"POST\" because each entry is trimmed before comparison.
testParams.put("Type", "GET, POST");
ServerLog.setParams(testParams);
var s5 = ServerLog.writeWebLog("127.0.0.1", "8443", "/test", "POST", 1, "", null, null, "127.0.0.1");
check({
    name: "writeWebLog returns true with Type=\"GET, POST\" writing POST (C3 trim fix)",
    ok: (s5 === true),
    detail: "returned=" + s5 + " (expected true; trailing-space comma entry must match)",
    objective: "Verify the C3 fix: each configured Type entry is trimmed so \"GET, POST\" matches \"POST\"",
    objectiveZh: "驗證 C3 修復：每個設定的 Type 項目都會 trim，因此 \"GET, POST\" 可匹配 \"POST\"",
    command: "ServerLog.writeWebLog(\"127.0.0.1\", \"8443\", \"/test\", \"POST\", 1, \"\", null, null, \"127.0.0.1\")"
});

// Case S6 — CSV flush: once the CacheArray background thread flushes, the CSV
// file must exist and carry the header row plus at least one data row. Poll up
// to 10s (the running server's CacheArray filter may use a longer LogTimer).
var lines6 = waitForCsv(1, 10);
var fileExists6 = new File(csvFile).exists();
check({
    name: "web log CSV file created with header + data row",
    ok: (fileExists6 && lines6 !== null && lines6.size() >= 2),
    detail: "file=" + csvFile + " exists=" + fileExists6
        + " lines=" + (lines6 !== null ? lines6.size() : 0)
        + " header=" + (lines6 !== null && lines6.size() > 0 ? lines6.get(0) : ""),
    objective: "Verify the batched web-log cache flushes to <Folder>/<yyyyMMdd>.csv with a header row and data rows",
    objectiveZh: "驗證網頁日誌批次快取會沖刷到 <Folder>/<yyyyMMdd>.csv，並包含標題列及資料列",
    command: "Files.readAllLines(Paths.get(csvFile), StandardCharsets.UTF_8)"
});

// Case S7 — row content: the POST /test row written in S5 must be present
// with the domain and port fields.
var rowFound7 = csvContains("127.0.0.1") && csvContains("/test") && csvContains("POST");
check({
    name: "CSV data row contains domain, uri and type (POST /test written)",
    ok: rowFound7,
    detail: "searching '" + csvFile + "' for 127.0.0.1 + /test + POST -> " + rowFound7,
    objective: "Verify the queued row is flushed with its domain/port/uri/type fields",
    objectiveZh: "驗證排隊的資料列已沖刷且包含 domain/port/uri/type 欄位",
    command: "grep '127.0.0.1.*/test.*POST' " + csvFile
});

// Case S8 — Allowed[] URI filter: with Allowed[0]=\"^.*SPECIAL.*$\", only rows
// whose full CSV line matches the regex are queued. Write one matching and one
// non-matching row, then confirm only the SPECIAL row lands in the file.
testParams.put("Allowed[0]", "^.*SPECIAL.*$");
ServerLog.setParams(testParams);
var wSpecial = ServerLog.writeWebLog("127.0.0.1", "8443", "/SPECIAL/keep", "GET", 2, "", null, null, "127.0.0.1");
var wOther = ServerLog.writeWebLog("127.0.0.1", "8443", "/plain/other", "GET", 3, "", null, null, "127.0.0.1");
var s8Special = waitForCsvContains("SPECIAL/keep", 10);
var s8Other = csvContains("plain/other");
check({
    name: "Allowed[] filter keeps matching URI, drops non-matching",
    ok: (wSpecial === true && wOther === true && s8Special === true && s8Other === false),
    detail: "write(SPECIAL)=" + wSpecial + " write(plain)=" + wOther
        + " csvHasSPECIAL=" + s8Special + " csvHasPlain=" + s8Other,
    objective: "Verify the Allowed[] regex filter gates which rows reach the CSV",
    objectiveZh: "驗證 Allowed[] 正規表達式過濾器決定哪些資料列會寫入 CSV",
    command: "ServerLog.writeWebLog(\"127.0.0.1\", \"8443\", \"/SPECIAL/keep\", \"GET\", 2, ...) — Allowed[0]=\"^.*SPECIAL.*$\""
});

// Case S9 — ParamMaxLength truncation: a long header value must be truncated
// to ParamMaxLength characters plus "..." inside the CSV row.
var headers9 = new HashMap();
headers9.put("X-Long-Header", "A_VERY_LONG_HEADER_VALUE_THAT_EXCEEDS_THE_LIMIT");
var s9 = ServerLog.writeWebLog("127.0.0.1", "8443", "/SPECIAL/trunc", "GET", 4, "", headers9, null, "127.0.0.1");
var s9Row = waitForCsvContains("SPECIAL/trunc", 10);
var s9Key = csvContains("X-Long-Header=");
var s9Trunc = csvContains("X-Long-Header=A_VERY_LONG");
var s9Full = csvContains("A_VERY_LONG_HEADER_VALUE_THAT_EXCEEDS_THE_LIMIT");
// Dump the actual row for the report so a regression in the truncation format
// (e.g. escaping of "...") is visible instead of a bare fail.
var s9Line = "";
var lines9 = readCsvLines();
if (lines9 !== null) {
    for (var i = 1; i < lines9.size(); i++) {
        var ln9 = "" + lines9.get(i);
        if (ln9.indexOf("SPECIAL/trunc") >= 0) {
            s9Line = ln9;
            break;
        }
    }
    if (s9Line.length > 300) { s9Line = s9Line.substring(0, 300) + "..."; }
}
check({
    name: "ParamMaxLength truncates long header values to N chars + \"...\"",
    ok: (s9 === true && s9Row === true && s9Key === true && s9Trunc === true && s9Full === false),
    detail: "write=" + s9 + " rowPresent=" + s9Row + " keyPresent=" + s9Key
        + " truncatedStart=" + s9Trunc + " fullValueAbsent=" + s9Full
        + " (ParamMaxLength=" + testParams.get("ParamMaxLength") + ")"
        + " row=[" + s9Line + "]",
    objective: "Verify handleParams truncates values longer than ParamMaxLength",
    objectiveZh: "驗證 handleParams 會把超過 ParamMaxLength 的值截斷",
    command: "ServerLog.writeWebLog(..., headers={X-Long-Header:\"A_VERY_LONG_HEADER_VALUE_THAT_EXCEEDS_THE_LIMIT\"}, ...)"
});

// Case S10 — restore the original ServerLog config captured in S2 so the
// running server is left exactly as it was.
var restoredOk = true;
try {
    if (origParams !== null && origParams !== undefined) {
        ServerLog.setParams(origParams);
    }
} catch (e) {
    restoredOk = false;
}
check({
    name: "original ServerLog config restored",
    ok: restoredOk,
    detail: (origParams !== null && origParams !== undefined)
        ? "setParams(origParams) applied (Enable=" + ServerLog.getParams().getBoolean("Enable", false) + ")"
        : "origParams was null — no restore needed",
    objective: "Leave the server's ServerLog config unchanged after the suite",
    objectiveZh: "測試結束後還原伺服器的 ServerLog 設定，不留殘留狀態",
    command: "if (origParams != null) ServerLog.setParams(origParams);"
});

// Case S11 — cleanup: delete the scratch web-log folder.
var cleaned = true;
try {
    if (new File(testFolder).exists()) { deleteDir(new File(testFolder)); }
} catch (e) {
    cleaned = false;
}
var stillExists = new File(testFolder).exists();
check({
    name: "scratch web-log folder removed (cleanup)",
    ok: (cleaned === true && stillExists === false),
    detail: "folder=" + testFolder + " stillExists=" + stillExists,
    objective: "Remove the temporary web-log CSV files created by this suite",
    objectiveZh: "移除本測試套件建立的臨時網頁日誌 CSV 檔案",
    command: "deleteDir(new File(testFolder))"
});

// ---------------------------------------------------------------------------
// Summary
// ---------------------------------------------------------------------------
writeReport();
writeMd();
print("========================================");
print("TestServerLog result: " + passCount + " passed, " + failCount + " failed");
if (failCount > 0) {
    print("FAILED checks: " + failures.join(", "));
} else {
    print("ALL TESTS PASSED");
}
