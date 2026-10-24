// ============================================================================
// JsPlus-Tomcat SecurityFilter Test Suite (console mode)
// ----------------------------------------------------------------------------
// Run      : Inside the JsPlus console (server already running):
//              Console.eval(<path to this file>);
//            No bat/sh launcher needed — the console environment provides the
//            running Tomcat, the H2 data source and the global `tomcat` object.
// Harness  : Shared plumbing (Java bindings, counters, check/isOk/msg and
//            writeReport) lives in TestBase.js and is loaded below.
// Output   : <PROJECT_ROOT>/test_reports/TestSecurityFilter-Report.html (green PASS / red FAIL
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
//              SecurityFilter.cacheSeconds=0
//            The 60s default auth-enable cache would otherwise make the live
//            curl checks (C10-C12) read stale state after create/update.
// Coverage : domain_auth CRUD (create/query/queryAll/update/remove) plus
//            domain_auth_user CRUD (create/query/queryAll/update/remove).
//            Uses the same 127.0.0.1:8443 test mapping as TestDomainProxy.
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

// A real browser User-Agent for the live curl checks (C10-C12): the proxied
// upstream (zh.wikipedia.org) rate-limits generic-UA and rapid requests with
// HTTP 429, which would make the auth checks flaky.
var CURL_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36";

// Report file name and title for this suite (consumed by writeReport in TestBase.js).
var REPORT_NAME = "TestSecurityFilter-Report.html";
var REPORT_TITLE = "JsPlus-Tomcat SecurityFilter Test Report";

print("=== JsPlus-Tomcat SecurityFilter Test ===");
print("JS_PLUS_PATH: " + System.getProperty("JS_PLUS_PATH"));
print("Mode        : " + (CONSOLE_MODE ? "console (existing server, no System.exit)" : "standalone"));


// Case C1
check({
    name: "console environment ready (global tomcat)",
    ok: CONSOLE_MODE,
    detail: "expected to run via Console.eval(...) inside the JsPlus console",
    objective: "Ensure the suite runs inside the live JsPlus console (server ready, no System.exit)",
    objectiveZh: "確保測試套件於 JsPlus 主控台內執行（伺服器就緒、無 System.exit）",
    command: "Console.eval(System.getenv('JS_PLUS_PATH') + '/../test/TestSecurityFilter.js')"
});

var r;

// Case C2
// SecurityFilter auth is scoped to a domain:port — ensure a domain mapping
// exists for 127.0.0.1:8443 first. Clear any leftover first so this case is
// repeatable.
r = callTomcat(function(){ return tomcat.removeDomainMapping("127.0.0.1", "8443"); });
var mapParams = new HashMap();
mapParams.put("target", "https://zh.wikipedia.org");
mapParams.put("scriptEnable", false);
r = callTomcat(function(){ return tomcat.setDomainMapping("127.0.0.1", "8443", mapParams); });
check({
    name: "setDomainMapping 127.0.0.1:8443 (precondition)",
    ok: isOk(r),
    detail: msg(r),
    objective: "Ensure a domain mapping exists for 127.0.0.1:8443 before auth tests",
    objectiveZh: "確保 auth 測試前已建立 127.0.0.1:8443 的網域對應",
    command: "tomcat.setDomainMapping(\"127.0.0.1\", \"8443\", {target: \"https://zh.wikipedia.org\", scriptEnable: false})",
    detail: msg(r) + dbVerify(function(){ return "SELECT * FROM domain_mapping_param WHERE domain='127.0.0.1' AND port='8443' AND deleted=FALSE"; })
});

// Case C3
r = callTomcat(function(){ return tomcat.createDomainAuth("127.0.0.1", "8443", true); });
check({
    name: "createDomainAuth 127.0.0.1:8443 (auth enabled)",
    ok: isOk(r),
    detail: msg(r),
    objective: "Verify a domain_auth record (127.0.0.1:8443, auth_enable=true) can be created",
    objectiveZh: "驗證 domain_auth 記錄（127.0.0.1:8443、auth_enable=true）可建立",
    command: "tomcat.createDomainAuth(\"127.0.0.1\", \"8443\", true)",
    detail: msg(r) + dbVerify(function(){ return "SELECT * FROM domain_auth WHERE domain='127.0.0.1' AND port='8443' AND deleted=FALSE"; })
});

// Case C4
r = callTomcat(function(){ return tomcat.queryDomainAuth("127.0.0.1", "8443"); });
check({
    name: "queryDomainAuth 127.0.0.1:8443 (auth enabled)",
    ok: isOk(r) && r.msg.indexOf("AuthEnable: true") >= 0,
    detail: msg(r) + dbVerify(function(){ return "SELECT * FROM domain_auth WHERE domain='127.0.0.1' AND port='8443' AND deleted=FALSE"; }),
    objective: "Verify the auth record is persisted with auth_enable=true",
    objectiveZh: "驗證 auth 記錄已持久化且 auth_enable=true",
    command: "tomcat.queryDomainAuth(\"127.0.0.1\", \"8443\")"
});

// Case C5
r = callTomcat(function(){ return tomcat.queryDomainAuths(); });
check({
    name: "queryDomainAuths (127.0.0.1:8443 listed)",
    ok: isOk(r) && r.msg.indexOf("127.0.0.1:8443") >= 0,
    detail: msg(r) + dbVerify(function(){ return "SELECT * FROM domain_auth WHERE deleted=FALSE"; }),
    objective: "Verify all domain auth records are listed and include the test record",
    objectiveZh: "驗證所有網域 auth 記錄已列出且包含測試記錄",
    command: "tomcat.queryDomainAuths()"
});

// Case C6
r = callTomcat(function(){ return tomcat.createDomainAuthUser("127.0.0.1", "8443", "testuser", "testpass123"); });
check({
    name: "createDomainAuthUser 127.0.0.1:8443.testuser",
    ok: isOk(r),
    detail: msg(r),
    objective: "Verify a domain_auth_user can be created under the auth record",
    objectiveZh: "驗證 auth 記錄下可建立 domain_auth_user",
    command: "tomcat.createDomainAuthUser(\"127.0.0.1\", \"8443\", \"testuser\", \"testpass123\")",
    detail: msg(r) + dbVerify(function(){ return "SELECT * FROM domain_auth_user WHERE username='testuser' AND deleted=FALSE"; })
});

// Case C7
r = callTomcat(function(){ return tomcat.queryDomainAuthUser("127.0.0.1", "8443", "testuser"); });
check({
    name: "queryDomainAuthUser 127.0.0.1:8443.testuser",
    ok: isOk(r) && r.msg.indexOf("Username: testuser") >= 0,
    detail: msg(r) + dbVerify(function(){ return "SELECT * FROM domain_auth_user WHERE username='testuser' AND deleted=FALSE"; }),
    objective: "Verify the auth user is persisted with the correct username",
    objectiveZh: "驗證 auth 使用者已持久化且使用者名稱正確",
    command: "tomcat.queryDomainAuthUser(\"127.0.0.1\", \"8443\", \"testuser\")"
});

// Case C8
r = callTomcat(function(){ return tomcat.queryDomainAuthUsers("127.0.0.1", "8443"); });
check({
    name: "queryDomainAuthUsers 127.0.0.1:8443 (testuser listed)",
    ok: isOk(r) && r.msg.indexOf("testuser") >= 0,
    detail: msg(r) + dbVerify(function(){ return "SELECT * FROM domain_auth_user WHERE deleted=FALSE"; }),
    objective: "Verify all auth users of the domain are listed and include testuser",
    objectiveZh: "驗證該網域所有 auth 使用者已列出且包含 testuser",
    command: "tomcat.queryDomainAuthUsers(\"127.0.0.1\", \"8443\")"
});

// Case C9
r = callTomcat(function(){ return tomcat.updateDomainAuthUser("127.0.0.1", "8443", "testuser", "newpass456"); });
check({
    name: "updateDomainAuthUser 127.0.0.1:8443.testuser (new password)",
    ok: isOk(r) && r.msg.indexOf("updated successfully") >= 0,
    detail: msg(r),
    objective: "Verify the auth user password can be updated (re-encrypted)",
    objectiveZh: "驗證 auth 使用者密碼可更新（重新加密）",
    command: "tomcat.updateDomainAuthUser(\"127.0.0.1\", \"8443\", \"testuser\", \"newpass456\")",
    detail: msg(r) + dbVerify(function(){ return "SELECT * FROM domain_auth_user WHERE username='testuser' AND deleted=FALSE"; })
});

// Case C10
// Requires SecurityFilter.cacheSeconds=0 (see header) — the filter reads the
// auth-enable flag from the DB directly, so createDomainAuth above takes
// effect immediately for this live request.
// NOTE: all curls below send a real browser User-Agent and are spaced out by
// 1s sleeps; the proxied upstream (zh.wikipedia.org) rate-limits requests with
// a generic UA and bursts of rapid hits (429), which would otherwise make the
// suite flaky.
var statusCode = -1;
var unauthOk = false;
try {
    statusCode = parseInt(runCmd(["curl", "-k", "-s", "-o", NULL_DEVICE, "-w", "%{http_code}", "-A", CURL_USER_AGENT, "https://127.0.0.1:8443/"]).trim());
    unauthOk = (statusCode === 401);
} catch (e) {
    print("[WARN] curl (no credentials) failed: " + e);
}
check({
    name: "curl GET https://127.0.0.1:8443/ (no credentials -> 401)",
    ok: unauthOk,
    detail: "status=" + statusCode,
    objective: "Verify SecurityFilter rejects requests without credentials (HTTP 401)",
    objectiveZh: "驗證 SecurityFilter 拒絕無憑證請求（HTTP 401）",
    command: "curl -k -s -o NUL -w \"%{http_code}\" -A \"<browser UA>\" https://127.0.0.1:8443/"
});
Thread.sleep(1000);

// Case C11
var authOk = false;
try {
    statusCode = parseInt(runCmd(["curl", "-k", "-s", "-o", NULL_DEVICE, "-w", "%{http_code}", "-u", "testuser:newpass456", "-A", CURL_USER_AGENT, "https://127.0.0.1:8443/"]).trim());
    // 200 = forwarded to the upstream; 429 = the request DID pass SecurityFilter
    // and reached the upstream, which replied with rate limiting (429). A 401
    // (or anything else) means the auth gate itself failed.
    authOk = (statusCode === 200) || (statusCode === 429);
} catch (e) {
    print("[WARN] curl (valid credentials) failed: " + e);
}
check({
    name: "curl GET https://127.0.0.1:8443/ (testuser:newpass456 -> 200)",
    ok: authOk,
    detail: "status=" + statusCode + (statusCode === 429 ? " (upstream rate limit: auth accepted, request forwarded)" : ""),
    objective: "Verify SecurityFilter accepts valid credentials and forwards the request (HTTP 200; 429 = forwarded but rate-limited by the upstream)",
    objectiveZh: "驗證 SecurityFilter 接受有效憑證並轉發請求（HTTP 200；429 代表已通過認證但遭上游限流）",
    command: "curl -k -s -o NUL -w \"%{http_code}\" -u testuser:newpass456 -A \"<browser UA>\" https://127.0.0.1:8443/"
});
Thread.sleep(1000);

// Case C12
var wrongAuthOk = false;
try {
    statusCode = parseInt(runCmd(["curl", "-k", "-s", "-o", NULL_DEVICE, "-w", "%{http_code}", "-u", "testuser:wrongpass", "-A", CURL_USER_AGENT, "https://127.0.0.1:8443/"]).trim());
    wrongAuthOk = (statusCode === 401);
} catch (e) {
    print("[WARN] curl (wrong credentials) failed: " + e);
}
check({
    name: "curl GET https://127.0.0.1:8443/ (wrong password -> 401)",
    ok: wrongAuthOk,
    detail: "status=" + statusCode,
    objective: "Verify SecurityFilter rejects requests with invalid credentials (HTTP 401)",
    objectiveZh: "驗證 SecurityFilter 拒絕無效憑證請求（HTTP 401）",
    command: "curl -k -s -o NUL -w \"%{http_code}\" -u testuser:wrongpass -A \"<browser UA>\" https://127.0.0.1:8443/"
});

// Case C13
r = callTomcat(function(){ return tomcat.removeDomainAuthUser("127.0.0.1", "8443", "testuser"); });
check({
    name: "removeDomainAuthUser 127.0.0.1:8443.testuser",
    ok: isOk(r),
    detail: msg(r),
    objective: "Verify the auth user is removed (soft delete)",
    objectiveZh: "驗證 auth 使用者已移除（軟刪除）",
    command: "tomcat.removeDomainAuthUser(\"127.0.0.1\", \"8443\", \"testuser\")",
    detail: msg(r) + dbVerify(function(){ return "SELECT * FROM domain_auth_user WHERE deleted=FALSE"; })
});

// Case C14
r = callTomcat(function(){ return tomcat.updateDomainAuth("127.0.0.1", "8443", false); });
check({
    name: "updateDomainAuth 127.0.0.1:8443 (auth disabled)",
    ok: isOk(r) && r.msg.indexOf("updated successfully") >= 0,
    detail: msg(r),
    objective: "Verify the auth record can be disabled (auth_enable=false)",
    objectiveZh: "驗證 auth 記錄可停用（auth_enable=false）",
    command: "tomcat.updateDomainAuth(\"127.0.0.1\", \"8443\", false)",
    detail: msg(r) + dbVerify(function(){ return "SELECT * FROM domain_auth WHERE domain='127.0.0.1' AND port='8443' AND deleted=FALSE"; })
});

// Case C15
r = callTomcat(function(){ return tomcat.removeDomainAuth("127.0.0.1", "8443"); });
check({
    name: "removeDomainAuth 127.0.0.1:8443 (cleanup)",
    ok: isOk(r),
    detail: msg(r),
    objective: "Verify the test auth record is removed (no users left, cleanup)",
    objectiveZh: "驗證測試 auth 記錄已移除（無殘留使用者、清理）",
    command: "tomcat.removeDomainAuth(\"127.0.0.1\", \"8443\")",
    detail: msg(r) + dbVerify(function(){ return "SELECT * FROM domain_auth WHERE deleted=FALSE"; })
});

// Case C16 — cleanup all test data: remove the precondition domain mapping
// (auth + auth_user already removed by C13/C15) and verify no mapping records
// remain for the test domain (deleted=FALSE -> 0 rows).
r = callTomcat(function(){ return tomcat.removeDomainMapping("127.0.0.1", "8443"); });
check({
    name: "removeDomainMapping 127.0.0.1:8443 (precondition cleanup)",
    ok: isOk(r),
    detail: msg(r),
    objective: "Remove the 127.0.0.1:8443 mapping created for the auth tests and verify no test records remain",
    objectiveZh: "移除 auth 測試建立的 127.0.0.1:8443 對應，並驗證無測試記錄殘留",
    command: "tomcat.removeDomainMapping(\"127.0.0.1\", \"8443\")",
    detail: msg(r) + dbVerify(function(){ return "SELECT * FROM domain_mapping_param WHERE domain='127.0.0.1' AND port='8443' AND deleted=FALSE"; })
});

// ---------------------------------------------------------------------------
// Summary
// ---------------------------------------------------------------------------
writeReport();
writeMd();
print("========================================");
print("TestSecurityFilter result: " + passCount + " passed, " + failCount + " failed");
if (failCount > 0) {
    print("FAILED checks: " + failures.join(", "));
} else {
    print("ALL TESTS PASSED");
}
