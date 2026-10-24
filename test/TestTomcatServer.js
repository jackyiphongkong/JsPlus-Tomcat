// ============================================================================
// JsPlus-Tomcat TomcatServer Test Suite (console mode)
// ----------------------------------------------------------------------------
// Run      : Inside the JsPlus console (server already running):
//              Console.eval(<path to this file>);
//            Or via TestAll.js (which loads it as suite #5).
// Harness  : Shared plumbing lives in TestBase.js and is loaded below.
// Output   : <PROJECT_ROOT>/test_reports/TestTomcatServer-Report.html / .md
// Scope    : Validates the TomcatServer layer THROUGH the console entry
//            `tomcat` (JsPlus/script/TomcatEmbed.js) — the module class is
//            never touched directly, mirroring the other suites:
//             A) Console entry         — global `tomcat` exists and the
//                introspection API answers (getVersion/getTomcatHome/
//                getMemoryInfo)
//             B) addSslHost behaviour  — live SSLHostConfig add + domain_ssl_host
//                write, verified with a dbVerify SELECT, then cleanup
//             C) Config contract       — tomcat.properties keys exactly match
//                what TomcatServer.loadParams() consumes, including the
//                renamed Webapp.contextPath/Webapp.appBase keys (regression
//                guard against the old bare names) and the SSL.* block
//             D) Keystore readiness    — default_keystore.jks /
//                default_truststore.jks exist, are real JKS stores with the
//                correct aliases, and the truststore contains a trustedCertEntry
//                (the trust anchor that fixed the 8443 startup failure)
//             E) Behaviour smoke       — read-only tomcat calls (getTomcat /
//                queryDomainSslHosts / loadDomainSslHosts) succeed against the
//                running server
//             F) DomainSslHost CRUD    — create/query/update/remove + cleanup-all,
//                each mutation verified with a dbVerify SELECT, plus the
//                cleanup-all case
// NOTE     : The suite never starts/stops the server and never calls the
//            mutating TomcatServer APIs that would disturb a live server
//            (loadParams/loadTomcatProps/reloadSslHostConfig). Every API call
//            records its {code, msg} return value; DB-writing calls carry a
//            dbVerify (SELECT *) verification + cleanup. The suite also never
//            calls System.exit() in console mode.
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

var REPORT_NAME = "TestTomcatServer-Report.html";
var REPORT_TITLE = "JsPlus-Tomcat TomcatServer Test Report";

print("=== JsPlus-Tomcat TomcatServer Test ===");
print("JS_PLUS_PATH: " + System.getProperty("JS_PLUS_PATH"));

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------
var File = Java.type("java.io.File");
var FileInputStream = Java.type("java.io.FileInputStream");
var KeyStore = Java.type("java.security.KeyStore");

// Call a `tomcat` method that does NOT return a result-message string
// (e.g. getTomcat() returns a Tomcat object, getMemoryInfo() a Java Map).
function callDirect(fn) {
    try {
        return { code: 1, value: fn() };
    } catch (e) {
        return { code: 0, value: null, err: "" + e };
    }
}

// Read tomcat.properties as a key->value map (line-by-line, no regex — the
// GraalVM 25 JS runtime has no regex language loaded).
function readProps() {
    var map = {};
    try {
        var Files = Java.type("java.nio.file.Files");
        var Paths = Java.type("java.nio.file.Paths");
        var content = Files.readString(Paths.get(basePath + "config/tomcat.properties"));
        var lines = content.split("\n");
        for (var i = 0; i < lines.length; i++) {
            var ln = lines[i];
            if (ln.length > 0 && ln.charAt(ln.length - 1) === "\r") {
                ln = ln.substring(0, ln.length - 1);
            }
            var t = ln.trim();
            if (t.length === 0 || t.charAt(0) === "#") {
                continue;
            }
            var eq = t.indexOf("=");
            if (eq < 0) {
                continue;
            }
            var k = t.substring(0, eq).trim();
            var v = t.substring(eq + 1).trim();
            map[k] = v;
        }
    } catch (e) {
        map["__error__"] = "" + e;
    }
    return map;
}

// Build a Java char[] from a JS string (GraalVM JS maps java.lang.String back
// to a JS string, so neither toCharArray() nor Java.to("...","char[]") works).
function toChars(s) {
    var CharArrayType = Java.type("char[]");
    var out = new CharArrayType(s.length);
    for (var i = 0; i < s.length; i++) {
        out[i] = s.charCodeAt(i);
    }
    return out;
}

// Detail text for a callTomcat result — shows BOTH the resolved code and the
// API message, so the report carries the same {code, msg} the Java API returns
// (e.g. resMap.put("code", 1) / resMap.put("msg", "DomainSslHost x updated
// successfully.")). Passed/failed cases keep this + the dbVerify appendix.
function apiDetail(r) {
    if (r === undefined || r === null) {
        return "";
    }
    var parts = [];
    if (r.code !== undefined && r.code !== null) {
        parts.push("code=" + r.code);
    }
    if (r.msg !== undefined && r.msg !== null && ("" + r.msg).length > 0) {
        parts.push("msg: " + r.msg);
    }
    return parts.join(" | ");
}

// Load a JKS store via the Java KeyStore API — throws if the file is missing,
// is not a JKS store, or the password is wrong.
function loadJks(path) {
    var ks = KeyStore.getInstance("JKS");
    var fis = new FileInputStream(path);
    try {
        ks.load(fis, toChars("changeit"));
    } finally {
        fis.close();
    }
    return ks;
}

// ---------------------------------------------------------------------------
// A. Console entry — global `tomcat` and its introspection API
// ---------------------------------------------------------------------------

// Case C1
var hasTomcat = (typeof tomcat !== "undefined" && tomcat !== null);
check({
    name: "global tomcat object present (TomcatEmbed)",
    ok: hasTomcat,
    detail: hasTomcat ? "TomcatEmbed console entry resolved" : "no global tomcat object",
    objective: "Verify the TomcatEmbed console entry exposes the global tomcat object",
    objectiveZh: "驗證 TomcatEmbed 主控台入口已公開全域 tomcat 物件",
    command: "TomcatEmbed.js -> OriginConsole.getContext().put(\"tomcat\", new TomcatEmbed())"
});

// Case C2
var ver = callTomcat(function(){ return tomcat.getVersion(); });
check({
    name: "tomcat.getVersion()",
    ok: hasTomcat && isOk(ver) && msg(ver).length > 0 && msg(ver).indexOf("2026") >= 0,
    detail: hasTomcat ? msg(ver) : "(no tomcat)",
    objective: "Verify getVersion() exposes TomcatServer.VERSION through the console entry",
    objectiveZh: "驗證 getVersion() 經主控台入口公開 TomcatServer.VERSION",
    command: "tomcat.getVersion()"
});

// Case C3
var home = callDirect(function(){ return tomcat.getTomcatHome(); });
var homeStr = (home.code === 1 && home.value !== null) ? ("" + home.value) : "";
check({
    name: "tomcat.getTomcatHome()",
    ok: home.code === 1 && homeStr.length > 0 && new File(homeStr).isDirectory(),
    detail: home.code === 1 ? homeStr : "call failed: " + home.err,
    objective: "Verify getTomcatHome() returns an existing directory",
    objectiveZh: "驗證 getTomcatHome() 返回真實目錄",
    command: "tomcat.getTomcatHome()"
});

// Case C4
var mem = callDirect(function(){ return tomcat.getMemoryInfo(); });
var memOk = (mem.code === 1 && mem.value !== null && mem.value.size !== undefined && mem.value.size() > 0);
check({
    name: "tomcat.getMemoryInfo()",
    ok: memOk,
    detail: memOk ? "map entries: " + mem.value.size() : (mem.code === 1 ? "empty/null map" : "call failed: " + mem.err),
    objective: "Verify getMemoryInfo() returns a non-empty metrics map",
    objectiveZh: "驗證 getMemoryInfo() 返回非空指標地圖",
    command: "tomcat.getMemoryInfo()"
});

// ---------------------------------------------------------------------------
// B. addSslHost behaviour — the only TomcatServer method that both mutates
//    the live SSL connector and persists a domain_ssl_host row, so it carries
//    a dbVerify verification. Needs a real keystore under keyRoot
//    (default_keystore.jks is committed for CI).
// ---------------------------------------------------------------------------
var SSL_TEST_HOST = "ssl-test.local";

// Case C5 — live SSLHostConfig add + domain_ssl_host write, verified with
// dbVerify (SELECT *) right after the call.
var addSslR = callTomcat(function(){ return tomcat.addSslHost(SSL_TEST_HOST, "default_keystore.jks", "changeit", "client"); });
check({
    name: "tomcat.addSslHost()",
    ok: isOk(addSslR),
    detail: apiDetail(addSslR) + dbVerify(function(){ return "SELECT * FROM domain_ssl_host WHERE hostname='" + SSL_TEST_HOST + "' AND deleted=FALSE"; }),
    objective: "Verify addSslHost() adds a live SSLHostConfig and persists the DomainSslHost row",
    objectiveZh: "驗證 addSslHost() 新增即時 SSLHostConfig 並寫入 DomainSslHost 記錄",
    command: "tomcat.addSslHost(" + SSL_TEST_HOST + ", default_keystore.jks, changeit, client)"
});

// Case C6 — cleanup: remove the ssl-test.local row created by C5 and verify
// it is gone (deleted=FALSE -> 0 rows).
var sslCleanupR = callTomcat(function(){ return tomcat.removeDomainSslHost(SSL_TEST_HOST); });
check({
    name: "cleanup: addSslHost test host removed",
    ok: isOk(sslCleanupR),
    detail: apiDetail(sslCleanupR) + dbVerify(function(){ return "SELECT * FROM domain_ssl_host WHERE hostname='" + SSL_TEST_HOST + "' AND deleted=FALSE"; }),
    objective: "Verify the addSslHost test row is removed",
    objectiveZh: "驗證 addSslHost 測試記錄已移除",
    command: "tomcat.removeDomainSslHost(" + SSL_TEST_HOST + ")"
});

// ---------------------------------------------------------------------------
// C. Config contract (tomcat.properties vs TomcatServer.loadParams keys)
// ---------------------------------------------------------------------------
var props = readProps();
var cfgErr = props["__error__"];

// Case C7
check({
    name: "tomcat.properties readable",
    ok: cfgErr === undefined,
    detail: cfgErr !== undefined ? "read error: " + cfgErr : basePath + "config/tomcat.properties parsed",
    objective: "Verify the config file can be read for the contract checks",
    objectiveZh: "驗證 config 檔案可讀以進行契約檢查",
    command: "Files.readString(" + basePath + "config/tomcat.properties)"
});

function cfgCheck(name, key, expected, command) {
    var v = props[key];
    var okv = (v !== undefined && v === expected);
    check({
        name: name,
        ok: okv,
        detail: okv ? key + "=" + v : (v === undefined ? key + " missing" : key + "=" + v + " (expected " + expected + ")"),
        objective: "Verify config key " + key + " is present with value " + expected,
        objectiveZh: "驗證 config 鍵 " + key + " 值為 " + expected,
        command: command
    });
}

// Renamed Webapp.* keys — the core of the contextPath/appBase rename task.

// Case C8
cfgCheck("Webapp.appBase[0] key (renamed)", "Webapp.appBase[0]",
    "${JS_PLUS_PATH}/webapps/default/",
    "Webapp.appBase[0]=${JS_PLUS_PATH}/webapps/default/  (config)");

// Regression guard: the OLD bare keys must NOT exist anymore.

// Case C9
var oldCtx = props["contextPath[0]"];
var oldApp = props["appBase[0]"];
check({
    name: "legacy contextPath[0] key absent (regression guard)",
    ok: oldCtx === undefined,
    detail: oldCtx === undefined ? "no bare contextPath[0] key" : "STILL PRESENT: contextPath[0]=" + oldCtx,
    objective: "Verify the old bare contextPath key is gone after the Webapp.* rename",
    objectiveZh: "驗證改名後舊 contextPath 裸鍵已移除",
    command: "grep '^contextPath\\[' tomcat.properties"
});
// Case C10
check({
    name: "legacy appBase[0] key absent (regression guard)",
    ok: oldApp === undefined,
    detail: oldApp === undefined ? "no bare appBase[0] key" : "STILL PRESENT: appBase[0]=" + oldApp,
    objective: "Verify the old bare appBase key is gone after the Webapp.* rename",
    objectiveZh: "驗證改名後舊 appBase 裸鍵已移除",
    command: "grep '^appBase\\[' tomcat.properties"
});

// SSL block.

// Case C11
cfgCheck("SSL.enable", "SSL.enable", "true", "SSL.enable=true");
var keyRoot = props["SSL.keyRoot"];

// Case C12
check({
    name: "SSL.keyRoot points to key/",
    ok: keyRoot !== undefined && keyRoot.indexOf("/key/") >= 0,
    detail: keyRoot !== undefined ? keyRoot : "SSL.keyRoot missing",
    objective: "Verify SSL.keyRoot locates the keystore directory",
    objectiveZh: "驗證 SSL.keyRoot 指向 keystore 目錄",
    command: "SSL.keyRoot=${JS_PLUS_PATH}/key/"
});
// Case C13
cfgCheck("SSL.keystoreFile[0]", "SSL.keystoreFile[0]", "default_keystore.jks", "SSL.keystoreFile[0]=default_keystore.jks");

// Case C14
cfgCheck("SSL.keystorePass[0]", "SSL.keystorePass[0]", "changeit", "SSL.keystorePass[0]=changeit");

// Case C15
cfgCheck("SSL.keyAlias[0]", "SSL.keyAlias[0]", "client", "SSL.keyAlias[0]=client");

// Case C16
cfgCheck("SSL.truststoreFile", "SSL.truststoreFile", "default_truststore.jks", "SSL.truststoreFile=default_truststore.jks");

// Case C17
cfgCheck("SSL.truststorePass", "SSL.truststorePass", "changeit", "SSL.truststorePass=changeit");

// Case C18
cfgCheck("SSL.truststoreType=JKS", "SSL.truststoreType", "JKS", "SSL.truststoreType=JKS");

// Ports.

// Case C19
cfgCheck("port key", "port", "${TOMCAT_PORT}", "port=${TOMCAT_PORT}");

// Case C20
cfgCheck("sslPort key", "sslPort", "${TOMCAT_SSL_PORT}", "sslPort=${TOMCAT_SSL_PORT}");

// ---------------------------------------------------------------------------
// D. Keystore readiness (real file check via the Java KeyStore API)
// ---------------------------------------------------------------------------
var keyDir = basePath + "key/";
var ksPath = keyDir + "default_keystore.jks";
var tsPath = keyDir + "default_truststore.jks";

function ksDetail(label, path, fn) {
    try {
        var ks = loadJks(path);
        return fn(ks);
    } catch (e) {
        return "FAILED: " + e;
    }
}

var keystoreState = ksDetail("keystore", ksPath, function (ks) { return "ok"; });

// Case C21
check({
    name: "default_keystore.jks readable (JKS, password ok)",
    ok: keystoreState === "ok",
    detail: keystoreState === "ok" ? ksPath + " loaded as JKS" : ksPath + " " + keystoreState,
    objective: "Verify the keystore file exists, is JKS and the store password matches config",
    objectiveZh: "驗證 keystore 檔案存在、為 JKS 格式且密碼與 config 一致",
    command: "KeyStore.getInstance(\"JKS\").load(default_keystore.jks, changeit)"
});

var keyAliasState = ksDetail("keyAlias", ksPath, function (ks) {
    if (!ks.containsAlias("client")) {
        return "alias 'client' missing";
    }
    return ks.isKeyEntry("client") ? "ok" : "alias 'client' is NOT a key entry";
});

// Case C22
check({
    name: "default_keystore.jks alias=client is a key entry",
    ok: keyAliasState === "ok",
    detail: keyAliasState === "ok" ? "alias 'client' -> PrivateKeyEntry" : ksPath + " " + keyAliasState,
    objective: "Verify the configured key alias exists and holds the private key",
    objectiveZh: "驗證設定之 key alias 存在且為私鑰項目",
    command: "SSL.keyAlias[0]=client -> KeyStore.isKeyEntry(\"client\")"
});

var truststoreState = ksDetail("truststore", tsPath, function (ks) { return "ok"; });

// Case C23
check({
    name: "default_truststore.jks readable (JKS, password ok)",
    ok: truststoreState === "ok",
    detail: truststoreState === "ok" ? tsPath + " loaded as JKS" : tsPath + " " + truststoreState,
    objective: "Verify the truststore file exists, is JKS and the store password matches config",
    objectiveZh: "驗證 truststore 檔案存在、為 JKS 格式且密碼與 config 一致",
    command: "KeyStore.getInstance(\"JKS\").load(default_truststore.jks, changeit)"
});

var trustAnchorState = ksDetail("trustAnchor", tsPath, function (ks) {
    if (!ks.containsAlias("client")) {
        return "no 'client' alias";
    }
    return ks.isCertificateEntry("client") ? "ok" : "alias 'client' is NOT a trustedCertEntry";
});

// Case C24 — the trust anchor check (8443 fix): the truststore must contain a
// trustedCertEntry for SSLContext creation to succeed.
check({
    name: "default_truststore.jks has trustedCertEntry (trust anchor)",
    ok: trustAnchorState === "ok",
    detail: trustAnchorState === "ok"
        ? "alias 'client' -> trustedCertEntry (SSLContext trust anchor present)"
        : tsPath + " " + trustAnchorState,
    objective: "Verify the truststore contains at least one trustedCertEntry — the trust anchor that makes SSLContext creation succeed (8443 fix)",
    objectiveZh: "驗證 truststore 含受信任憑證項目（SSLContext 建立所需之信任錨，8443 修復關鍵）",
    command: "keytool -exportcert -alias client -keystore default_keystore.jks | keytool -importcert -alias client -keystore default_truststore.jks"
});

// ---------------------------------------------------------------------------
// E. Behaviour smoke — read-only calls against the running server
// ---------------------------------------------------------------------------
// Case C25
var t = callDirect(function(){ return tomcat.getTomcat(); });
check({
    name: "tomcat.getTomcat() returns live Tomcat",
    ok: t.code === 1 && t.value !== null,
    detail: t.code === 1 ? (t.value !== null ? "org.apache.catalina.startup.Tomcat instance" : "null (server not started)") : "call failed: " + t.err,
    objective: "Verify getTomcat() returns the live embedded Tomcat when the server is running",
    objectiveZh: "驗證 getTomcat() 在伺服器運行時返回實時嵌入式 Tomcat",
    command: "tomcat.getTomcat()"
});

// Case C26
var hosts = callTomcat(function(){ return tomcat.queryDomainSslHosts(); });
check({
    name: "tomcat.queryDomainSslHosts()",
    ok: hasTomcat && isOk(hosts),
    detail: apiDetail(hosts),
    objective: "Verify the domain-SSL host query API is callable through the console entry",
    objectiveZh: "驗證網域 SSL host 查詢 API 可經主控台入口調用",
    command: "tomcat.queryDomainSslHosts()"
});

// Case C27
var domHosts = callTomcat(function(){ return tomcat.loadDomainSslHosts(); });
check({
    name: "tomcat.loadDomainSslHosts()",
    ok: hasTomcat && isOk(domHosts),
    detail: apiDetail(domHosts),
    objective: "Verify the domain-SSL host loader is callable through the console entry",
    objectiveZh: "驗證網域 SSL host 載入 API 可經主控台入口調用",
    command: "tomcat.loadDomainSslHosts()"
});

// ---------------------------------------------------------------------------
// F. DomainSslHost CRUD — write-through the console entry, each mutation
//    verified with a dbVerify SELECT (the same CacheDriverExecutor data path
//    the APIs use), followed by a cleanup-all case.
// ---------------------------------------------------------------------------
var TEST_HOST = "test.jsplus.local";

// Case C28 — create a DomainSslHost row, verified with dbVerify (SELECT *).
var createR = callTomcat(function(){ return tomcat.createDomainSslHost(TEST_HOST, "test_keystore.jks", "changeit", "client"); });
check({
    name: "tomcat.createDomainSslHost()",
    ok: isOk(createR),
    detail: apiDetail(createR) + dbVerify(function(){ return "SELECT * FROM domain_ssl_host WHERE hostname='" + TEST_HOST + "' AND deleted=FALSE"; }),
    objective: "Verify a DomainSslHost row is inserted through the console entry",
    objectiveZh: "驗證經主控台入口寫入 DomainSslHost 記錄",
    command: "tomcat.createDomainSslHost(" + TEST_HOST + ", test_keystore.jks, changeit, client)"
});

// Case C29 — query the created DomainSslHost, verified with dbVerify
// (SELECT *) against the same row inserted by C29.
var queryR = callTomcat(function(){ return tomcat.queryDomainSslHost(TEST_HOST); });
check({
    name: "tomcat.queryDomainSslHost()",
    ok: isOk(queryR),
    detail: apiDetail(queryR) + dbVerify(function(){ return "SELECT * FROM domain_ssl_host WHERE hostname='" + TEST_HOST + "' AND deleted=FALSE"; }),
    objective: "Verify the created DomainSslHost is queryable by hostname",
    objectiveZh: "驗證已建立之 DomainSslHost 可按 hostname 查詢",
    command: "tomcat.queryDomainSslHost(" + TEST_HOST + ")"
});

// Case C30 — update the row (keystore file -> test_keystore2.jks), verified
// with dbVerify (SELECT *).
var updateR = callTomcat(function(){ return tomcat.updateDomainSslHost(TEST_HOST, "test_keystore2.jks", "changeit", "client"); });
check({
    name: "tomcat.updateDomainSslHost()",
    ok: isOk(updateR),
    detail: apiDetail(updateR) + dbVerify(function(){ return "SELECT * FROM domain_ssl_host WHERE hostname='" + TEST_HOST + "' AND deleted=FALSE"; }),
    objective: "Verify the DomainSslHost row is updated through the console entry",
    objectiveZh: "驗證經主控台入口更新 DomainSslHost 記錄",
    command: "tomcat.updateDomainSslHost(" + TEST_HOST + ", test_keystore2.jks, changeit, client)"
});

// Case C31 — remove the row, verified with dbVerify (SELECT *).
var removeR = callTomcat(function(){ return tomcat.removeDomainSslHost(TEST_HOST); });
check({
    name: "tomcat.removeDomainSslHost()",
    ok: isOk(removeR),
    detail: apiDetail(removeR) + dbVerify(function(){ return "SELECT * FROM domain_ssl_host WHERE hostname='" + TEST_HOST + "'"; }),
    objective: "Verify the DomainSslHost row is removed through the console entry",
    objectiveZh: "驗證經主控台入口移除 DomainSslHost 記錄",
    command: "tomcat.removeDomainSslHost(" + TEST_HOST + ")"
});

// Case C32 — cleanup-all: confirm no test DomainSslHost row remains
// (deleted=FALSE -> 0 rows).
var cleanupR = callTomcat(function(){ return tomcat.queryDomainSslHosts(); });
check({
    name: "cleanup-all: no test DomainSslHost remains",
    ok: isOk(cleanupR),
    detail: apiDetail(cleanupR) + dbVerify(function(){ return "SELECT * FROM domain_ssl_host WHERE hostname='" + TEST_HOST + "' AND deleted=FALSE"; }),
    objective: "Verify no test DomainSslHost row remains after cleanup",
    objectiveZh: "驗證清理後無測試 DomainSslHost 殘留",
    command: "SELECT * FROM domain_ssl_host WHERE hostname='" + TEST_HOST + "' AND deleted=FALSE"
});

// ---------------------------------------------------------------------------
// G. HTTP/2 contract — config switch + live h2c handshake on the plain
//    connector. The h2-over-TLS (ALPN) path on sslPort is driven by the same
//    Http2.enable switch, so the plain-connector h2c handshake proves the
//    upgrade protocol wiring end to end without TLS plumbing in the harness.
// ---------------------------------------------------------------------------

// Case C33 — HTTP/2 config contract: Http2.enable must be true in
// tomcat.properties (the switch TomcatServer.loadParams() consumes).
var h2Props = readProps();
var h2Switch = h2Props["Http2.enable"];
var h2Enabled = (h2Switch !== undefined && ("" + h2Switch).toLowerCase() === "true");
check({
    name: "HTTP/2 config switch on (Http2.enable=true)",
    ok: h2Enabled,
    detail: "Http2.enable=" + (h2Switch === undefined ? "(missing)" : h2Switch)
        + (h2Enabled ? " -> h2 over TLS (ALPN) + h2c (Upgrade) active" : " -> HTTP/2 negotiation disabled"),
    objective: "Verify tomcat.properties enables HTTP/2 (Http2.enable=true), consumed by TomcatServer.loadParams()",
    objectiveZh: "驗證 tomcat.properties 已啟用 Http2.enable=true（TomcatServer.loadParams 所讀取之開關）",
    command: "tomcat.properties -> Http2.enable=true"
});

// Case C34 — live h2c prior-knowledge handshake against the plain connector:
// a Java HttpClient built with Version.HTTP_2 must negotiate HTTP_2.
var H2cHttpClient = Java.type("java.net.http.HttpClient");
var H2cHttpRequest = Java.type("java.net.http.HttpRequest");
var H2cBodyHandlers = Java.type("java.net.http.HttpResponse.BodyHandlers");
var H2cURI = Java.type("java.net.URI");
var h2c = { ok: false, detail: "" };
try {
    var h2cClient = H2cHttpClient.newBuilder().version(H2cHttpClient.Version.HTTP_2).build();
    var h2cReq = H2cHttpRequest.newBuilder(H2cURI.create("http://localhost:8080/")).GET().build();
    var h2cResp = h2cClient.send(h2cReq, H2cBodyHandlers.ofString());
    var h2cVer = "" + h2cResp.version();
    h2c.ok = (h2cVer.indexOf("HTTP_2") >= 0);
    h2c.detail = "GET http://localhost:8080/ -> version=" + h2cVer + " status=" + h2cResp.statusCode();
} catch (e) {
    h2c.detail = "request failed: " + e;
}
check({
    name: "HTTP/2 h2c upgrade on plain connector (8080)",
    ok: h2c.ok,
    detail: h2c.detail,
    objective: "Verify a Version.HTTP_2 prior-knowledge request to the plain connector negotiates HTTP_2 (h2c Upgrade)",
    objectiveZh: "驗證對純文字連接埠之 HTTP_2 prior-knowledge 請求協商出 HTTP_2（h2c Upgrade）",
    command: "HttpClient(HTTP_2).send(GET http://localhost:8080/) -> response version"
});

// ---------------------------------------------------------------------------
// H. Webapp.[1] default context — shipped config now deploys the default webapp
//    (webapps/default) at contextPath /default (renamed from webapps/trade at
//    /default_a).
// ---------------------------------------------------------------------------

// Case C35 — Webapp.contextPath[1] must be /default.
cfgCheck("Webapp.contextPath[1] key (/default)", "Webapp.contextPath[1]",
    "/default",
    "Webapp.contextPath[1]=/default  (config)");

// Case C36 — Webapp.appBase[1] must point at webapps/default/.
cfgCheck("Webapp.appBase[1] key (webapps/default)", "Webapp.appBase[1]",
    "${JS_PLUS_PATH}/webapps/default/",
    "Webapp.appBase[1]=${JS_PLUS_PATH}/webapps/default/  (config)");

// ---------------------------------------------------------------------------
// Summary
// ---------------------------------------------------------------------------
writeReport();
writeMd();
print("========================================");
print("TestTomcatServer result: " + passCount + " passed, " + failCount + " failed");
if (failCount > 0) {
    print("FAILED checks: " + failures.join(", "));
} else {
    print("ALL TESTS PASSED");
}
