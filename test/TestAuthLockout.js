// ============================================================================
// TestAuthLockout — domain_auth account lockout test suite (console mode)
// Runs against the live JsPlus console / embedded Tomcat. Validates:
//   L1-L3 : wrong password attempts are rejected (401) and count up
//   L4    : once the limit is hit, even the CORRECT password is rejected (locked)
//   L5    : unlockAuthUser clears the lock
//   L6    : correct password accepted again after unlock
//   L7    : failed-attempt counter resumes after unlock
// Uses the shared TestBase.js harness (callTomcat / check / dbVerify / runCmd).
// Loaded by TestAll.js as suite #6; also runnable standalone via -Task.
// ============================================================================
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

var REPORT_NAME = "TestAuthLockout-Report.html";
var REPORT_TITLE = "JsPlus-Tomcat Auth Lockout Test";

print("=== JsPlus-Tomcat Auth Lockout Test ===");
print("JS_PLUS_PATH: " + System.getProperty("JS_PLUS_PATH"));

// ---- Bootstrap embedded Tomcat in this JVM (mirror of TestAll.js) ----------
// Needed when this script runs standalone via -Task (no global `tomcat`).
var Thread = Java.type("java.lang.Thread");
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

if (BOOTSTRAPPED) {
    print("[BOOTSTRAP] waiting for embedded Tomcat 8080/8443 ...");
    var ok8080 = waitForPort(8080, 60);
    var ok8443 = waitForPort(8443, 60);
    print("[BOOTSTRAP] 8080=" + ok8080 + " 8443=" + ok8443);
}

var r;

// ---- Setup ---------------------------------------------------------------
r = callTomcat(function(){ return tomcat.createDomainAuth("127.0.0.1", "8443", true); });
print("[SETUP] createDomainAuth(127.0.0.1:8443, true) -> " + msg(r));

callTomcat(function(){ return tomcat.removeDomainAuthUser("127.0.0.1", "8443", "lockuser"); });
r = callTomcat(function(){ return tomcat.createDomainAuthUser("127.0.0.1", "8443", "lockuser", "lockpass123"); });
print("[SETUP] createDomainAuthUser(lockuser) -> " + msg(r));

r = callTomcat(function(){ return tomcat.setAuthLockPolicy("127.0.0.1", "8443", 3, 30); });
print("[SETUP] setAuthLockPolicy(3, 30) -> " + msg(r));

function httpCode(user, pass) {
    var out = runCmd(["curl", "-sk", "--max-time", "20", "-o", "NUL", "-w", "%{http_code}", "-u", user + ":" + pass, "https://127.0.0.1:8443/"]);
    return "" + out;
}

// ---- L1-L3: wrong password attempts ---------------------------------------
var w1 = httpCode("lockuser", "wrong1");
check({
    name: "L1 wrong password attempt #1 -> 401",
    ok: w1.indexOf("401") >= 0,
    detail: "http_code=" + w1,
    objective: "Wrong password #1 must be rejected with 401 and counted",
    objectiveZh: "錯誤密碼第 1 次必須回 401 並計數",
    command: "curl -sk -u lockuser:wrong1 https://127.0.0.1:8443/"
});

var w2 = httpCode("lockuser", "wrong2");
check({
    name: "L2 wrong password attempt #2 -> 401",
    ok: w2.indexOf("401") >= 0,
    detail: "http_code=" + w2,
    objective: "Wrong password #2 must be rejected with 401 and counted",
    objectiveZh: "錯誤密碼第 2 次必須回 401 並計數",
    command: "curl -sk -u lockuser:wrong2 https://127.0.0.1:8443/"
});

var w3 = httpCode("lockuser", "wrong3");
check({
    name: "L3 wrong password attempt #3 -> 401 (lock triggered)",
    ok: w3.indexOf("401") >= 0,
    detail: "http_code=" + w3 + dbVerify(function(){ return "SELECT failed_attempts, locked_until FROM domain_auth_user WHERE username='lockuser' AND deleted=FALSE"; }),
    objective: "Wrong password #3 must be rejected and the account locked (locked_until set)",
    objectiveZh: "錯誤密碼第 3 次必須被拒並鎖定帳號（locked_until 已設定）",
    command: "curl -sk -u lockuser:wrong3 https://127.0.0.1:8443/"
});

// ---- L4: correct password rejected while locked ----------------------------
var c1 = httpCode("lockuser", "lockpass123");
check({
    name: "L4 correct password while locked -> 401",
    ok: c1.indexOf("401") >= 0,
    detail: "http_code=" + c1 + dbVerify(function(){ return "SELECT failed_attempts, locked_until FROM domain_auth_user WHERE username='lockuser' AND deleted=FALSE"; }),
    objective: "A locked account must reject even the correct password until the lock expires or is cleared",
    objectiveZh: "鎖定期間即使輸入正確密碼也必須被拒，直到解鎖或到期",
    command: "curl -sk -u lockuser:lockpass123 https://127.0.0.1:8443/ (while locked)"
});

// ---- L5: manual unlock ------------------------------------------------------
r = callTomcat(function(){ return tomcat.unlockAuthUser("127.0.0.1", "8443", "lockuser"); });
check({
    name: "L5 unlockAuthUser clears the lock",
    ok: isOk(r),
    detail: msg(r) + dbVerify(function(){ return "SELECT failed_attempts, locked_until FROM domain_auth_user WHERE username='lockuser' AND deleted=FALSE"; }),
    objective: "unlockAuthUser must clear failed_attempts and locked_until immediately",
    objectiveZh: "unlockAuthUser 必須即時清除 failed_attempts 與 locked_until",
    command: "tomcat.unlockAuthUser(\"127.0.0.1\", \"8443\", \"lockuser\")"
});

// ---- L6: correct password accepted again -----------------------------------
var c2 = httpCode("lockuser", "lockpass123");
check({
    name: "L6 correct password after unlock -> not 401",
    ok: c2.indexOf("401") < 0,
    detail: "http_code=" + c2,
    objective: "After unlock the correct password must be accepted again",
    objectiveZh: "解鎖後正確密碼必須再次被接受",
    command: "curl -sk -u lockuser:lockpass123 https://127.0.0.1:8443/ (after unlock)"
});

// ---- L7: counter resumes after unlock (1 wrong attempt, no lock) -----------
var w4 = httpCode("lockuser", "wrong1");
check({
    name: "L7 wrong password #1 after unlock -> 401 (counter resumes)",
    ok: w4.indexOf("401") >= 0,
    detail: "http_code=" + w4 + dbVerify(function(){ return "SELECT failed_attempts, locked_until FROM domain_auth_user WHERE username='lockuser' AND deleted=FALSE"; }),
    objective: "After unlock the failed-attempt counter must resume from 0 (1 failed -> still unlocked)",
    objectiveZh: "解鎖後失敗計數須從 0 重新累積（第 1 次失敗仍未鎖定）",
    command: "curl -sk -u lockuser:wrong1 https://127.0.0.1:8443/ (after unlock)"
});

// ---- cleanup: remove the smoke-test user ------------------------------------
r = callTomcat(function(){ return tomcat.removeDomainAuthUser("127.0.0.1", "8443", "lockuser"); });
print("[CLEANUP] removeDomainAuthUser(lockuser) -> " + msg(r));

writeReport();
writeMd();
print("========================================");
print("AuthLockout result: " + passCount + " passed, " + failCount + " failed");
if (failCount > 0) {
    print("FAILED checks: " + failures.join(", "));
} else {
    print("ALL TESTS PASSED");
}
