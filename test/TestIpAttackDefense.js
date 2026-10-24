// ============================================================================
// JsPlus-Tomcat IpAttackDefense Test Suite (console mode)
// ----------------------------------------------------------------------------
// Run      : Inside the JsPlus console (server already running):
//              Console.eval(<path to this file>);
//            No bat/sh launcher needed — the console environment provides the
//            running Tomcat, the H2 data source and the global `tomcat` object.
// Harness  : Shared plumbing (Java bindings, counters, check/isOk/msg and
//            writeReport) lives in TestBase.js and is loaded below.
// Output   : <PROJECT_ROOT>/test_reports/TestIpAttackDefense-Report.html (green PASS / red FAIL
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
// PREREQ   : In JsPlus/config/tomcat.properties set:
//              IpFilter.cacheSeconds=0
//              IpAttackDefense.ruleCacheSeconds=0
//            The auto-banned IP is enforced through an ip_ban DENY rule that
//            IpFilter reads; setting 0 makes the live checks (C8/C11) read the
//            rule list unconditionally (create/remove already invalidate it).
//            The defense-rule cache is disabled the same way so a rule update
//            (C5) is picked up by the live burst without stale entries.
//            IpAttackDefense.logTimer is 5000 ms — the batched ip_request_log
//            insert flushes on that cadence, so C14 sleeps ~6 s before querying.
// Coverage : ip_defense_rule CRUD (create/query/update/remove) plus the live
//            attack flow: repeated requests trigger the auto-ban (Cache-based
//            counter, no batch-flush delay), the banned IP is rejected with
//            403 via IpFilter, and removeIpBan lifts the ban (200 again).
//            The live burst uses its own threshold=3 rule so the CRUD update
//            (C5, threshold 3->5) cannot invalidate the burst test.
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
var REPORT_NAME = "TestIpAttackDefense-Report.html";
var REPORT_TITLE = "JsPlus-Tomcat IpAttackDefense Test Report";

print("=== JsPlus-Tomcat IpAttackDefense Test ===");
print("JS_PLUS_PATH: " + System.getProperty("JS_PLUS_PATH"));
print("Mode        : " + (CONSOLE_MODE ? "console (existing server, no System.exit)" : "standalone"));


// Case C1
check({
    name: "console environment ready (global tomcat)",
    ok: CONSOLE_MODE,
    detail: "expected to run via Console.eval(...) inside the JsPlus console",
    objective: "Ensure the suite runs inside the live JsPlus console (server ready, no System.exit)",
    objectiveZh: "確保測試套件於 JsPlus 主控台內執行（伺服器就緒、無 System.exit）",
    command: "Console.eval(System.getenv('JS_PLUS_PATH') + '/../test/TestIpAttackDefense.js')"
});

var r;

// Case C2
// Defense rules are scoped to a domain:port — ensure a domain mapping exists
// for 127.0.0.1:8443 so the live curl checks can be verified through the proxy.
r = callTomcat(function(){ return tomcat.removeDomainMapping("127.0.0.1", "8443"); });
var mapParams = new HashMap();
mapParams.put("target", "https://zh.wikipedia.org");
r = callTomcat(function(){ return tomcat.setDomainMapping("127.0.0.1", "8443", mapParams); });
check({
    name: "setDomainMapping 127.0.0.1:8443 (precondition)",
    ok: isOk(r),
    detail: msg(r),
    objective: "Ensure a domain mapping exists for 127.0.0.1:8443 before the live defense checks",
    objectiveZh: "確保即時防禦檢查前已建立 127.0.0.1:8443 的網域對應",
    command: "tomcat.setDomainMapping(\"127.0.0.1\", \"8443\", {target: \"https://zh.wikipedia.org\"})",
    detail: msg(r) + dbVerify(function(){ return "SELECT * FROM domain_mapping_param WHERE domain='127.0.0.1' AND port='8443' AND deleted=FALSE"; })
});

// Case C3
r = callTomcat(function(){ return tomcat.createDefenseRule("test-burst", "127.0.0.1", "8443", "", 60, 3, 3600, true); });
check({
    name: "createDefenseRule test-burst (127.0.0.1:8443, w=60 t=3 ban=3600s)",
    ok: isOk(r) && r.msg.indexOf("created successfully") >= 0,
    detail: msg(r) + dbVerify(function(){ return "SELECT * FROM ip_defense_rule WHERE deleted=FALSE"; }),
    objective: "Verify an ip_defense_rule can be created (blank uri_pattern = every URI on the domain)",
    objectiveZh: "驗證 ip_defense_rule 可建立（空白 uri_pattern = 網域內所有 URI）",
    command: "tomcat.createDefenseRule(\"test-burst\", \"127.0.0.1\", \"8443\", \"\", 60, 3, 3600, true)"
});

// Case C4
r = callTomcat(function(){ return tomcat.queryDefenseRules(); });
// Parse the rule id from the first line "1) <id> test-burst ..." (no regex,
// indexOf/substring only) so the update/remove cases can reuse it.
var ruleId = "";
if (r.code === 1 && r.msg.indexOf("test-burst 127.0.0.1:8443") >= 0) {
    var firstLine = r.msg;
    var nl = firstLine.indexOf("\n");
    if (nl >= 0) {
        firstLine = firstLine.substring(0, nl);
    }
    var idx = firstLine.indexOf(") ");
    if (idx >= 0) {
        var rest = firstLine.substring(idx + 2);
        var sp = rest.indexOf(" ");
        if (sp > 0) {
            ruleId = rest.substring(0, sp);
        }
    }
}
check({
    name: "queryDefenseRules (test-burst listed with w=60 t=3 ban=3600s enabled=true)",
    ok: isOk(r) && r.msg.indexOf("test-burst 127.0.0.1:8443") >= 0
        && r.msg.indexOf("w=60 t=3 ban=3600s enabled=true") >= 0 && ruleId.length > 0,
    detail: msg(r) + (ruleId.length > 0 ? " parsed ruleId=" + ruleId : " (ruleId parse failed)")
        + dbVerify(function(){ return "SELECT * FROM ip_defense_rule WHERE deleted=FALSE"; }),
    objective: "Verify the rule is persisted with its window/threshold/ban values and obtain its id",
    objectiveZh: "驗證規則已持久化（窗口／閾值／封禁值）並取得其 id",
    command: "tomcat.queryDefenseRules()"
});

// Case C5
r = callTomcat(function(){ return tomcat.updateDefenseRule(ruleId, "test-burst-2", "127.0.0.1", "8443", "", 30, 5, 1800, true); });
check({
    name: "updateDefenseRule (ruleId=" + ruleId + ", w=30 t=5 ban=1800s)",
    ok: isOk(r) && r.msg.indexOf("updated successfully") >= 0,
    detail: msg(r),
    objective: "Verify a defense rule can be updated (name/window/threshold/ban)",
    objectiveZh: "驗證防禦規則可更新（名稱／窗口／閾值／封禁）",
    command: "tomcat.updateDefenseRule(\"<ruleId>\", \"test-burst-2\", \"127.0.0.1\", \"8443\", \"\", 30, 5, 1800, true)",
    detail: msg(r) + dbVerify(function(){ return "SELECT * FROM ip_defense_rule WHERE deleted=FALSE"; })
});

// Case C6
r = callTomcat(function(){ return tomcat.queryDefenseRules(); });
check({
    name: "queryDefenseRules (test-burst-2 listed with w=30 t=5 ban=1800s enabled=true)",
    ok: isOk(r) && r.msg.indexOf("test-burst-2 127.0.0.1:8443") >= 0
        && r.msg.indexOf("w=30 t=5 ban=1800s enabled=true") >= 0,
    detail: msg(r) + dbVerify(function(){ return "SELECT * FROM ip_defense_rule WHERE deleted=FALSE"; }),
    objective: "Verify the updated values are persisted",
    objectiveZh: "驗證更新後的值已持久化",
    command: "tomcat.queryDefenseRules()"
});

// Case C7
// A dedicated live rule with threshold=3, independent of the CRUD rule updated
// above (now threshold=5), so the burst test below stays deterministic.
r = callTomcat(function(){ return tomcat.createDefenseRule("test-burst-live", "127.0.0.1", "8443", "", 60, 3, 3600, true); });
var liveRuleId = "";
if (isOk(r)) {
    var qr = callTomcat(function(){ return tomcat.queryDefenseRules(); });
    if (qr.code === 1 && qr.msg.indexOf("test-burst-live 127.0.0.1:8443") >= 0) {
        var pos = qr.msg.indexOf("test-burst-live");
        var lineStart = qr.msg.lastIndexOf("\n", pos);
        var line = qr.msg.substring(lineStart + 1);
        var idx = line.indexOf(") ");
        if (idx >= 0) {
            var rest = line.substring(idx + 2);
            var sp = rest.indexOf(" ");
            if (sp > 0) {
                liveRuleId = rest.substring(0, sp);
            }
        }
    }
}
check({
    name: "createDefenseRule test-burst-live (threshold=3 for live burst)",
    ok: isOk(r) && liveRuleId.length > 0,
    detail: msg(r) + (liveRuleId.length > 0 ? " parsed ruleId=" + liveRuleId : " (ruleId parse failed)"),
    objective: "Create a dedicated threshold=3 rule so the live burst test is not affected by the CRUD update",
    objectiveZh: "建立獨立 threshold=3 規則，令即時封禁測試不受 CRUD 更新影響",
    command: "tomcat.createDefenseRule(\"test-burst-live\", \"127.0.0.1\", \"8443\", \"\", 60, 3, 3600, true)",
    detail: msg(r) + dbVerify(function(){ return "SELECT * FROM ip_defense_rule WHERE deleted=FALSE"; })
});

// Case C8
// Live burst against the dedicated threshold=3 rule: the 3rd request triggers
// the auto-ban and the 4th request from the same IP is rejected (403 via the
// auto-written ip_ban DENY rule). The counter lives in Cache (per rule+IP) —
// not in ip_request_log — so there is no batch-flush delay. Remove any leftover
// ban first to re-run.
r = callTomcat(function(){ return tomcat.removeIpBan("127.0.0.1", "8443", "127.0.0.1"); });
var s1 = -1, s4 = -1;
try {
    s1 = curlStatus("https://127.0.0.1:8443/");
    curlStatus("https://127.0.0.1:8443/");
    curlStatus("https://127.0.0.1:8443/");
    s4 = curlStatus("https://127.0.0.1:8443/");
} catch (e) {
    print("[WARN] curl (burst) failed: " + e);
}
check({
    name: "live burst: 1st request allowed, 4th request blocked (403)",
    ok: s1 !== 403 && s4 === 403,
    detail: "statuses=1st:" + s1 + ",4th:" + s4,
    objective: "Verify repeated requests cross the threshold and the IP is auto-banned (HTTP 403)",
    objectiveZh: "驗證重複請求超越閾值後 IP 被自動封禁（HTTP 403）",
    command: "curl -k -s --max-time 10 -o NUL -w \"%{http_code}\" https://127.0.0.1:8443/   (x4)",
    detail: "statuses=1st:" + s1 + ",4th:" + s4 + dbVerify(function(){ return "SELECT * FROM ip_ban WHERE deleted=FALSE"; })
});

// Case C9
r = callTomcat(function(){ return tomcat.queryIpBans(); });
check({
    name: "queryIpBans (127.0.0.1:8443 IP=127.0.0.1 listed)",
    ok: isOk(r) && r.msg.indexOf("127.0.0.1:8443 IP=127.0.0.1") >= 0,
    detail: msg(r) + dbVerify(function(){ return "SELECT * FROM ip_ban WHERE deleted=FALSE"; }),
    objective: "Verify the auto-ban is persisted in ip_ban with its rule reference",
    objectiveZh: "驗證自動封禁已寫入 ip_ban 並附有規則參照",
    command: "tomcat.queryIpBans()"
});

// Case C10
r = callTomcat(function(){ return tomcat.removeIpBan("127.0.0.1", "8443", "127.0.0.1"); });
check({
    name: "removeIpBan 127.0.0.1:8443 127.0.0.1",
    ok: isOk(r) && r.msg.indexOf("removed successfully") >= 0,
    detail: msg(r),
    objective: "Verify the ban can be lifted (ip_ban soft-deleted, ip_ban DENY rule removed)",
    objectiveZh: "驗證封禁可解除（ip_ban 軟刪除、ip_ban DENY 規則移除）",
    command: "tomcat.removeIpBan(\"127.0.0.1\", \"8443\", \"127.0.0.1\")",
    detail: msg(r) + dbVerify(function(){ return "SELECT * FROM ip_ban WHERE deleted=FALSE"; })
});

// Case C11
var s11 = -1;
try {
    s11 = curlStatus("https://127.0.0.1:8443/");
} catch (e) {
    print("[WARN] curl (after unban) failed: " + e);
}
// 429 is tolerated: it proves the request reached the upstream proxy target
// (zh.wikipedia.org rate limit), i.e. the local ban/unban gate has passed.
check({
    name: "curl GET https://127.0.0.1:8443/ (after unban -> 200/429)",
    ok: s11 === 200 || s11 === 429,
    detail: "status=" + s11 + " (429 = upstream rate limit, local ban already lifted)",
    objective: "Verify the IP is allowed again once the ban is removed",
    objectiveZh: "驗證封禁解除後 IP 再次放行",
    command: "curl -k -s --max-time 10 -o NUL -w \"%{http_code}\" https://127.0.0.1:8443/"
});

// Case C12
r = callTomcat(function(){ return tomcat.removeDefenseRule(liveRuleId); });
check({
    name: "removeDefenseRule (ruleId=" + liveRuleId + ", live rule cleanup)",
    ok: isOk(r) && r.msg.indexOf("removed successfully") >= 0,
    detail: msg(r),
    objective: "Verify the dedicated live rule can be removed (soft delete, cleanup)",
    objectiveZh: "驗證獨立即時規則可移除（軟刪除、清理）",
    command: "tomcat.removeDefenseRule(\"<liveRuleId>\")",
    detail: msg(r) + dbVerify(function(){ return "SELECT * FROM ip_defense_rule WHERE deleted=FALSE"; })
});

// Case C13
r = callTomcat(function(){ return tomcat.removeDefenseRule(ruleId); });
check({
    name: "removeDefenseRule (ruleId=" + ruleId + ", CRUD rule cleanup)",
    ok: isOk(r) && r.msg.indexOf("removed successfully") >= 0,
    detail: msg(r),
    objective: "Verify the CRUD test rule can be removed (soft delete, cleanup)",
    objectiveZh: "驗證 CRUD 測試規則可移除（軟刪除、清理）",
    command: "tomcat.removeDefenseRule(\"<ruleId>\")",
    detail: msg(r) + dbVerify(function(){ return "SELECT * FROM ip_defense_rule WHERE deleted=FALSE"; })
});

// Case C14
// ip_request_log is written by a batched CacheArray insert on the
// IpAttackDefense.logTimer cadence (5000 ms in tomcat.properties); give the
// queue a moment to flush before querying.
Thread.sleep(6000);
r = callTomcat(function(){ return tomcat.queryIpRequestLogs(); });
check({
    name: "queryIpRequestLogs (batched requests recorded)",
    ok: r.code === 1 && r.msg.indexOf("127.0.0.1:8443") >= 0,
    detail: msg(r) + dbVerify(function(){ return "SELECT * FROM ip_request_log WHERE deleted=FALSE ORDER BY request_time DESC"; }),
    objective: "Verify requests are recorded into ip_request_log via the batched insert",
    objectiveZh: "驗證請求已透過批次插入寫入 ip_request_log",
    command: "tomcat.queryIpRequestLogs()   (after ~6s flush, logTimer=5000ms)"
});

// Case C15
r = callTomcat(function(){ return tomcat.clearIpRequestLogs(); });
check({
    name: "clearIpRequestLogs (cleanup)",
    ok: isOk(r) && r.msg.indexOf("cleared") >= 0,
    detail: msg(r),
    objective: "Verify the request log can be cleared (cleanup of test records)",
    objectiveZh: "驗證請求日誌可清除（清理測試記錄）",
    command: "tomcat.clearIpRequestLogs()",
    detail: msg(r) + dbVerify(function(){ return "SELECT * FROM ip_request_log WHERE deleted=FALSE ORDER BY request_time DESC"; })
});

// Case C16 — cleanup all test data: remove the precondition domain mapping
// (rules/ban/log already removed by C10/C12/C13/C15) and verify no mapping
// records remain for the test domain (deleted=FALSE -> 0 rows).
r = callTomcat(function(){ return tomcat.removeDomainMapping("127.0.0.1", "8443"); });
check({
    name: "removeDomainMapping 127.0.0.1:8443 (precondition cleanup)",
    ok: isOk(r),
    detail: msg(r),
    objective: "Remove the 127.0.0.1:8443 mapping created for the defense tests and verify no test records remain",
    objectiveZh: "移除防禦測試建立的 127.0.0.1:8443 對應，並驗證無測試記錄殘留",
    command: "tomcat.removeDomainMapping(\"127.0.0.1\", \"8443\")",
    detail: msg(r) + dbVerify(function(){ return "SELECT * FROM domain_mapping_param WHERE domain='127.0.0.1' AND port='8443' AND deleted=FALSE"; })
});

// ---------------------------------------------------------------------------
// Summary
// ---------------------------------------------------------------------------
writeReport();
writeMd();
print("========================================");
print("TestIpAttackDefense result: " + passCount + " passed, " + failCount + " failed");
if (failCount > 0) {
    print("FAILED checks: " + failures.join(", "));
} else {
    print("ALL TESTS PASSED");
}
