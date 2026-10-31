// ============================================================================
// precache.js — preRequest cache-flow test script.
//
// This script is loaded by DomainProxy with scriptEnable=true. It exercises
// the two halves of the preRequest cache contract:
//
//   /prime   → on first hit, write a known-good {status, headers, body} map
//              into the Java Cache under the convention `domCache:<host>:<uri>`,
//              then return null so the request proxies through (the upstream
//              may 502 — we don't care; the side effect is the cache write).
//   /cached  → on hit, Cache.get(...) returns the primed map and preRequest
//              short-circuits with {status:200, body:'CACHED'}.
//
// Why a separate script: the JsPlus *console* sandbox (where TestDomainProxy.js
// runs via Console.eval) does not allow `Java.type('com.killcoding.cache.Cache')`,
// but the domain-script sandbox does (same as sample.js). Priming the cache
// from within a domain script keeps the whole flow inside the runtime that
// will actually serve /cached.
// ============================================================================

var TomcatScriptConsole = Java.type('com.killcoding.jsplus.tomcat.TomcatScriptConsole');
var StandardCharsets    = Java.type('java.nio.charset.StandardCharsets');

function DomainMapping() {}

DomainMapping.getParams = function() {
    return {
        target: 'https://zh.wikipedia.org',
        filterEnable: false
    };
};

DomainMapping.preRequest = function() {
    var request = TomcatScriptConsole.get().request;
    var uri     = request.getRequestURI();
    var host    = request.getServerName();

    var Cache   = Java.type('com.killcoding.cache.Cache');
    var HashMap = Java.type('java.util.HashMap');

    // /prime — write a known response into the cache, then return 200 PRIMED
    // directly (instead of null/proxy) so we can confirm the JS side ran.
    if (uri === '/prime') {
        var cacheKey = 'domCache:' + host + ':/cached';
        var m = new HashMap();
        m.put('status', 200);
        var h = new HashMap();
        h.put('Content-Type', 'text/plain; charset=utf-8');
        m.put('headers', h);
        m.put('body', String('CACHED').getBytes(StandardCharsets.UTF_8));
        Cache.set(cacheKey, m, 60);   // 60s TTL
        return { status: 200, headers: { 'Content-Type': 'text/plain' }, body: 'PRIMED' };
    }

    // /cached — look up the primed entry and short-circuit on a hit.
    if (uri === '/cached') {
        var cacheKey = 'domCache:' + host + ':/cached';
        var cached = Cache.get(cacheKey);
        if (cached !== null && cached !== undefined) {
            return cached;
        }
        return null;
    }

    return null;
};
