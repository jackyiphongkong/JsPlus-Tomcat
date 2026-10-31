// ============================================================================
// _RunOne.js — bootstrap Tomcat (if needed) then run a single test suite.
// Usage: set env RUN_SUITE=TestDomainProxy, then:
//   java ... com.killcoding.jsplus.Main -Task .../test/_RunOne.js
// The suite JS itself does NOT bootstrap Tomcat (it assumes TestAll.js has
// already done so); this entry point provides that bootstrap so each suite
// can be run standalone.
// ============================================================================

var System = Java.type("java.lang.System");
var Thread = Java.type("java.lang.Thread");
var basePath = System.getProperty("JS_PLUS_PATH");
var suiteName = System.getenv("RUN_SUITE");

if (!suiteName) {
    System.err.println("[_RunOne] ERROR: RUN_SUITE env var not set.");
    java.lang.Runtime.getRuntime().halt(1);
}

// Bootstrap TomcatEmbed in THIS JVM if no global `tomcat` exists.
if (typeof tomcat === "undefined") {
    var OriginConsole = Console;
    var TomcatConsole = Console.newBuilder();
    TomcatConsole.getContext().put("OriginConsole", OriginConsole);
    TomcatConsole.eval(basePath + '/script/TomcatEmbed.js');
    try {
        tomcat.start();
    } catch (e) {
        System.err.println("[_RunOne] WARN: tomcat.start(): " + e);
    }
}

// Wait for embedded Tomcat (port 8080) to come up.
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

print("[_RunOne] Waiting for Tomcat :8080 ...");
if (!waitForPort(8080, 60)) {
    System.err.println("[_RunOne] ERROR: Tomcat did not come up on :8080.");
    java.lang.Runtime.getRuntime().halt(1);
}
print("[_RunOne] Tomcat ready. Running " + suiteName + ".js ...");

// Load and run the requested suite. It prints its own pass/fail summary.
Console.eval(basePath + '/../test/' + suiteName + '.js');

// Hard-halt so the non-daemon TomcatServer await thread does not keep JVM alive.
java.lang.Runtime.getRuntime().halt(0);
