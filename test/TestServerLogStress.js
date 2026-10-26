// ============================================================================
// JsPlus-Tomcat ServerLog Bulk Write Stress Test (console/standalone)
// ----------------------------------------------------------------------------
// Run      : java ... com.killcoding.jsplus.Main -Task <this file> < NUL
//            (same launcher as TestAll.bat Step 2; standalone mode is fine,
//            no running server needed — the shared static CacheArray is reset
//            first so the flush uses this suite's short LogTimer).
// Harness  : Shared plumbing lives in TestBase.js (loaded below).
// Output   : <PROJECT_ROOT>/test_reports/TestServerLogStress-Report.html
// Focus    : Prove writeWebLog survives a bulk load (default 5000 rows):
//            every call returns true, the CacheArray background filter drains
//            ALL rows into the CSV (header + N data rows), first/last rows
//            are intact, and throughput is sane. Ends with Runtime.halt(0)
//            because the CacheArray filter thread keeps the JVM alive.
// Env      : TESTWEBLOG_TOTAL  default 5000   (number of rows to write)
// ============================================================================

// Load the shared test harness.
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

// Report file name and title for this suite.
var REPORT_NAME = "TestServerLogStress-Report.html";
var REPORT_TITLE = "JsPlus-Tomcat ServerLog Bulk Write Test Report";

print("=== JsPlus-Tomcat ServerLog Bulk Write Stress Test ===");
print("JS_PLUS_PATH: " + System.getProperty("JS_PLUS_PATH"));
print("Mode        : " + (CONSOLE_MODE ? "console" : "standalone"));

// Java bindings used by this suite.
var ServerLog = Java.type("com.killcoding.jsplus.tomcat.ServerLog");
var Thread = Java.type("java.lang.Thread");
var File = Java.type("java.io.File");
var Files = Java.type("java.nio.file.Files");
var Paths = Java.type("java.nio.file.Paths");
var StandardCharsets = Java.type("java.nio.charset.StandardCharsets");
var SimpleDateFormat = Java.type("java.text.SimpleDateFormat");
var JDate = Java.type("java.util.Date");

function envOr(name, def) {
    var v = System.getenv(name);
    return (v !== null && v !== undefined && v !== "") ? v : ("" + def);
}
function round0(x) { return Math.round(x); }

var r;
var total = parseInt(envOr("TESTWEBLOG_TOTAL", "5000"), 10);

// Reset the shared static CacheArray (see TestServerLog.js — the running
// server's filter may be idle; a fresh filter binds this suite's LogTimer).
try {
    var JClass = Java.type("java.lang.Class");
    var wlcClass = JClass.forName("com.killcoding.jsplus.tomcat.ServerLog");
    var wlcField = wlcClass.getDeclaredField("webLogCacheArray");
    wlcField.setAccessible(true);
    wlcField.set(null, null);
    print("[ServerLog] webLogCacheArray reset to null (fresh filter, LogTimer=200ms)");
} catch (e) {
    print("[WARN] could not reset ServerLog.webLogCacheArray: " + e);
}

var testFolder = basePath + "../test_reports/weblog_stress";
var dateStr = new SimpleDateFormat("yyyyMMdd").format(new JDate());
var csvFile = testFolder + "/" + dateStr + ".csv";

// Capture original config for restore at the end.
var origParams = ServerLog.getParams();

var testParams = new HashMap();
testParams.put("Enable", true);
testParams.put("Type", "GET, POST");
testParams.put("Folder", testFolder);
testParams.put("LogTimer", 200);
testParams.put("ParamMaxLength", 50);
testParams.put("ArchiveDays", 31);
testParams.put("MaxFileSize", 1024 * 1024 * 10);
ServerLog.setParams(testParams);

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
function waitForCsv(minDataRows, seconds) {
    var deadline = Date.now() + seconds * 1000;
    while (Date.now() < deadline) {
        var lines = readCsvLines();
        if (lines !== null && lines.size() > minDataRows) { return lines; }
        Thread.sleep(300);
    }
    return readCsvLines();
}
function deleteDir(f) {
    if (f.isDirectory()) {
        var children = f.listFiles();
        for (var i = 0; i < children.length; i++) { deleteDir(children[i]); }
    }
    f.delete();
}

// Case B1 — bulk write: N consecutive writeWebLog calls, all must return true.
var t0 = Date.now();
var errors = 0;
var firstError = "";
for (var i = 0; i < total; i++) {
    try {
        var ok = ServerLog.writeWebLog("127.0.0.1", "8443", "/stress/" + i, "GET", i + 1, "", null, null, "127.0.0.1");
        if (ok !== true) { errors++; if (firstError === "") { firstError = "non-true at " + i; } }
    } catch (e) {
        errors++;
        if (firstError === "") { firstError = "exception at " + i + ": " + e; }
    }
}
var t1 = Date.now();
var elapsedMs = t1 - t0;
var writeQps = (elapsedMs > 0) ? round0(total * 1000.0 / elapsedMs) : 0;
check({
    name: "bulk write " + total + " rows (all returned true)",
    ok: errors === 0,
    detail: "errors=" + errors + (firstError !== "" ? " first=" + firstError : "")
        + " elapsed=" + elapsedMs + "ms writeQps=" + writeQps,
    objective: "Prove writeWebLog accepts and queues a bulk load without failures",
    objectiveZh: "證明 writeWebLog 可承受大量寫入而不出錯",
    command: "for (i=0;i<" + total + ";i++) ServerLog.writeWebLog(\"127.0.0.1\",\"8443\",\"/stress/\"+i,\"GET\",i+1,\"\",null,null,\"127.0.0.1\")"
});

// Case B2 — CSV drain: the background CacheArray filter must flush header + N
// data rows within the poll window.
var linesB2 = waitForCsv(total, 90);
var rowCountB2 = (linesB2 !== null) ? linesB2.size() : 0;
var expectedB2 = total + 1;   // header + N data rows
check({
    name: "CSV drained: header + " + total + " data rows",
    ok: (rowCountB2 >= expectedB2),
    detail: "rowCount=" + rowCountB2 + " expected>=" + expectedB2 + " file=" + csvFile,
    objective: "Verify every queued row is eventually flushed to the CSV by the CacheArray filter",
    objectiveZh: "驗證 CacheArray 過濾器最終把所有排隊資料沖刷到 CSV",
    command: "Files.readAllLines(Paths.get(csvFile))"
});

// Case B3 — integrity: first (/stress/0) and last (/stress/<N-1>) rows present.
var firstOk = csvContains("/stress/0");
var lastOk = csvContains("/stress/" + (total - 1));
check({
    name: "first and last rows present in CSV",
    ok: (firstOk && lastOk),
    detail: "first=/stress/0 -> " + firstOk + " last=/stress/" + (total - 1) + " -> " + lastOk,
    objective: "Spot-check that the head and tail of the bulk load survived the flush",
    objectiveZh: "抽樣驗證大量寫入的首尾資料完整",
    command: "grep /stress/0 + grep /stress/" + (total - 1) + " in " + csvFile
});

// Case B4 — CacheArray drained: after the flush settles, the in-memory cache
// should hold far fewer items than the total written (or 0). If the cache
// keeps everything, that is a memory-growth signal worth reporting.
var cacheSize = -1;
var cacheChecked = false;
try {
    var wlcNow = wlcField.get(null);
    cacheSize = (wlcNow !== null) ? wlcNow.size() : 0;
    cacheChecked = true;
} catch (e) {
    print("[WARN] cache size check failed: " + e);
}
check({
    name: "CacheArray drained after flush (size << total)",
    ok: (cacheChecked === true && (cacheSize === 0 || cacheSize < total / 4)),
    detail: "cacheSize=" + cacheSize + " total=" + total
        + (cacheSize >= total / 4 ? " (note: array keeps processed items — potential growth under sustained load)" : ""),
    objective: "Report whether processed rows are released from the in-memory cache after flushing",
    objectiveZh: "報告沖刷後記憶體快取是否釋放處理過的資料列",
    command: "ServerLog.webLogCacheArray.size() (via reflection)"
});

// Case B5 — restore original config + cleanup scratch folder.
var restoredOk = true;
try {
    if (origParams !== null && origParams !== undefined) {
        ServerLog.setParams(origParams);
    }
} catch (e) {
    restoredOk = false;
}
var cleaned = true;
try {
    if (new File(testFolder).exists()) { deleteDir(new File(testFolder)); }
} catch (e) {
    cleaned = false;
}
var stillExists = new File(testFolder).exists();
check({
    name: "original config restored + scratch folder removed",
    ok: (restoredOk === true && cleaned === true && stillExists === false),
    detail: "restored=" + restoredOk + " cleaned=" + cleaned + " stillExists=" + stillExists,
    objective: "Leave no config or file residue behind",
    objectiveZh: "測試後不留設定或檔案殘留",
    command: "ServerLog.setParams(origParams); deleteDir(testFolder)"
});

// ---------------------------------------------------------------------------
// Summary
// ---------------------------------------------------------------------------
writeReport();
writeMd();
print("========================================");
print("TestServerLogStress result: " + passCount + " passed, " + failCount + " failed");
if (failCount > 0) {
    print("FAILED checks: " + failures.join(", "));
} else {
    print("ALL TESTS PASSED");
}

// The CacheArray filter thread (and possibly the console) keeps the JVM alive;
// hard-halt so this standalone task always returns. Production paths are
// untouched — the suite already restored the original config.
var Runtime = Java.type("java.lang.Runtime");
Runtime.getRuntime().halt(0);
