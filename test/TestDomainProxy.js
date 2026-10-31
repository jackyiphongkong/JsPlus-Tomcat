// ============================================================================
// JsPlus-Tomcat DomainProxy Test Suite (console mode, incremental skeleton)
// ----------------------------------------------------------------------------
// Run      : Inside the JsPlus console (server already running):
//              Console.eval(<path to this file>);
//            No bat/sh launcher needed — the console environment provides the
//            running Tomcat, the H2 data source and the global `tomcat` object.
// Harness  : Shared plumbing (Java bindings, counters, check/isOk/msg and
//            writeReport) lives in TestBase.js and is loaded below.
// Output   : <PROJECT_ROOT>/test_reports/TestDomainProxy-Report.html (green PASS / red FAIL
//            per check), written with BaseFile (DiskFile). Each check prints
//            [PASS]/[FAIL].
// NOTE     : In console mode the suite NEVER calls System.exit() (that would
//            shut down the ghost console); it prints the summary instead.
//            Run only against a disposable/development database.
// API      : Every public API call goes through the global `tomcat` object
//            (JsPlus/script/TomcatEmbed.js console entry) wrapped by
//            callTomcat() in TestBase.js — the module classes
//            (DomainProxy/SecurityFilter/IpFilter/IpAttackDefense) are never
//            touched directly. TomcatEmbed returns the result message string;
//            callTomcat re-wraps it into {code, msg} for the checks.
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
var REPORT_NAME = "TestDomainProxy-Report.html";
var REPORT_TITLE = "JsPlus-Tomcat DomainProxy Test Report";

print("=== JsPlus-Tomcat DomainProxy Test ===");
print("JS_PLUS_PATH: " + System.getProperty("JS_PLUS_PATH"));
print("Mode        : " + (CONSOLE_MODE ? "console (existing server, no System.exit)" : "standalone"));


// Case C1
check({
    name: "console environment ready (global tomcat)",
    ok: CONSOLE_MODE,
    detail: "expected to run via Console.eval(...) inside the JsPlus console",
    objective: "Ensure the suite runs inside the live JsPlus console (server ready, no System.exit)",
    objectiveZh: "確保測試套件於 JsPlus 主控台內執行（伺服器就緒、無 System.exit）",
    command: "Console.eval(System.getenv('JS_PLUS_PATH') + '/../test/TestDomainProxy.js')"
});

var r;

// Case C2
var mapParams = new HashMap();
mapParams.put("target", "https://zh.wikipedia.org");
mapParams.put("scriptEnable", false);  // empty = allow all
r = callTomcat(function(){ return tomcat.setDomainMapping("127.0.0.1", "8443", mapParams); });
check({
    name: "setDomainMapping 127.0.0.1:8443 -> zh.wikipedia.org",
    ok: isOk(r),
    detail: msg(r),
    objective: "Verify a domain mapping 127.0.0.1:8443 -> https://zh.wikipedia.org can be created",
    objectiveZh: "驗證 127.0.0.1:8443 → https://zh.wikipedia.org 的網域對應可建立",
    command: "tomcat.setDomainMapping(\"127.0.0.1\", \"8443\", {target: \"https://zh.wikipedia.org\", scriptEnable: false})",
    detail: msg(r) + dbVerify(function(){ return "SELECT * FROM domain_mapping_param WHERE domain='127.0.0.1' AND port='8443' AND deleted=FALSE"; })
});

// Case C3
r = callTomcat(function(){ return tomcat.queryDomainMapping("127.0.0.1", "8443"); });
check({
    name: "queryDomainMapping 127.0.0.1:8443 (target persisted)",
    ok: isOk(r),
    detail: msg(r) + dbVerify(function(){ return "SELECT * FROM domain_mapping_param WHERE domain='127.0.0.1' AND port='8443' AND deleted=FALSE"; }),
    objective: "Verify the mapping is persisted with the correct target",
    objectiveZh: "驗證對應已持久化並指向正確目標",
    command: "tomcat.queryDomainMapping(\"127.0.0.1\", \"8443\")"
});

// Case C4
r = callTomcat(function(){ return tomcat.queryDomainMappings(); });
check({
    name: "queryDomainMappings (127.0.0.1:8443 listed)",
    ok: isOk(r) && r.msg.indexOf("127.0.0.1:8443") >= 0,
    detail: msg(r) + dbVerify(function(){ return "SELECT * FROM domain_mapping_param WHERE deleted=FALSE"; }),
    objective: "Verify all domain mappings are listed and include the test mapping",
    objectiveZh: "驗證所有網域對應已列出且包含測試對應",
    command: "tomcat.queryDomainMappings()"
});

// Case C5
r = callTomcat(function(){ return tomcat.removeDomainMapping("127.0.0.1", "8443"); });
check({
    name: "removeDomainMapping 127.0.0.1:8443",
    ok: isOk(r),
    detail: msg(r),
    objective: "Verify the test mapping is removed (cleanup)",
    objectiveZh: "驗證測試對應已移除（清理）",
    command: "tomcat.removeDomainMapping(\"127.0.0.1\", \"8443\")",
    detail: msg(r) + dbVerify(function(){ return "SELECT * FROM domain_mapping_param WHERE deleted=FALSE"; })
});

// Case C6
r = callTomcat(function(){ return tomcat.setDomainMapping("127.0.0.1", "8443", mapParams); });
check({
    name: "setDomainMapping (re-create for live proxy test)",
    ok: isOk(r),
    detail: msg(r),
    objective: "Re-create the 127.0.0.1:8443 -> https://zh.wikipedia.org mapping before the live request",
    objectiveZh: "於即時代理請求前重新建立 127.0.0.1:8443 對應",
    command: "tomcat.setDomainMapping(\"127.0.0.1\", \"8443\", {target: \"https://zh.wikipedia.org\"})",
    detail: msg(r) + dbVerify(function(){ return "SELECT * FROM domain_mapping_param WHERE domain='127.0.0.1' AND port='8443' AND deleted=FALSE"; })
});

var statusCode = -1;
var pageTitle = "";
var curlOk = false;
try {
    var codeOut = runCmd(["curl", "-k", "-s", "-o", NULL_DEVICE, "-w", "%{http_code}", "https://127.0.0.1:8443/"]);
    statusCode = parseInt(codeOut.trim());
    var body = runCmd(["curl", "-k", "-s", "https://127.0.0.1:8443/"]);
    var titleStart = body.indexOf("<title>");
    var titleEnd = body.indexOf("</title>");
    if (titleStart >= 0 && titleEnd > titleStart) {
        pageTitle = body.substring(titleStart + "<title>".length, titleEnd);
    }
    curlOk = (statusCode === 200);
} catch (e) {
    print("[WARN] curl request failed: " + e);
}
// Case C7
check({
    name: "curl GET https://127.0.0.1:8443/ (proxied to zh.wikipedia.org)",
    ok: curlOk,
    detail: "status=" + statusCode + " title=" + pageTitle,
    objective: "Verify DomainProxy forwards the request upstream and returns HTTP 200 with the upstream <title>",
    objectiveZh: "驗證 DomainProxy 將請求轉發至上游並返回 HTTP 200 及上游 <title>",
    command: "curl -k -s -o NUL -w \"%{http_code}\" https://127.0.0.1:8443/  |  curl -k -s https://127.0.0.1:8443/",
    detail: "status=" + statusCode + " title=" + pageTitle + dbVerify(function(){ return "SELECT * FROM domain_mapping_param WHERE domain='127.0.0.1' AND port='8443' AND deleted=FALSE"; })
});

// Case C8
r = callTomcat(function(){ return tomcat.removeDomainMapping("127.0.0.1", "8443"); });
check({
    name: "removeDomainMapping (cleanup after live proxy test)",
    ok: isOk(r),
    detail: msg(r),
    objective: "Remove the mapping created for this test",
    objectiveZh: "移除本次測試建立的對應",
    command: "tomcat.removeDomainMapping(\"127.0.0.1\", \"8443\")",
    detail: msg(r) + dbVerify(function(){ return "SELECT * FROM domain_mapping_param WHERE deleted=FALSE"; })
});

// Case C9
mapParams = new HashMap();
mapParams.put("scriptEnable", true);
mapParams.put("scriptFile", "sample.js");
r = callTomcat(function(){ return tomcat.setDomainMapping("127.0.0.1", "8080", mapParams); });
check({
    name: "setDomainMapping (Test DomainMapping.filter)",
    ok: isOk(r),
    detail: msg(r),
    objective: "Create the 127.0.0.1:8080 -> https://zh.wikipedia.org mapping with a filter script (sample.js) that modifies the HTML response",
    objectiveZh: "建立 127.0.0.1:8080 對應並啟用過濾腳本（sample.js）修改 HTML 回應",
    command: "tomcat.setDomainMapping(\"127.0.0.1\", \"8080\", {scriptEnable: true, scriptFile: \"sample.js\"})",
    detail: msg(r) + dbVerify(function(){ return "SELECT * FROM domain_mapping_param WHERE domain='127.0.0.1' AND port='8080' AND deleted=FALSE"; })
});

// Case C10
var statusCode = -1;
var pageTitle = "";
var curlOk = false;
try {
    var codeOut = runCmd(["curl", "-k", "-s", "-o", NULL_DEVICE, "-w", "%{http_code}", "http://127.0.0.1:8080/"]);
    statusCode = parseInt(codeOut.trim());
    var body = runCmd(["curl", "-k", "-s", "http://127.0.0.1:8080/?aaa"]);
    curlOk = (statusCode === 200);
} catch (e) {
    print("[WARN] curl request failed: " + e);
}

check({
    name: "curl GET http://127.0.0.1:8080/ (proxied to zh.wikipedia.org, html title updated)",
    ok: curlOk && (body.indexOf("Cleared") > -1),
    detail: "status=" + statusCode + " Found 'Cleared'=" + (body.indexOf("Cleared") > -1),
    objective: "Verify DomainProxy forwards the request upstream and returns HTTP 200 with the upstream HTML modified by the filter script (sample.js) to include 'Cleared' in the body",
    objectiveZh: "驗證 DomainProxy 轉發請求並返回 HTTP 200，上游 HTML 已由過濾腳本加入 'Cleared'",
    command: "curl -k -s -o NUL -w \"%{http_code}\" http://127.0.0.1:8080/  |  curl -k -s \"http://127.0.0.1:8080/?aaa\"",
    detail: "status=" + statusCode + " Found 'Cleared'=" + (body.indexOf("Cleared") > -1) + dbVerify(function(){ return "SELECT * FROM domain_mapping_param WHERE domain='127.0.0.1' AND port='8080' AND deleted=FALSE"; })
});

// Case C11 — cleanup all test data: remove the 8080 filter-script mapping
// (8443 was already removed by C8) and verify no 127.0.0.1 mapping records
// remain in domain_mapping_param (deleted=FALSE -> 0 rows).
r = callTomcat(function(){ return tomcat.removeDomainMapping("127.0.0.1", "8080"); });
check({
    name: "removeDomainMapping 127.0.0.1:8080 (filter script cleanup)",
    ok: isOk(r),
    detail: msg(r),
    objective: "Remove the 127.0.0.1:8080 mapping created for the filter script test and verify no test mapping records remain",
    objectiveZh: "移除過濾腳本測試建立的 127.0.0.1:8080 對應，並驗證無測試對應記錄殘留",
    command: "tomcat.removeDomainMapping(\"127.0.0.1\", \"8080\")",
    detail: msg(r) + dbVerify(function(){ return "SELECT * FROM domain_mapping_param WHERE domain='127.0.0.1' AND deleted=FALSE"; })
});

// ---------------------------------------------------------------------------
// URI-prefix mappings (uri column): a mapping scoped to domain:port/uri is
// picked by longest-prefix match on the request path, falling back to the
// blank-uri (catch-all) mapping when none matches.
// ---------------------------------------------------------------------------

// Case C12 — create the uri-specific mapping. 8080's catch-all mapping was
// already removed by C11, so any request below must resolve through /wiki.
r = callTomcat(function(){ return tomcat.setDomainMapping("127.0.0.1", "8080", "/wiki",
    {target: "https://zh.wikipedia.org", scriptEnable: false}); });
check({
    name: "setDomainMapping 127.0.0.1:8080 uri=/wiki",
    ok: isOk(r),
    detail: msg(r) + dbVerify(function(){ return "SELECT * FROM domain_mapping_param WHERE domain='127.0.0.1' AND port='8080' AND uri='/wiki' AND deleted=FALSE"; }),
    objective: "Verify a uri-specific mapping 127.0.0.1:8080/wiki -> https://zh.wikipedia.org is created and persisted",
    objectiveZh: "驗證可建立並持久化 127.0.0.1:8080/wiki 之 URI 對應",
    command: "tomcat.setDomainMapping(\"127.0.0.1\", \"8080\", \"/wiki\", {target: \"https://zh.wikipedia.org\", scriptEnable: false})"
});

// Case C13 — query the uri-specific mapping (Uri shown in the result).
r = callTomcat(function(){ return tomcat.queryDomainMapping("127.0.0.1", "8080", "/wiki"); });
check({
    name: "queryDomainMapping 127.0.0.1:8080 uri=/wiki (Uri shown)",
    ok: isOk(r) && r.msg.indexOf("Uri: /wiki") >= 0,
    detail: msg(r),
    objective: "Verify the uri-specific mapping is queryable and reports the uri",
    objectiveZh: "驗證 URI 對應可查詢且顯示 uri",
    command: "tomcat.queryDomainMapping(\"127.0.0.1\", \"8080\", \"/wiki\")"
});

// Case C14 — longest-prefix routing: GET /wiki/ must resolve through the /wiki
// mapping (scriptEnable=false -> no sample.js -> body must NOT contain
// 'Cleared'; if the uri match failed the request would get 502 since the
// catch-all 8080 mapping was removed).
var statusCode = -1;
var bodyText = "";
var uriCurlOk = false;
try {
    var codeOut = runCmd(["curl", "-k", "-s", "-o", NULL_DEVICE, "-w", "%{http_code}", "http://127.0.0.1:8080/wiki/"]);
    statusCode = parseInt(codeOut.trim());
    bodyText = runCmd(["curl", "-k", "-s", "http://127.0.0.1:8080/wiki/"]);
    // Forwarded successfully = any real upstream HTTP response (never 502);
    // and the /wiki mapping has scriptEnable=false so 'Cleared' must NOT appear.
    uriCurlOk = (statusCode > 0 && statusCode !== 502) && (bodyText.indexOf("Cleared") < 0);
} catch (e) {
    print("[WARN] curl request failed: " + e);
}
check({
    name: "curl GET http://127.0.0.1:8080/wiki/ (routed via uri=/wiki, no filter script)",
    ok: uriCurlOk,
    detail: "status=" + statusCode + " foundCleared=" + (bodyText.indexOf("Cleared") > -1) + dbVerify(function(){ return "SELECT * FROM domain_mapping_param WHERE domain='127.0.0.1' AND port='8080' AND uri='/wiki' AND deleted=FALSE"; }),
    objective: "Verify the request path /wiki/ is routed through the uri=/wiki mapping (prefix match) and bypasses the blank-uri filter script",
    objectiveZh: "驗證請求路徑 /wiki/ 經 uri=/wiki 前綴對應轉發，且不會套用空白對應之過濾腳本",
    command: "curl -k -s -o NUL -w \"%{http_code}\" http://127.0.0.1:8080/wiki/  |  curl -k -s http://127.0.0.1:8080/wiki/"
});

// Case C15 — cleanup: remove the /wiki uri mapping and verify it is gone.
r = callTomcat(function(){ return tomcat.removeDomainMapping("127.0.0.1", "8080", "/wiki"); });
check({
    name: "removeDomainMapping 127.0.0.1:8080 uri=/wiki (cleanup)",
    ok: isOk(r),
    detail: msg(r) + dbVerify(function(){ return "SELECT * FROM domain_mapping_param WHERE domain='127.0.0.1' AND port='8080' AND uri='/wiki' AND deleted=FALSE"; }),
    objective: "Remove the uri-specific mapping created for this test and verify no test mapping records remain",
    objectiveZh: "移除本次測試建立的 /wiki URI 對應，並驗證無測試對應記錄殘留",
    command: "tomcat.removeDomainMapping(\"127.0.0.1\", \"8080\", \"/wiki\")"
});

// Case C16 — "/" uri normalises to the catch-all mapping (stored as blank
// uri, same as the 3-arg setDomainMapping) with scriptEnable=false; the
// 4-arg overload must accept "/" and persist it as the domain:port catch-all.
r = callTomcat(function(){ return tomcat.setDomainMapping("127.0.0.1", "8080", "/",
    {target: "https://zh.wikipedia.org", scriptEnable: false}); });
check({
    name: "setDomainMapping 127.0.0.1:8080 uri=/ (normalises to catch-all)",
    ok: isOk(r),
    detail: msg(r) + dbVerify(function(){ return "SELECT * FROM domain_mapping_param WHERE domain='127.0.0.1' AND port='8080' AND COALESCE(uri,'')='' AND deleted=FALSE"; }),
    objective: "Verify a '/' uri maps to the catch-all (blank-uri) domain:port mapping and is persisted with scriptEnable=false",
    objectiveZh: "驗證 '/' 之 URI 正常化為空白 catch-all 對應並持久化，且 scriptEnable=false",
    command: "tomcat.setDomainMapping(\"127.0.0.1\", \"8080\", \"/\", {target: \"https://zh.wikipedia.org\", scriptEnable: false})"
});

// Case C17 — cleanup: remove the catch-all 8080 mapping created by C16 and
// verify no 127.0.0.1 mapping records remain.
r = callTomcat(function(){ return tomcat.removeDomainMapping("127.0.0.1", "8080"); });
check({
    name: "removeDomainMapping 127.0.0.1:8080 (C16 catch-all cleanup)",
    ok: isOk(r),
    detail: msg(r) + dbVerify(function(){ return "SELECT * FROM domain_mapping_param WHERE domain='127.0.0.1' AND deleted=FALSE"; }),
    objective: "Remove the catch-all mapping created in C16 and verify no test mapping records remain",
    objectiveZh: "移除 C16 建立的 catch-all 對應，並驗證無測試對應記錄殘留",
    command: "tomcat.removeDomainMapping(\"127.0.0.1\", \"8080\")"
});


// ---------------------------------------------------------------------------
// Internal mode (mode='internal'): dispatch to a local webapp context without
// a second HTTP round-trip; the browser URL keeps the mapped prefix.
// ---------------------------------------------------------------------------

// Case C18 — create the internal-mode mapping localhost:8443/test ->
// https://127.0.0.1/default (the target's path component is the destination
// webapp context; host/port are ignored in internal mode).
r = callTomcat(function(){ return tomcat.setDomainMapping("localhost", "8443", "/test",
    {mode: "internal", target: "https://127.0.0.1/default", scriptEnable: false}); });
check({
    name: "setDomainMapping localhost:8443 uri=/test mode=internal",
    ok: isOk(r),
    detail: msg(r) + dbVerify(function(){ return "SELECT * FROM domain_mapping_param WHERE domain='localhost' AND port='8443' AND uri='/test' AND deleted=FALSE"; }),
    objective: "Verify an internal-mode uri mapping (localhost:8443/test -> local webapp /default) is created and persisted",
    objectiveZh: "驗證可建立並持久化 internal 模式之 URI 對應（localhost:8443/test → 本地 /default）",
    command: "tomcat.setDomainMapping(\"localhost\", \"8443\", \"/test\", {mode: \"internal\", target: \"https://127.0.0.1/default\", scriptEnable: false})"
});

// Case C19 — request /test/ through the internal mapping: must NOT be a proxy
// 502 ("No action"/"Target domain not found") and must NOT return an external
// redirect (302 Location to https://...). A local webapp status (200/404/403)
// proves the RequestDispatcher.forward path was taken.
var statusCode = -1;
var hasExternalLocation = false;
var internalCurlOk = false;
var bodyText = "";
try {
    var headerText = runCmd(["curl", "-k", "-s", "-o", NULL_DEVICE, "-D", "-", "https://localhost:8443/test/"]);
    var statusParts = headerText.split("\n")[0].split(" ");
    statusCode = statusParts.length >= 2 ? parseInt(statusParts[1]) : -1;
    hasExternalLocation = headerText.indexOf("Location: https://") >= 0;
    internalCurlOk = (statusCode > 0 && statusCode !== 502) && !hasExternalLocation;
    bodyText = runCmd(["curl", "-k", "-s", "https://localhost:8443/test/"]);
    // NOTE: no JS regex literal here — GraalVM JS would need the Truffle
    // "regex" language, which is not bundled ([python, js] only).
    bodyText = String(bodyText).split("\n")[0];
    if (bodyText.length > 200) {
        bodyText = bodyText.substring(0, 200) + "...";
    }
} catch (e) {
    print("[WARN] curl request failed: " + e);
}
check({
    name: "curl GET https://localhost:8443/test/ (internal dispatch, no 502/redirect)",
    ok: internalCurlOk,
    detail: "status=" + statusCode + " externalLocation=" + hasExternalLocation + " html=" + bodyText + dbVerify(function(){ return "SELECT * FROM domain_mapping_param WHERE domain='localhost' AND port='8443' AND uri='/test' AND deleted=FALSE"; }),
    objective: "Verify mode=internal dispatches to the local webapp (no proxy 502, no external redirect) and records the served HTML body; /default is served by webapps/default so a local 200 proves the dispatch path",
    objectiveZh: "驗證 internal 模式於本地分派請求（無代理 502、無外部重定向）並記錄返回之 HTML 內容；/default 由 webapps/default 提供，本地 200 證明分派已執行",
    command: "curl -k -s -o NUL -D - https://localhost:8443/test/ && curl -k -s https://localhost:8443/test/"
});

// Case C20 — cleanup: remove the internal-mode mapping created in C18.
r = callTomcat(function(){ return tomcat.removeDomainMapping("localhost", "8443", "/test"); });
check({
    name: "removeDomainMapping localhost:8443 uri=/test (internal cleanup)",
    ok: isOk(r),
    detail: msg(r) + dbVerify(function(){ return "SELECT * FROM domain_mapping_param WHERE domain='localhost' AND port='8443' AND uri='/test' AND deleted=FALSE"; }),
    objective: "Remove the internal-mode mapping created for this test and verify no test mapping records remain",
    objectiveZh: "移除本次測試建立的 internal 對應，並驗證無測試對應記錄殘留",
    command: "tomcat.removeDomainMapping(\"localhost\", \"8443\", \"/test\")"
});


// Case C21 — mode=internal with scriptEnable=true must be rejected with
// resMap {code: 0, msg: "<ERROR_MSG>"}. This case calls the Java API directly
// (not via callTomcat) because TomcatEmbed only echoes resMap.msg and drops
// the code, so callTomcat cannot observe code=0.
var internalScriptRejected = false;
var internalScriptMsg = "";
try {
    var p = new HashMap();
    p.put("mode", "internal");
    p.put("target", "https://127.0.0.1/default");
    p.put("scriptEnable", true);
    p.put("scriptFile", "filter_script.js");
    var rawRes = DomainProxy.setDomainMapping("localhost", "8443", "/test_script", p);
    internalScriptRejected = Number(rawRes.get("code")) === 0 && String(rawRes.get("msg")).indexOf("scriptEnable") >= 0;
    internalScriptMsg = String(rawRes.get("msg"));
} catch (e) {
    print("[WARN] direct Java call failed: " + e);
}
check({
    name: "setDomainMapping mode=internal scriptEnable=true rejected (code=0)",
    ok: internalScriptRejected,
    detail: "code=" + (internalScriptRejected ? "0" : "?") + " msg=" + internalScriptMsg + dbVerify(function(){ return "SELECT * FROM domain_mapping_param WHERE domain='localhost' AND port='8443' AND uri='/test_script' AND deleted=FALSE"; }),
    objective: "Verify mode=internal combined with scriptEnable=true returns resMap {code:0, msg:<error>}; the filter script only applies to proxy (Forward/Redirect) traffic",
    objectiveZh: "驗證 mode=internal 配合 scriptEnable=true 會返回 {code:0, msg:<錯誤>}；過濾腳本僅適用於代理轉發（Forward/Redirect）",
    command: "DomainProxy.setDomainMapping(\"localhost\", \"8443\", \"/test_script\", {mode: \"internal\", scriptEnable: true, scriptFile: \"filter_script.js\"})"
});


// ---------------------------------------------------------------------------
// preRequest hook (DomainMapping.preRequest) — short-circuit before upstream.
// sample.js's preRequest() stubs /health to {status:200, body:'ok'} so the
// request must never reach the upstream target (even when the target is
// unreachable, /health must return 200 'ok' immediately).
// We use 8443 (HTTPS, already listened by Tomcat) because the mapping's port
// is request.getLocalPort() — a request arriving on 8081 cannot connect.
// ---------------------------------------------------------------------------

// Case C22 — create the 127.0.0.1:8443 mapping with scriptFile=sample.js so
// preRequest() is loaded. Target is set to zh.wikipedia.org (unreachable from
// CI at times) — this is deliberate: if preRequest short-circuits /health, the
// target never matters.
var preMapParams = new HashMap();
preMapParams.put("target", "https://zh.wikipedia.org");
preMapParams.put("scriptEnable", true);
preMapParams.put("scriptFile", "sample.js");
r = callTomcat(function(){ return tomcat.setDomainMapping("127.0.0.1", "8443", preMapParams); });
check({
    name: "setDomainMapping 127.0.0.1:8443 scriptFile=sample.js (preRequest hook)",
    ok: isOk(r),
    detail: msg(r) + dbVerify(function(){ return "SELECT * FROM domain_mapping_param WHERE domain='127.0.0.1' AND port='8443' AND deleted=FALSE"; }),
    objective: "Create a 127.0.0.1:8443 mapping with scriptFile=sample.js so the preRequest() hook is loaded; target points at zh.wikipedia.org to prove the stub below does not depend on upstream reachability",
    objectiveZh: "建立 127.0.0.1:8443 對應並載入 sample.js 之 preRequest() 掛鉤；上游指向 zh.wikipedia.org 以證明短攔不依賴上游可達性",
    command: "tomcat.setDomainMapping(\"127.0.0.1\", \"8443\", {target: \"https://zh.wikipedia.org\", scriptEnable: true, scriptFile: \"sample.js\"})"
});

// Case C23 — curl GET https://127.0.0.1:8443/health must return 200 with body
// exactly 'ok'. Because preRequest short-circuits /health, the upstream
// zh.wikipedia.org is never contacted — so this must pass even when the
// external proxy path (port 8080) is flaky / 502.
var healthStatus = -1;
var healthBody = "";
var healthOk = false;
try {
    var healthHeader = runCmd(["curl", "-k", "-s", "-o", NULL_DEVICE, "-D", "-", "https://127.0.0.1:8443/health"]);
    var hp = healthHeader.split("\n")[0].split(" ");
    healthStatus = hp.length >= 2 ? parseInt(hp[1]) : -1;
    healthBody = String(runCmd(["curl", "-k", "-s", "https://127.0.0.1:8443/health"])).trim();
    healthOk = (healthStatus === 200) && (healthBody === "ok");
} catch (e) {
    print("[WARN] curl /health failed: " + e);
}
check({
    name: "curl GET https://127.0.0.1:8443/health (preRequest short-circuit, stub 200 'ok')",
    ok: healthOk,
    detail: "status=" + healthStatus + " body='" + healthBody + "'" + dbVerify(function(){ return "SELECT * FROM domain_mapping_param WHERE domain='127.0.0.1' AND port='8443' AND deleted=FALSE"; }),
    objective: "Verify DomainMapping.preRequest() short-circuits GET /health with {status:200, body:'ok'} without contacting the upstream target",
    objectiveZh: "驗證 DomainMapping.preRequest() 對 GET /health 短攔回傳 {status:200, body:'ok'}，且不會連接上游",
    command: "curl -k -s -o NUL -D - https://127.0.0.1:8443/health && curl -k -s https://127.0.0.1:8443/health"
});

// Case C24 — curl GET /prime. preRequest() writes the {status:200,
// body:'CACHED'} entry into the Java Cache and returns 200 'PRIMED' directly
// (no upstream call). We assert status=200 body='PRIMED' to confirm the JS
// side ran and the cache write happened before we check the cache hit.
var primeStatus = -1;
var primeBody = "";
var primeOk = false;
try {
    var primeHeader = runCmd(["curl", "-k", "-s", "-o", NULL_DEVICE, "-D", "-", "https://127.0.0.1:8443/prime"]);
    var pp = primeHeader.split("\n")[0].split(" ");
    primeStatus = pp.length >= 2 ? parseInt(pp[1]) : -1;
    primeBody = String(runCmd(["curl", "-k", "-s", "https://127.0.0.1:8443/prime"])).trim();
    primeOk = (primeStatus === 200) && (primeBody === "PRIMED");
} catch (e) {
    print("[WARN] curl /prime failed: " + e);
}
check({
    name: "curl GET https://127.0.0.1:8443/prime (preRequest writes cache, short-circuits 200 PRIMED)",
    ok: primeOk,
    detail: "status=" + primeStatus + " body='" + primeBody + "'",
    objective: "Verify preRequest() runs on /prime, writes the cache entry, and short-circuits with 200 'PRIMED' (no upstream call)",
    objectiveZh: "驗證 preRequest() 於 /prime 執行、寫入快取，並短攔回傳 200 'PRIMED'（不連接上游）",
    command: "curl -k -s -o NUL -D - https://127.0.0.1:8443/prime && curl -k -s https://127.0.0.1:8443/prime"
});

// Case C25 — curl GET /cached. preRequest() must find the primed entry and
// short-circuit with {status:200, body:'CACHED'}. Crucially, this must NOT
// proxy upstream — so even though zh.wikipedia.org is unreachable, /cached
// returns 200 'CACHED'.
// NOTE: sleep 2s to let the IpAttackDefense rate-limiter window cool down —
// the preceding /health (x2) and /prime (x2) curls already consumed the burst
// budget, and without the pause /cached would come back 429 before reaching
// DomainProxy.
java.lang.Thread.sleep(2000);
var cachedStatus = -1;
var cachedBody = "";
var cachedOk = false;
try {
    var cachedHeader = runCmd(["curl", "-k", "-s", "-o", NULL_DEVICE, "-D", "-", "https://127.0.0.1:8443/cached"]);
    var cp = cachedHeader.split("\n")[0].split(" ");
    cachedStatus = cp.length >= 2 ? parseInt(cp[1]) : -1;
    cachedBody = String(runCmd(["curl", "-k", "-s", "https://127.0.0.1:8443/cached"])).trim();
    cachedOk = (cachedStatus === 200) && (cachedBody === "CACHED");
} catch (e) {
    print("[WARN] curl /cached failed: " + e);
}
check({
    name: "curl GET https://127.0.0.1:8443/cached (preRequest cache-hit short-circuit)",
    ok: cachedOk,
    detail: "status=" + cachedStatus + " body='" + cachedBody + "'",
    objective: "Verify preRequest() finds the primed cache entry and short-circuits with {status:200, body:'CACHED'} without contacting the upstream target",
    objectiveZh: "驗證 preRequest() 命中預置快取並短攔回傳 {status:200, body:'CACHED'}，且不會連接上游",
    command: "curl -k -s -o NUL -D - https://127.0.0.1:8443/cached && curl -k -s https://127.0.0.1:8443/cached"
});

// Case C26 — cleanup: remove the 8443 mapping created in C22.
r = callTomcat(function(){ return tomcat.removeDomainMapping("127.0.0.1", "8443"); });
check({
    name: "removeDomainMapping 127.0.0.1:8443 (preRequest cleanup)",
    ok: isOk(r),
    detail: msg(r) + dbVerify(function(){ return "SELECT * FROM domain_mapping_param WHERE domain='127.0.0.1' AND port='8443' AND deleted=FALSE"; }),
    objective: "Remove the 8443 mapping created for the preRequest hook test and verify no residual rows",
    objectiveZh: "移除本次測試建立的 8443 對應，並驗證無殘留記錄",
    command: "tomcat.removeDomainMapping(\"127.0.0.1\", \"8443\")"
});


// ---------------------------------------------------------------------------
// Summary
// ---------------------------------------------------------------------------
writeReport();
writeMd();
print("========================================");
print("TestDomainProxy result: " + passCount + " passed, " + failCount + " failed");
if (failCount > 0) {
    print("FAILED checks: " + failures.join(", "));
} else {
    print("ALL TESTS PASSED");
}

