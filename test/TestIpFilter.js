// ============================================================================
// JsPlus-Tomcat IpFilter Test Suite (console mode)
// ----------------------------------------------------------------------------
// Run      : Inside the JsPlus console (server already running):
//              Console.eval(<path to this file>);
//            No bat/sh launcher needed — the console environment provides the
//            running Tomcat, the H2 data source and the global `tomcat` object.
// Harness  : Shared plumbing (Java bindings, counters, check/isOk/msg and
//            writeReport) lives in TestBase.js and is loaded below.
// Output   : <PROJECT_ROOT>/test_reports/TestIpFilter-Report.html (green PASS / red FAIL per
//            check), written with BaseFile (DiskFile). Each check prints
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
//            Rule lookups are cached (default 30 s). create/update/remove
//            invalidate the cache automatically, but setting 0 makes the live
//            curl checks (C9/C11) read the DB unconditionally.
// Coverage : domain_ip_rule CRUD (create/query/queryAll/update/remove) plus
//            live deny/allow checks via curl (403 while denied, 200 after
//            the deny rule is removed). createDomainIpRule invalidates the
//            in-memory rule cache, so live checks take effect immediately.
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
var REPORT_NAME = "TestIpFilter-Report.html";
var REPORT_TITLE = "JsPlus-Tomcat IpFilter Test Report";

print("=== JsPlus-Tomcat IpFilter Test ===");
print("JS_PLUS_PATH: " + System.getProperty("JS_PLUS_PATH"));
print("Mode        : " + (CONSOLE_MODE ? "console (existing server, no System.exit)" : "standalone"));


// Case C1
check({
    name: "console environment ready (global tomcat)",
    ok: CONSOLE_MODE,
    detail: "expected to run via Console.eval(...) inside the JsPlus console",
    objective: "Ensure the suite runs inside the live JsPlus console (server ready, no System.exit)",
    objectiveZh: "確保測試套件於 JsPlus 主控台內執行（伺服器就緒、無 System.exit）",
    command: "Console.eval(System.getenv('JS_PLUS_PATH') + '/../test/TestIpFilter.js')"
});

var r;

// Case C2
// IpFilter rules are scoped to a domain:port — ensure a domain mapping exists
// for 127.0.0.1:8443 so the live curl checks can be verified through the proxy.
r = callTomcat(function(){ return tomcat.removeDomainMapping("127.0.0.1", "8443"); });
var mapParams = new HashMap();
mapParams.put("target", "https://zh.wikipedia.org");
r = callTomcat(function(){ return tomcat.setDomainMapping("127.0.0.1", "8443", mapParams); });
check({
    name: "setDomainMapping 127.0.0.1:8443 (precondition)",
    ok: isOk(r),
    detail: msg(r),
    objective: "Ensure a domain mapping exists for 127.0.0.1:8443 before IP rule tests",
    objectiveZh: "確保 IP 規則測試前已建立 127.0.0.1:8443 的網域對應",
    command: "tomcat.setDomainMapping(\"127.0.0.1\", \"8443\", {target: \"https://zh.wikipedia.org\"})",
    detail: msg(r) + dbVerify(function(){ return "SELECT * FROM domain_mapping_param WHERE domain='127.0.0.1' AND port='8443' AND deleted=FALSE"; })
});

// Case C3
r = callTomcat(function(){ return tomcat.createDomainIpRule("127.0.0.1", "8443", "ALLOW", "10.0.0.1"); });
check({
    name: "createDomainIpRule 127.0.0.1:8443 [ALLOW] 10.0.0.1",
    ok: isOk(r) && r.msg.indexOf("created successfully") >= 0,
    detail: msg(r),
    objective: "Verify a manual ALLOW rule can be created for the test domain",
    objectiveZh: "驗證可為測試網域建立手動 ALLOW 規則",
    command: "tomcat.createDomainIpRule(\"127.0.0.1\", \"8443\", \"ALLOW\", \"10.0.0.1\")",
    detail: msg(r) + dbVerify(function(){ return "SELECT * FROM domain_ip_rule WHERE domain='127.0.0.1' AND port='8443' AND rule_type='ALLOW' AND ip_pattern='10.0.0.1' AND deleted=FALSE"; })
});

// Case C4
r = callTomcat(function(){ return tomcat.queryDomainIpRule("127.0.0.1", "8443", "ALLOW", "10.0.0.1"); });
check({
    name: "queryDomainIpRule 127.0.0.1:8443 [ALLOW] 10.0.0.1 (manual source)",
    ok: isOk(r) && r.msg.indexOf("IpPattern: 10.0.0.1") >= 0 && r.msg.indexOf("Source: manual") >= 0,
    detail: msg(r) + dbVerify(function(){ return "SELECT * FROM domain_ip_rule WHERE domain='127.0.0.1' AND port='8443' AND rule_type='ALLOW' AND ip_pattern='10.0.0.1' AND deleted=FALSE"; }),
    objective: "Verify the rule is persisted and identified as a manual rule (Source: manual)",
    objectiveZh: "驗證規則已持久化並標示為手動規則（Source: manual）",
    command: "tomcat.queryDomainIpRule(\"127.0.0.1\", \"8443\", \"ALLOW\", \"10.0.0.1\")"
});

// Case C5
r = callTomcat(function(){ return tomcat.queryDomainIpRules(); });
check({
    name: "queryDomainIpRules (127.0.0.1:8443 [ALLOW] 10.0.0.1 listed)",
    ok: isOk(r) && r.msg.indexOf("127.0.0.1:8443 [ALLOW] 10.0.0.1 (manual)") >= 0,
    detail: msg(r) + dbVerify(function(){ return "SELECT * FROM domain_ip_rule WHERE deleted=FALSE"; }),
    objective: "Verify all IP rules are listed and include the test rule with its manual source",
    objectiveZh: "驗證所有 IP 規則已列出且包含測試規則及其手動來源",
    command: "tomcat.queryDomainIpRules()"
});

// Case C6
r = callTomcat(function(){ return tomcat.updateDomainIpRule("127.0.0.1", "8443", "ALLOW", "10.0.0.1", "10.0.0.2"); });
check({
    name: "updateDomainIpRule 127.0.0.1:8443 [ALLOW] 10.0.0.1 -> 10.0.0.2",
    ok: isOk(r),
    detail: msg(r),
    objective: "Verify the rule IP pattern can be updated",
    objectiveZh: "驗證規則 IP 模式可更新",
    command: "tomcat.updateDomainIpRule(\"127.0.0.1\", \"8443\", \"ALLOW\", \"10.0.0.1\", \"10.0.0.2\")",
    detail: msg(r) + dbVerify(function(){ return "SELECT * FROM domain_ip_rule WHERE domain='127.0.0.1' AND port='8443' AND rule_type='ALLOW' AND ip_pattern='10.0.0.2' AND deleted=FALSE"; })
});

// Case C7
r = callTomcat(function(){ return tomcat.queryDomainIpRule("127.0.0.1", "8443", "ALLOW", "10.0.0.2"); });
check({
    name: "queryDomainIpRule 127.0.0.1:8443 [ALLOW] 10.0.0.2 (updated)",
    ok: isOk(r) && r.msg.indexOf("IpPattern: 10.0.0.2") >= 0,
    detail: msg(r) + dbVerify(function(){ return "SELECT * FROM domain_ip_rule WHERE domain='127.0.0.1' AND port='8443' AND rule_type='ALLOW' AND ip_pattern='10.0.0.2' AND deleted=FALSE"; }),
    objective: "Verify the updated IP pattern is persisted",
    objectiveZh: "驗證更新後的 IP 模式已持久化",
    command: "tomcat.queryDomainIpRule(\"127.0.0.1\", \"8443\", \"ALLOW\", \"10.0.0.2\")"
});

// Case C8
r = callTomcat(function(){ return tomcat.removeDomainIpRule("127.0.0.1", "8443", "ALLOW", "10.0.0.2"); });
check({
    name: "removeDomainIpRule 127.0.0.1:8443 [ALLOW] 10.0.0.2",
    ok: isOk(r),
    detail: msg(r),
    objective: "Verify the manual ALLOW rule can be removed (cleanup)",
    objectiveZh: "驗證手動 ALLOW 規則可移除（清理）",
    command: "tomcat.removeDomainIpRule(\"127.0.0.1\", \"8443\", \"ALLOW\", \"10.0.0.2\")",
    detail: msg(r) + dbVerify(function(){ return "SELECT * FROM domain_ip_rule WHERE deleted=FALSE"; })
});

// Case C9
// Deny the local address and verify the filter blocks the live request (403).
// createDomainIpRule invalidates the rule cache, so this takes effect at once.
r = callTomcat(function(){ return tomcat.createDomainIpRule("127.0.0.1", "8443", "DENY", "127.0.0.1"); });
var denyRuleOk = isOk(r);
var deniedStatus = -1;
var deniedOk = false;
if (denyRuleOk) {
    try {
        deniedStatus = curlStatus("https://127.0.0.1:8443/");
        deniedOk = (deniedStatus === 403);
    } catch (e) {
        print("[WARN] curl (denied) failed: " + e);
    }
}
check({
    name: "curl GET https://127.0.0.1:8443/ (DENY 127.0.0.1 -> 403)",
    ok: denyRuleOk && deniedOk,
    detail: "rule=" + msg(r) + " status=" + deniedStatus,
    objective: "Verify IpFilter blocks a request from a denied IP (HTTP 403)",
    objectiveZh: "驗證 IpFilter 攔截來自被拒 IP 的請求（HTTP 403）",
    command: "tomcat.createDomainIpRule(\"127.0.0.1\", \"8443\", \"DENY\", \"127.0.0.1\")  |  curl -k -s -o NUL -w \"%{http_code}\" https://127.0.0.1:8443/",
    detail: "rule=" + msg(r) + " status=" + deniedStatus + dbVerify(function(){ return "SELECT * FROM domain_ip_rule WHERE domain='127.0.0.1' AND port='8443' AND rule_type='DENY' AND ip_pattern='127.0.0.1' AND deleted=FALSE"; })
});

// Case C10 — ALLOW wins over DENY for the same IP: while the DENY rule from
// C9 is still active, create an ALLOW rule for the same IP. A matching ALLOW
// rule is evaluated first, so the request must pass despite the DENY rule.
r = callTomcat(function(){ return tomcat.createDomainIpRule("127.0.0.1", "8443", "ALLOW", "127.0.0.1"); });
check({
    name: "createDomainIpRule 127.0.0.1:8443 [ALLOW] 127.0.0.1 (conflicts with DENY)",
    ok: isOk(r),
    detail: msg(r) + dbVerify(function(){ return "SELECT * FROM domain_ip_rule WHERE domain='127.0.0.1' AND port='8443' AND rule_type IN ('ALLOW','DENY') AND ip_pattern='127.0.0.1' AND deleted=FALSE"; }),
    objective: "Create an ALLOW rule for 127.0.0.1 while the DENY rule from C9 is still active (both coexist)",
    objectiveZh: "在 C9 的 DENY 規則仍生效時，建立同一 IP 的 ALLOW 規則（兩者並存）",
    command: "tomcat.createDomainIpRule(\"127.0.0.1\", \"8443\", \"ALLOW\", \"127.0.0.1\")"
});

// Case C11 — live check: with DENY + ALLOW for the same IP, the request must
// be allowed (200, or 429 = reached upstream = the local allow gate passed).
var conflictStatus = -1;
var conflictOk = false;
try {
    conflictStatus = curlStatus("https://127.0.0.1:8443/");
    conflictOk = (conflictStatus === 200 || conflictStatus === 429);
} catch (e) {
    print("[WARN] curl (allow-wins) failed: " + e);
}
check({
    name: "curl GET https://127.0.0.1:8443/ (DENY + ALLOW -> ALLOW wins)",
    ok: conflictOk,
    detail: "status=" + conflictStatus + " (429 = upstream rate limit, local gate passed)",
    objective: "Verify a matching ALLOW rule takes precedence over a DENY rule for the same IP",
    objectiveZh: "驗證同一 IP 之 ALLOW 規則優先於 DENY 規則（ALLOW 命中即放行）",
    command: "curl -k -s -o NUL -w \"%{http_code}\" https://127.0.0.1:8443/",
    detail: "status=" + conflictStatus + dbVerify(function(){ return "SELECT * FROM domain_ip_rule WHERE domain='127.0.0.1' AND port='8443' AND rule_type IN ('ALLOW','DENY') AND ip_pattern='127.0.0.1' AND deleted=FALSE"; })
});

// Case C12 — remove the ALLOW rule again: only the DENY rule from C9 remains.
r = callTomcat(function(){ return tomcat.removeDomainIpRule("127.0.0.1", "8443", "ALLOW", "127.0.0.1"); });
check({
    name: "removeDomainIpRule 127.0.0.1:8443 [ALLOW] 127.0.0.1 (back to DENY-only)",
    ok: isOk(r),
    detail: msg(r) + dbVerify(function(){ return "SELECT * FROM domain_ip_rule WHERE domain='127.0.0.1' AND port='8443' AND rule_type='DENY' AND ip_pattern='127.0.0.1' AND deleted=FALSE"; }),
    objective: "Remove the ALLOW rule and verify only the C9 DENY rule remains",
    objectiveZh: "移除 ALLOW 規則，驗證僅剩 C9 的 DENY 規則",
    command: "tomcat.removeDomainIpRule(\"127.0.0.1\", \"8443\", \"ALLOW\", \"127.0.0.1\")"
});

// Case C13 — live check: with the ALLOW rule gone, the DENY rule takes effect
// again and the request is blocked locally (403, no upstream request).
var denyAgainStatus = -1;
var denyAgainOk = false;
try {
    denyAgainStatus = curlStatus("https://127.0.0.1:8443/");
    denyAgainOk = (denyAgainStatus === 403);
} catch (e) {
    print("[WARN] curl (deny-again) failed: " + e);
}
check({
    name: "curl GET https://127.0.0.1:8443/ (ALLOW removed -> DENY blocks again, 403)",
    ok: denyAgainOk,
    detail: "status=" + denyAgainStatus,
    objective: "Verify the DENY rule regains effect once the conflicting ALLOW rule is removed (HTTP 403)",
    objectiveZh: "驗證移除 ALLOW 後 DENY 規則重新生效（HTTP 403）",
    command: "curl -k -s -o NUL -w \"%{http_code}\" https://127.0.0.1:8443/",
    detail: "status=" + denyAgainStatus + dbVerify(function(){ return "SELECT * FROM domain_ip_rule WHERE domain='127.0.0.1' AND port='8443' AND rule_type='DENY' AND ip_pattern='127.0.0.1' AND deleted=FALSE"; })
});

// Case C14
r = callTomcat(function(){ return tomcat.removeDomainIpRule("127.0.0.1", "8443", "DENY", "127.0.0.1"); });
check({
    name: "removeDomainIpRule 127.0.0.1:8443 [DENY] 127.0.0.1",
    ok: isOk(r),
    detail: msg(r),
    objective: "Verify the DENY rule can be removed",
    objectiveZh: "驗證 DENY 規則可移除",
    command: "tomcat.removeDomainIpRule(\"127.0.0.1\", \"8443\", \"DENY\", \"127.0.0.1\")",
    detail: msg(r) + dbVerify(function(){ return "SELECT * FROM domain_ip_rule WHERE deleted=FALSE"; })
});

// Case C15
var allowedStatus = -1;
var allowedOk = false;
try {
    allowedStatus = curlStatus("https://127.0.0.1:8443/");
    allowedOk = (allowedStatus === 200);
} catch (e) {
    print("[WARN] curl (allowed) failed: " + e);
}
check({
    name: "curl GET https://127.0.0.1:8443/ (after DENY removed -> 200)",
    ok: allowedOk,
    detail: "status=" + allowedStatus,
    objective: "Verify the request is allowed again once the DENY rule is removed (HTTP 200)",
    objectiveZh: "驗證 DENY 規則移除後請求再次放行（HTTP 200）",
    command: "curl -k -s -o NUL -w \"%{http_code}\" https://127.0.0.1:8443/",
    detail: "status=" + allowedStatus + dbVerify(function(){ return "SELECT * FROM domain_ip_rule WHERE deleted=FALSE"; })
});

// ---------------------------------------------------------------------------
// URI-scoped IP rules (uri column): a rule scoped to domain:port/uri is
// matched by longest-prefix on the request path; requests outside the uri
// fall back to the catch-all (blank-uri) rules / no rules.
// ---------------------------------------------------------------------------

// Case C16 — create a uri-scoped DENY rule for /admin only.
r = callTomcat(function(){ return tomcat.createDomainIpRule("127.0.0.1", "8443", "/admin", "DENY", "127.0.0.1"); });
check({
    name: "createDomainIpRule 127.0.0.1:8443 uri=/admin [DENY] 127.0.0.1",
    ok: isOk(r),
    detail: msg(r) + dbVerify(function(){ return "SELECT * FROM domain_ip_rule WHERE domain='127.0.0.1' AND port='8443' AND uri='/admin' AND rule_type='DENY' AND ip_pattern='127.0.0.1' AND deleted=FALSE"; }),
    objective: "Verify a uri-scoped DENY rule 127.0.0.1:8443/admin for 127.0.0.1 is created and persisted",
    objectiveZh: "驗證可建立並持久化 127.0.0.1:8443/admin 之 URI 範圍 DENY 規則",
    command: "tomcat.createDomainIpRule(\"127.0.0.1\", \"8443\", \"/admin\", \"DENY\", \"127.0.0.1\")"
});

// Case C17 — query the uri-scoped rule (Uri shown in the result).
r = callTomcat(function(){ return tomcat.queryDomainIpRule("127.0.0.1", "8443", "/admin", "DENY", "127.0.0.1"); });
check({
    name: "queryDomainIpRule 127.0.0.1:8443 uri=/admin [DENY] 127.0.0.1 (Uri shown)",
    ok: isOk(r) && r.msg.indexOf("Uri: /admin") >= 0,
    detail: msg(r),
    objective: "Verify the uri-scoped rule is queryable and reports the uri",
    objectiveZh: "驗證 URI 範圍規則可查詢且顯示 uri",
    command: "tomcat.queryDomainIpRule(\"127.0.0.1\", \"8443\", \"/admin\", \"DENY\", \"127.0.0.1\")"
});

// Case C18 — live routing: /admin/ must be blocked by the uri-scoped DENY
// (403), while / (outside the uri) must NOT be blocked (any non-403 upstream
// response proves the request passed IpFilter).
var adminDeniedStatus = -1;
var adminDeniedOk = false;
var outsideAllowedStatus = -1;
var outsideAllowedOk = false;
try {
    adminDeniedStatus = curlStatus("https://127.0.0.1:8443/admin/");
    adminDeniedOk = (adminDeniedStatus === 403);
    outsideAllowedStatus = curlStatus("https://127.0.0.1:8443/");
    outsideAllowedOk = (outsideAllowedStatus > 0 && outsideAllowedStatus !== 403);
} catch (e) {
    print("[WARN] curl (uri-scoped) failed: " + e);
}
check({
    name: "curl /admin/ -> 403 (uri DENY), curl / -> not 403 (outside uri)",
    ok: adminDeniedOk && outsideAllowedOk,
    detail: "adminStatus=" + adminDeniedStatus + " rootStatus=" + outsideAllowedStatus,
    objective: "Verify the uri-scoped DENY blocks /admin/ (403) while requests outside the uri remain allowed",
    objectiveZh: "驗證 URI 範圍 DENY 阻擋 /admin/（403），而 uri 以外之請求不受影響",
    command: "curl -k -s -o NUL -w \"%{http_code}\" https://127.0.0.1:8443/admin/  |  curl -k -s -o NUL -w \"%{http_code}\" https://127.0.0.1:8443/",
    detail: "adminStatus=" + adminDeniedStatus + " rootStatus=" + outsideAllowedStatus + dbVerify(function(){ return "SELECT * FROM domain_ip_rule WHERE domain='127.0.0.1' AND port='8443' AND uri='/admin' AND rule_type='DENY' AND ip_pattern='127.0.0.1' AND deleted=FALSE"; })
});

// Case C19 — cleanup: remove the uri-scoped rule and verify it is gone.
r = callTomcat(function(){ return tomcat.removeDomainIpRule("127.0.0.1", "8443", "/admin", "DENY", "127.0.0.1"); });
check({
    name: "removeDomainIpRule 127.0.0.1:8443 uri=/admin [DENY] 127.0.0.1 (cleanup)",
    ok: isOk(r),
    detail: msg(r) + dbVerify(function(){ return "SELECT * FROM domain_ip_rule WHERE domain='127.0.0.1' AND port='8443' AND uri='/admin' AND deleted=FALSE"; }),
    objective: "Remove the uri-scoped rule created for this test and verify no test rules remain",
    objectiveZh: "移除本次測試建立的 /admin URI 範圍規則，並驗證無測試規則殘留",
    command: "tomcat.removeDomainIpRule(\"127.0.0.1\", \"8443\", \"/admin\", \"DENY\", \"127.0.0.1\")"
});

// Case C20 — cleanup all test data: remove the precondition domain mapping
// (ALLOW/DENY rules already removed by C8/C10/C15) and verify no mapping
// records remain for the test domain (deleted=FALSE -> 0 rows).
r = callTomcat(function(){ return tomcat.removeDomainMapping("127.0.0.1", "8443"); });
check({
    name: "removeDomainMapping 127.0.0.1:8443 (precondition cleanup)",
    ok: isOk(r),
    detail: msg(r),
    objective: "Remove the 127.0.0.1:8443 mapping created for the IP rule tests and verify no test records remain",
    objectiveZh: "移除 IP 規則測試建立的 127.0.0.1:8443 對應，並驗證無測試記錄殘留",
    command: "tomcat.removeDomainMapping(\"127.0.0.1\", \"8443\")",
    detail: msg(r) + dbVerify(function(){ return "SELECT * FROM domain_mapping_param WHERE domain='127.0.0.1' AND port='8443' AND deleted=FALSE"; })
});

// ---------------------------------------------------------------------------
// Summary
// ---------------------------------------------------------------------------
writeReport();
writeMd();
print("========================================");
print("TestIpFilter result: " + passCount + " passed, " + failCount + " failed");
if (failCount > 0) {
    print("FAILED checks: " + failures.join(", "));
} else {
    print("ALL TESTS PASSED");
}
