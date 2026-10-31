var System = Java.type('java.lang.System');
var TomcatScriptConsole = Java.type('com.killcoding.jsplus.tomcat.TomcatScriptConsole');
var GZIPInputStream = Java.type('java.util.zip.GZIPInputStream');
var ByteArrayInputStream = Java.type('java.io.ByteArrayInputStream');
var StandardCharsets = Java.type('java.nio.charset.StandardCharsets');
var String = Java.type('java.lang.String');
var JsString = Java.type('com.killcoding.jsplus.JsString');
var ByteArrayOutputStream = Java.type('java.io.ByteArrayOutputStream');
var GZIPOutputStream = Java.type('java.util.zip.GZIPOutputStream');


function DomainMapping(){
   // java.lang.System.out.println("Request: " + TomcatScriptConsole.get().request);
}

DomainMapping.prototype.getTarget = function(){
    return "https://zh.wikipedia.org"; 
}

DomainMapping.prototype.getConnectTimeout = function(){
    return "60s"; 
}

DomainMapping.prototype.getForwardedHeaders = function(){
    return {
        // header_A: 'value_1',
        // header_B: 'value_2',
        // header_C: 'value_3'
    }; 
}

DomainMapping.prototype.getResponseHeaders = function(){
    return {
        // header_1: 'value_1',
        // header_2: 'value_2',
        // header_3: 'value_3'
    }; 
}

DomainMapping.getParams = function(){
    var domain = new DomainMapping();
    return {
        target: domain.getTarget(),
        connectTimeout: domain.getConnectTimeout(),
        forwardedHeaders: domain.getForwardedHeaders(),
        responseHeaders: domain.getResponseHeaders(),
        forwardedHeader: true,
        filterEnable: true
    };
}

// ============================================================================
// preRequest — called on the way out to the backend, before any upstream HTTP
// request is sent. Use this hook to short-circuit a request with a stub or a
// cached response:
//
//   return null / undefined  → continue normal proxying to the upstream target
//   return { status:200, headers:{'Content-Type':'text/plain'}, body:'ok' }
//                            → write this response directly to the client and
//                              skip the upstream call entirely
//
// The example below shows two common patterns:
//   1. Stub: /health always returns "ok" without hitting the backend.
//   2. Cache: look up a previously stored response in the Java Cache; on a hit
//             return it; on a miss return null so the request proxies through.
// ============================================================================
DomainMapping.preRequest = function() {
    var request = TomcatScriptConsole.get().request;
    var method  = request.getMethod();
    var uri     = request.getRequestURI();

    // Pattern 1 — stub: /health never touches the backend.
    if (uri === '/health') {
        return {
            status: 200,
            headers: { 'Content-Type': 'text/plain; charset=utf-8' },
            body: 'ok'
        };
    }

    // Test hooks — these two paths exercise the cache write/read cycle from
    // the same domain script that will later read the cache, so there is no
    // cross-scriptloader sharing issue.
    //   /prime  → write a known-good entry, then short-circuit 200 PRIMED
    //   /cached → read the entry; on hit return it, on miss return null
    if (uri === '/prime') {
        try {
            var Cache   = Java.type('com.killcoding.cache.Cache');
            var JsString = Java.type('com.killcoding.jsplus.JsString');
            var host    = request.getServerName();
            var cacheKey = 'domCache:' + host + ':/cached';
            var HashMap = Java.type('java.util.HashMap');
            var m = new HashMap();
            m.put('status', 200);
            var h = new HashMap();
            h.put('Content-Type', 'text/plain; charset=utf-8');
            m.put('headers', h);
            m.put('body', JsString.valueOf('CACHED').getBytes('UTF-8'));
            Cache.set(cacheKey, m, 60);
        } catch (e) {
            java.lang.System.err.println('[sample.js] /prime cache write failed: ' + e);
        }
        return { status: 200, headers: { 'Content-Type': 'text/plain; charset=utf-8' }, body: 'PRIMED' };
    }

    if (uri === '/cached') {
        try {
            var Cache = Java.type('com.killcoding.cache.Cache');
            var host  = request.getServerName();
            var cacheKey = 'domCache:' + host + ':/cached';
            var cached = Cache.get(cacheKey);
            if (cached !== null && cached !== undefined) {
                return cached;
            }
        } catch (e) {
            java.lang.System.err.println('[sample.js] /cached cache read failed: ' + e);
        }
        return null;
    }

    // Pattern 2 — cache lookup. Only cache GET; build a stable cache key from
    // method + host + URI (+ query string if present). The Java Cache (from
    // JsPlus-Core) is reachable via Java.type; on a hit we replay the stored
    // {status, headers, body} map verbatim.
    if (method === 'GET') {
        var Cache = Java.type('com.killcoding.cache.Cache');
        var host = request.getServerName();
        var qs = request.getQueryString();
        var cacheKey = 'domCache:' + host + ':' + uri + (qs ? ('?' + qs) : '');
        var cached = Cache.get(cacheKey);
        if (cached !== null && cached !== undefined) {
            return cached;   // expected shape: {status, headers, body}
        }
        // On a miss, return null so the request proxies through. To populate
        // the cache, either:
        //   (a) have another management endpoint call Cache.set(key, value, ttlSec), or
        //   (b) hook the response in filter() and Cache.set(...) from there.
    }

    return null;
};

DomainMapping.filter = function(){
    var request = TomcatScriptConsole.get().request;
    var response = TomcatScriptConsole.get().response;
    var backendResponse = TomcatScriptConsole.get().backendResponse;
    var backendStream = TomcatScriptConsole.get().backendStream;
    var encoding = backendResponse.headers().firstValue("Content-Encoding").orElse("");
    var type = backendResponse.headers().firstValue("Content-Type").orElse("");
    var responseBytes = null;
    if(type.indexOf('text/html') > -1){ //&& request.getRequestURI() === '/wiki/'
        responseBytes = backendStream.readAllBytes();
        var plainText = null;   // decompressed, unmodified text — for cache
        if(encoding === 'gzip'){
            var gzis = new GZIPInputStream(new ByteArrayInputStream(responseBytes));
            var decompressed = gzis.readAllBytes();
            plainText = new String(decompressed, StandardCharsets.UTF_8);
            var text = plainText.concat("<!--Cleared-->");
            var textBytes = JsString.valueOf(text).getBytes('UTF-8');
            var baos = new ByteArrayOutputStream();
            var gzos = new GZIPOutputStream(baos);
            gzos.write(textBytes);
            gzos.close();
            var newBytes = baos.toByteArray();
            response.setContentLength(newBytes.length);
            writeCacheEntry(request, backendResponse, plainText);
            return newBytes;
        }else{
            plainText = new String(responseBytes, StandardCharsets.UTF_8);
            var text = plainText.concat("<!--Cleared-->");
            var newBytes = JsString.valueOf(text).getBytes('UTF-8');
            response.setContentLength(newBytes.length);
            writeCacheEntry(request, backendResponse, plainText);
            return newBytes;
        }
    }
    return responseBytes;
}

// writeCacheEntry — store the decompressed, unmodified HTML in the Java Cache
// so a later preRequest() call can short-circuit with the same body. We cache
// the *plain* (un-gzipped, no <!--Cleared-->) text on purpose: the short-circuit
// path writes raw bytes and sets its own Content-Type, so a gzip-encoded blob
// without a matching Content-Encoding header would corrupt the client.
//
// Cache.set semantics (JsPlus-Core): life >= 0 writes; 0 = never expire;
// negative = no-op. Cache.set(key, null, ...) is also a no-op.
function writeCacheEntry(request, backendResponse, plainText) {
    try {
        var method = request.getMethod();
        if (method !== 'GET') return;
        var uri  = request.getRequestURI();
        var host = request.getServerName();
        var qs   = request.getQueryString();
        var cacheKey = 'domCache:' + host + ':' + uri + (qs ? ('?' + qs) : '');

        var Cache  = Java.type('com.killcoding.cache.Cache');
        var HashMap = Java.type('java.util.HashMap');
        var responseMap = new HashMap();
        responseMap.put('status', Number(backendResponse.statusCode()));
        var headersMap = new HashMap();
        headersMap.put('Content-Type', 'text/html; charset=utf-8');
        responseMap.put('headers', headersMap);
        responseMap.put('body', JsString.valueOf(plainText).getBytes('UTF-8'));   // byte[]

        Cache.set(cacheKey, responseMap, 60);   // 60s TTL
    } catch (e) {
        // Never let a cache write failure break the proxy.
        java.lang.System.err.println('[sample.js] writeCacheEntry failed: ' + e);
    }
}

