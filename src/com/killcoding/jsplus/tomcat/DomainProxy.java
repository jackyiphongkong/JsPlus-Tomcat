/*******************************************************************************
The MIT License (MIT)

Copyright (c) 2024 KILLCODING.COM

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
THE SOFTWARE.
*****************************************************************************/
package com.killcoding.jsplus.tomcat;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import com.killcoding.log.Logger;
import com.killcoding.log.LoggerFactory;
import org.apache.catalina.connector.ClientAbortException;
import java.util.HashMap;
import java.util.IllegalFormatException;
import com.killcoding.tool.ConfigProperties;
import com.killcoding.cache.Cache;
import com.killcoding.datasource.CacheDriverExecutor;
import com.killcoding.datasource.Clock;
import com.killcoding.ext.Json;
import com.killcoding.tool.CommonTools;

public class DomainProxy implements Filter {

	private Logger log = null;

	public static String FILTER_NAME = "TomcatServerDomainProxyFilter";

	private static final Set<String> REQUEST_HOP_BY_HOP = Set.of("connection", "keep-alive", "proxy-authenticate",
			"proxy-authorization", "te", "trailer", "transfer-encoding", "upgrade", "host", "content-length", "expect");

	private static final Set<String> RESPONSE_HOP_BY_HOP = Set.of("connection", "keep-alive", "proxy-authenticate",
			"proxy-authorization", "te", "trailer", "transfer-encoding", "upgrade", "content-length");

	private static ConfigProperties config = new ConfigProperties();

	/** Reusable HttpClients per connect timeout; owned by this servlet (never shared via the global Cache). */
	private static final ConcurrentHashMap<Long, HttpClient> HTTP_CLIENTS = new ConcurrentHashMap<Long, HttpClient>();

	/** Fallback connect timeout (ms) when the configured value is not positive. */
	private static final long DEFAULT_PROXY_CONNECT_TIMEOUT_MS = 60000L;

	/** Cache key prefix for a domain:port mapping (blank-uri / catch-all config). */
	private static final String DOMAIN_PORT_KEY_PREFIX = "DomainProxy.domainPort.";

	/** Cache key prefix for a domain:port:uri mapping (uri-prefix-specific config). */
	private static final String DOMAIN_URI_KEY_PREFIX = "DomainProxy.domainUri.";

	/** URI-prefix registry: "domain:port" -> configured non-blank uri prefixes.
	 *  Sorted descending so the longest matching prefix is picked first. */
	private static final ConcurrentHashMap<String, java.util.TreeSet<String>> URI_PREFIX_REGISTRY =
			new ConcurrentHashMap<String, java.util.TreeSet<String>>();

	/** B14 fix: guards every TreeSet mutation/iteration so a concurrent mapping
	 *  update can never throw ConcurrentModificationException in matchUri(). */
	private static final Object URI_PREFIX_REGISTRY_LOCK = new Object();

	/**
	 * Normalise a uri mapping value: trim, drop a bare "/", ensure a leading "/",
	 * strip a trailing "/". Blank (or "/") means catch-all (all URIs under the
	 * domain:port match) and is stored as "".
	 */
	static String normalizeUri(String uri) {
		if (uri == null) {
			return "";
		}
		String u = uri.trim();
		if (u.isEmpty() || u.equals("/")) {
			return "";
		}
		if (!u.startsWith("/")) {
			u = "/" + u;
		}
		if (u.length() > 1 && u.endsWith("/")) {
			u = u.substring(0, u.length() - 1);
		}
		return u;
	}

	private static String domainPortKey(String domain, String port) {
		return String.format("%s%s:%s", DOMAIN_PORT_KEY_PREFIX, domain, port);
	}

	private static String domainUriKey(String domain, String port, String uri) {
		return String.format("%s%s:%s:%s", DOMAIN_URI_KEY_PREFIX, domain, port, uri);
	}

	/** Display part for a uri: blank -> "", non-blank -> "/api" (idempotent:
	 *  works on both the raw value and the already-normalised one). */
	private static String uriDisplay(String uri) {
		if (uri == null || uri.isEmpty()) {
			return "";
		}
		return uri.startsWith("/") ? uri : "/" + uri;
	}

	private static void registerUri(String domain, String port, String uri) {
		if (uri == null || uri.isEmpty()) {
			return;
		}
		String key = String.format("%s:%s", domain, port);
		synchronized (URI_PREFIX_REGISTRY_LOCK) {
			java.util.TreeSet<String> set = URI_PREFIX_REGISTRY.computeIfAbsent(key,
					k -> new java.util.TreeSet<String>());
			set.add(uri);
		}
	}

	private static void unregisterUri(String domain, String port, String uri) {
		if (uri == null || uri.isEmpty()) {
			return;
		}
		String key = String.format("%s:%s", domain, port);
		synchronized (URI_PREFIX_REGISTRY_LOCK) {
			java.util.TreeSet<String> set = URI_PREFIX_REGISTRY.get(key);
			if (set == null) {
				return;
			}
			set.remove(uri);
			if (set.isEmpty()) {
				URI_PREFIX_REGISTRY.remove(key);
			}
		}
	}

	/** Longest-prefix match of a request path against the configured uri mappings
	 *  for domain:port; returns the matched uri prefix or null when none matches. */
	private static String matchUri(String domain, String port, String requestPath) {
		String key = String.format("%s:%s", domain, port);
		java.util.TreeSet<String> set = URI_PREFIX_REGISTRY.get(key);
		if (set == null || set.isEmpty()) {
			return null;
		}
		synchronized (URI_PREFIX_REGISTRY_LOCK) {
			java.util.Iterator<String> it = set.descendingIterator();
			while (it.hasNext()) {
				String prefix = it.next();
				if (requestPath.equals(prefix) || requestPath.startsWith(prefix + "/")) {
					return prefix;
				}
			}
		}
		return null;
	}

	public DomainProxy() {
		super();
		log = LoggerFactory.getLogger(this.getClass());
	}

	// Called once during server startup (TomcatServer config load); no concurrent writes.
	public static void setParams(final Map params) {
		config.putAllAndReturnSlef(params);
	}

	@Override
	public void init(FilterConfig filterConfig) throws ServletException {
		// No-op: the shared config is set once by TomcatServer.setParams(...).
	}

	@Override
	public void destroy() {
		// No-op.
	}

	@Override
	public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain chain)
			throws ServletException, IOException {
		HttpServletRequest request = (HttpServletRequest) servletRequest;
		HttpServletResponse response = (HttpServletResponse) servletResponse;

		// Only the original REQUEST dispatch runs proxy resolution. Internal
		// FORWARD dispatches (mode=internal) must fall straight through to the
		// target webapp: the forwarded request keeps the original URI (e.g.
		// /test/), so matching it again would re-forward forever.
		if (request.getDispatcherType() != DispatcherType.REQUEST) {
			chain.doFilter(servletRequest, servletResponse);
			return;
		}

		String domain = request.getServerName();
		String port = request.getLocalPort() + "";
		long begin = System.currentTimeMillis();
		try {
			putConsoleParams(request, response);
			ConfigProperties domainCp = resolveDomainConfig(request, response);
			if (domainCp == null) {
				// No proxy mapping for this domain:port[:uri] — pass the request
				// through the chain so the local webapp (default servlet / JSP)
				// serves it.
				chain.doFilter(servletRequest, servletResponse);
				return;
			}
			applyDomainScript(domainCp, domain, port);

			String target = domainCp.getString("target");
			if (CommonTools.isBlank(target)) {
				safeSendError(response, 502, "Target domain not found.");
				return;
			}

			// The uri-scoped mapping prefix (e.g. "/test_default") is stripped
			// from the request path before it is appended to the target base.
			String matchedUri = matchUri(domain, port, request.getRequestURI());
			String mode = domainCp.getString("mode", "Forward");

			// Internal (same-JVM) dispatch: forwards to a local webapp context
			// (the target's path component, e.g. /default_a) without a second
			// HTTP round-trip; the browser URL keeps the mapped prefix.
			// Cross-context dispatch must go through ServletContext.getContext()
			// + the target context's RequestDispatcher (a bare
			// request.getRequestDispatcher("/default_a/...") only searches the
			// current ROOT context and 404s even with setCrossContext(true)).
			if ("internal".equalsIgnoreCase(mode)) {
				String targetPath = targetPathOf(target);
				if (targetPath == null) {
					safeSendError(response, 502, "Internal target not found: " + target);
					return;
				}
				ServletContext targetCtx = request.getServletContext().getContext(targetPath);
				if (targetCtx == null) {
					safeSendError(response, 404, "Internal target webapp not deployed: " + targetPath);
					return;
				}
				String rest = stripMappingPrefix(matchedUri, request.getRequestURI());
				if (rest.isEmpty()) {
					rest = "/";
				}
				// Directory requests: resolve the target webapp's welcome file
				// ourselves. Tomcat only performs welcome-file lookup on the
				// initial REQUEST dispatch, not on FORWARD, so without this the
				// forwarded "/" would serve a raw directory listing (or 404).
				String dispatchPath = rest;
				if (dispatchPath.endsWith("/")) {
					String welcome = resolveWelcomeFile(targetCtx, dispatchPath);
					if (welcome != null) {
						dispatchPath = welcome;
					}
				}
				// Rewrite the request path so the target context's servlet
				// resolves the mapped (prefix-stripped) path instead of the
				// original URI (Tomcat keeps the original URI on cross-context
				// forward, which would look for /default_a/test/ inside the
				// /default_a webapp).
				final String fRest = dispatchPath;
				final String fTargetPath = targetPath;
				HttpServletRequest rewritten = new HttpServletRequestWrapper(request) {
					@Override
					public String getRequestURI() {
						return fTargetPath + fRest;
					}

					@Override
					public String getContextPath() {
						return fTargetPath;
					}

					@Override
					public String getServletPath() {
						return fRest;
					}

					@Override
					public String getPathInfo() {
						return null;
					}
				};
				RequestDispatcher rd = targetCtx.getRequestDispatcher(dispatchPath);
				if (rd == null) {
					safeSendError(response, 404, "Internal target resource not found: " + targetPath + rest);
					return;
				}
				rd.forward(rewritten, response);
				long spend = System.currentTimeMillis() - begin;
				ServerLog.writeWebLog(domain, port, request.getRequestURI(), "response", System.currentTimeMillis(), request.getQueryString(), ServerLog.getHeaders(request), String.format("spend=%s;buferSize=%s;contentType=%s", Long.valueOf(spend), Integer.valueOf(response.getBufferSize()), response.getContentType()),request.getRemoteAddr());
				return;
			}

			String forwardUrl = buildForwardUrl(target, matchedUri, request);
			byte[] bodyBytes;
			// B11 fix: cap the request body so an unbounded upload cannot exhaust
			// the heap (readAllBytes() was unbounded). Chunked requests without a
			// Content-Length are detected by reading max+1 bytes.
			int maxBodyBytes = 10 * 1024 * 1024;
			try (InputStream is = request.getInputStream()) {
				if (request.getContentLengthLong() > maxBodyBytes) {
					safeSendError(response, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, "Request body too large.");
					return;
				}
				bodyBytes = is.readNBytes(maxBodyBytes + 1);
				if (bodyBytes.length > maxBodyBytes) {
					safeSendError(response, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, "Request body too large.");
					return;
				}
			}
			HttpRequest.Builder builder = buildForwardRequest(domainCp, request, forwardUrl, bodyBytes);

			if (!"Forward".equalsIgnoreCase(mode) && !"Redirect".equalsIgnoreCase(mode)) {
				safeSendError(response, 502, "No action");
				return;
			}
			if ("Redirect".equalsIgnoreCase(mode)) {
				response.setStatus(302);
				response.setHeader("Location", forwardUrl);
				return;
			}
			if ("Forward".equalsIgnoreCase(mode)) {
				forwardRequest(domainCp, request, response, builder, domain, port);
			}
			long spend = System.currentTimeMillis() - begin;
			ServerLog.writeWebLog(domain, port, request.getRequestURI(), "response", System.currentTimeMillis(), request.getQueryString(), ServerLog.getHeaders(request), String.format("spend=%s;buferSize=%s;contentType=%s", Long.valueOf(spend), Integer.valueOf(response.getBufferSize()), response.getContentType()),request.getRemoteAddr());
		} catch (InterruptedException e) {
			this.log.error(e.getMessage(), e);
			long spend2 = System.currentTimeMillis() - begin;
			ServerLog.writeWebLog(domain, port, request.getRequestURI(), "error", System.currentTimeMillis(), request.getQueryString(), ServerLog.getHeaders(request), String.format("spend=%s;buferSize=%s;contentType=%s", Long.valueOf(spend2), Integer.valueOf(response.getBufferSize()), response.getContentType()),request.getRemoteAddr());
			Thread.currentThread().interrupt();
			safeSendError(response, 502, "Proxy interrupted.");
		} catch (Exception e2) {
			// Client abort (HTTP/2 RST, broken pipe, connection reset) while
			// writing the response: there is no one left to receive an error
			// page. Log at debug only so CI logs stay clean.
			if (isClientAbort(e2)) {
				log.debug("Client aborted request: {}", e2.getMessage());
				return;
			}
			this.log.error(e2.getMessage(), e2);
			long spend3 = System.currentTimeMillis() - begin;
			ServerLog.writeWebLog(domain, port, request.getRequestURI(), "error", System.currentTimeMillis(), request.getQueryString(), ServerLog.getHeaders(request), String.format("spend=%s;buferSize=%s;contentType=%s", Long.valueOf(spend3), Integer.valueOf(response.getBufferSize()), response.getContentType()),request.getRemoteAddr());
			safeSendError(response, 502, "Server Error.");
		} finally {
			// B2 fix: always return the console taken by putConsoleParams() on
			// every code path (no mapping / target blank / internal / redirect /
			// forward / exception). freed() is idempotent — it no-ops when this
			// thread holds no console — so it is also safe to call again after
			// applyDomainScript() already freed via getDomainParams().
			TomcatScriptConsole.freed();
		}
	}

	private void putConsoleParams(HttpServletRequest request, HttpServletResponse response) throws Exception {
		Map<String, Object> consoleParams = new HashMap<String, Object>();
		consoleParams.put("request", request);
		consoleParams.put("response", response);
		consoleParams.put("proxy", this);
		TomcatScriptConsole.put(consoleParams);
	}

	private ConfigProperties resolveDomainConfig(HttpServletRequest request, HttpServletResponse response)
			throws IOException {
		String domain = request.getServerName();
		String port = request.getLocalPort() + "";
		String requestPath = request.getRequestURI();
		// 1) Longest-prefix match against the uri-specific mappings, then
		// 2) fall back to the blank-uri (catch-all) domain:port mapping.
		String uri = matchUri(domain, port, requestPath);
		ConfigProperties domainCp = null;
		if (uri != null) {
			domainCp = (ConfigProperties) Cache.get(domainUriKey(domain, port, uri));
		}
		if (domainCp == null) {
			domainCp = (ConfigProperties) Cache.get(domainPortKey(domain, port));
		}
		if (domainCp == null) {
			// No mapping: let the filter chain serve the request from the local
			// webapp instead of answering 502.
			return null;
		}
		return domainCp;
	}

	private void applyDomainScript(ConfigProperties domainCp, String domain, String port) throws Exception {
		if (!domainCp.getBoolean("scriptEnable", true)) {
			return;
		}
		Object domainParams = null;
		if (domainCp.containsKey("scriptFile")) {
			domainParams = TomcatScriptConsole.getDomainParams(domainCp.getString("scriptFile", null));
		} 
		// else {
		// 	domainParams = TomcatScriptConsole.getDomainParams(domain, port);
		// }
		if (domainParams == null) {
			return;
		}
		log.debug("domainParams: {}", domainParams);
		log.debug("domainParams class: {}", domainParams.getClass());
		if (TomcatScriptConsole.getEngineName().equalsIgnoreCase("graalvm.js")) {
			Map domainParamsMap = ((org.graalvm.polyglot.Value) domainParams).as(Map.class);
			domainCp.putAllAndReturnSlef(domainParamsMap);
		} else {
			Map domainParamsMap = (Map) domainParams;
			domainCp.putAllAndReturnSlef(domainParamsMap);
		}
		log.debug("Updated domainCp: {}", domainCp);
	}

	/**
	 * Build the upstream URL for a proxied request.
	 * <p>
	 * uri mapping semantics: the matched mapping prefix ({@code mappingUri}, e.g.
	 * "/test_default") is stripped from the request path and the remainder is
	 * appended to the target base. Example:
	 * 
	 * <pre>
	 * mapping  (127.0.0.1:8080, uri=/test_default, target=https://127.0.0.1/default)
	 * request  GET /test_default/foo?a=1
	 * result   https://127.0.0.1/default/foo?a=1
	 * </pre>
	 * 
	 * A blank/absent uri (catch-all mapping) leaves the request path untouched.
	 */
	private String buildForwardUrl(String target, String mappingUri, HttpServletRequest request) {
		String base = target.endsWith("/") ? target.substring(0, target.length() - 1) : target;
		String path = request.getRequestURI();
		if (path == null || path.isEmpty()) {
			path = "/";
		}
		if (mappingUri != null && !mappingUri.isEmpty()) {
			String prefix = uriDisplay(mappingUri);
			if (path.startsWith(prefix)) {
				path = path.substring(prefix.length());
			}
		}
		if (path.isEmpty()) {
			path = "/";
		}
		StringBuilder sb = new StringBuilder(base).append(path);
		if (request.getQueryString() != null) {
			sb.append('?').append(request.getQueryString());
		}
		return sb.toString();
	}

	/**
	 * Internal (same-JVM) dispatch path for the "internal" mode: the mapping
	 * prefix is stripped from the request path and the remainder is appended to
	 * the target's path component, which is used as the destination webapp
	 * context path. Example:
	 * 
	 * <pre>
	 * mapping  (localhost:8443, uri=/test, target=https://127.0.0.1/default_a)
	 * request  GET /test/foo?a=1
	 * result   RequestDispatcher.forward to /default_a/foo (query string kept)
	 * </pre>
	 * 
	 * Returns {@code null} when the target has no path component (nothing to
	 * dispatch to).
	 */
	private static String targetPathOf(String target) {
		String targetPath;
		try {
			targetPath = URI.create(target).getPath();
		} catch (IllegalArgumentException e) {
			return null;
		}
		if (targetPath == null || targetPath.isEmpty()) {
			return null;
		}
		if (!targetPath.startsWith("/")) {
			targetPath = "/" + targetPath;
		}
		if (targetPath.endsWith("/") && targetPath.length() > 1) {
			targetPath = targetPath.substring(0, targetPath.length() - 1);
		}
		return targetPath;
	}

	/** Strip the matched mapping prefix from a request path (catch-all: unchanged). */
	private static String stripMappingPrefix(String mappingUri, String path) {
		if (path == null || path.isEmpty()) {
			path = "/";
		}
		if (mappingUri != null && !mappingUri.isEmpty()) {
			String prefix = uriDisplay(mappingUri);
			if (path.startsWith(prefix)) {
				path = path.substring(prefix.length());
			}
		}
		return path.isEmpty() ? "/" : path;
	}

	/** Common welcome-file names, in Tomcat's default precedence. */
	private static final String[] WELCOME_CANDIDATES = { "index.html", "index.htm", "index.jsp", "default.htm",
			"default.html" };

	/**
	 * Resolve the welcome file for a directory path inside the target webapp
	 * (null when none of the common names exists). Tomcat's welcome-file lookup
	 * only runs on the initial REQUEST dispatch, so internal FORWARD dispatches
	 * must resolve it themselves.
	 */
	private static String resolveWelcomeFile(ServletContext ctx, String dirPath) {
		if (dirPath == null || dirPath.isEmpty()) {
			dirPath = "/";
		}
		if (!dirPath.endsWith("/")) {
			dirPath = dirPath + "/";
		}
		for (String name : WELCOME_CANDIDATES) {
			String path = dirPath + name;
			try {
				if (ctx.getResource(path) != null) {
					return path;
				}
			} catch (java.net.MalformedURLException e) {
				// Malformed path — try the next candidate.
			}
		}
		return null;
	}

	private HttpRequest.Builder buildForwardRequest(ConfigProperties domainCp, HttpServletRequest request,
			String forwardUrl, byte[] bodyBytes) {
		// Whole-request timeout (connect + waiting for response headers). Configurable
		// globally (DomainProxy.proxyRequestTimeout) and overridable per domain (requestTimeout).
		long defaultRequestTimeoutMs = config.getMilliSeconds("DomainProxy.proxyRequestTimeout", 30000L);
		long requestTimeoutMs = domainCp.getMilliSeconds("requestTimeout", defaultRequestTimeoutMs);
		HttpRequest.Builder builder = HttpRequest.newBuilder().uri(URI.create(forwardUrl))
				.timeout(Duration.ofMillis(requestTimeoutMs)).method(request.getMethod(), bodyBytes.length == 0
						? HttpRequest.BodyPublishers.noBody()
						: HttpRequest.BodyPublishers.ofByteArray(bodyBytes));
		request.getHeaderNames().asIterator().forEachRemaining(headerName -> {
			if (REQUEST_HOP_BY_HOP.contains(headerName.toLowerCase(Locale.ROOT))) {
				return;
			}
			List<String> values = Collections.list(request.getHeaders(headerName));
			for (String v : values) {
				builder.header(headerName, v);
			}
		});
		return builder;
	}

	private void forwardRequest(ConfigProperties domainCp, HttpServletRequest request, HttpServletResponse response,
			HttpRequest.Builder builder, String domain, String port) throws Exception {
		if (domainCp.getBoolean("forwardedHeader", true)) {
			builder.header("X-Forwarded-For", request.getRemoteAddr());
			builder.header("X-Forwarded-Host", request.getServerName());
			builder.header("X-Forwarded-Proto", request.getScheme());
		}

		Map<String, Object> forwardedHeaders = domainCp.getMap("forwardedHeaders");
		if (forwardedHeaders != null) {
			forwardedHeaders.forEach((name, value) -> {
				log.debug("forwardedHeader: {} -> {}", name, value);
				builder.header(name, value + "");
			});
		}

		long defaultConnectTimeoutMs = config.getMilliSeconds("DomainProxy.proxyConnectTimeout", 60000L);
		long connectTimeoutMs = domainCp.getMilliSeconds("connectTimeout", defaultConnectTimeoutMs);

		HttpResponse<InputStream> backendResponse = getHttpClient(connectTimeoutMs).send(builder.build(),
				HttpResponse.BodyHandlers.ofInputStream());

		response.setStatus(backendResponse.statusCode());

		backendResponse.headers().map().forEach((name, values) -> {
			if (RESPONSE_HOP_BY_HOP.contains(name.toLowerCase(Locale.ROOT))) {
				return;
			}
			for (String value : values) {
				response.addHeader(name, value);
			}
		});

		Map<String, Object> customResponseHeaders = domainCp.getMap("responseHeaders");
		if (customResponseHeaders != null) {
			customResponseHeaders.forEach((name, value) -> {
				log.debug("ResponseHeader: {} -> {}", name, value);
				response.addHeader(name, value + "");
			});
		}

		boolean filterEnable = domainCp.getBoolean("filterEnable", false);
		boolean scriptEnable = domainCp.getBoolean("scriptEnable", true);

		try (InputStream backendStream = backendResponse.body();
				OutputStream clientStream = response.getOutputStream()) {
			if (filterEnable && scriptEnable) {
				Map<String, Object> responseParams = new HashMap<String, Object>();
				responseParams.put("request", request);
				responseParams.put("response", response);
				responseParams.put("backendResponse", backendResponse);
				responseParams.put("proxy", this);
				responseParams.put("backendStream", backendStream);
				responseParams.put("clientStream", clientStream);
				TomcatScriptConsole.put(responseParams);
				Object result = null;
				if (domainCp.containsKey("scriptFile")) {
					result = TomcatScriptConsole.getResponseFilter(domainCp.getString("scriptFile", null));
				} else {
					result = TomcatScriptConsole.getResponseFilter(domain, port);
				}
				byte[] responseBytes = null;
				if (result != null) {
					if (TomcatScriptConsole.getEngineName().equalsIgnoreCase("graalvm.js")) {
						responseBytes = ((org.graalvm.polyglot.Value) result).as(byte[].class);
					} else {
						responseBytes = (byte[]) result;
					}
				}
				if (responseBytes == null) {
					backendStream.transferTo(clientStream);
				} else {
					clientStream.write(responseBytes);
					clientStream.flush();
				}
			} else {
				backendStream.transferTo(clientStream);
			}
		}
	}
    private void safeSendError(HttpServletResponse response, int code, String msg) throws IOException {
        if (!response.isCommitted()) {
            boolean errorRedirectEnable = config.getBoolean("errorRedirectEnable", false).booleanValue();
            if (errorRedirectEnable) {
                // B16 fix: never crash the request path on a mis-configured
                // errorRedirectUrl (a template without %s, or with a foreign
                // format spec, used to throw IllegalFormatConversionException).
                String errorRedirectTpl = config.getString("errorRedirectUrl", "http://errorRedirectUrl/%s.html");
                String errorRedirectUrl;
                try {
                    errorRedirectUrl = String.format(errorRedirectTpl, code);
                } catch (IllegalFormatException ife) {
                    errorRedirectUrl = errorRedirectTpl.replace("%s", code + "").replace("%d", code + "");
                }
                response.setStatus(302);
                response.setHeader("Location", errorRedirectUrl);
                return;
            }
            response.sendError(code, msg);
            return;
        }
        this.log.warn("Response already committed, cannot send error: {} {}", new Object[]{Integer.valueOf(code), msg});
    }

    /**
     * True when the failure is a client-side abort (the connection/stream was
     * already closed while we were writing): ClientAbortException, or an
     * IOException whose cause chain mentions broken pipe / connection reset /
     * a closed HTTP/2 stream. Such errors mean there is nobody left to receive
     * an error page, so they are logged at debug to keep the server logs clean.
     */
    private static boolean isClientAbort(Throwable t) {
        for (Throwable cur = t; cur != null; cur = cur.getCause()) {
            if (cur instanceof ClientAbortException) {
                return true;
            }
            String msg = cur.getMessage();
            if (msg != null) {
                String m = msg.toLowerCase(Locale.ROOT);
                if (m.contains("broken pipe") || m.contains("connection reset")
                        || m.contains("not writable") || m.contains("closed_rst")
                        || m.contains("stream is in state")) {
                    return true;
                }
            }
        }
        return false;
    }

    private HttpClient getHttpClient(long connectTimeoutMs) {
        // Guard against non-positive timeouts (Duration.ofMillis(0) throws on build).
        long timeout = connectTimeoutMs > 0 ? connectTimeoutMs : DEFAULT_PROXY_CONNECT_TIMEOUT_MS;
        return HTTP_CLIENTS.computeIfAbsent(Long.valueOf(timeout), ms -> HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(ms))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build());
    }

    public static Map<String, Object> setDomainMapping(String domain, String port, String scriptFile) {
		Map<String,Object> resMap = new HashMap<>();
        if (CommonTools.isBlank(domain)) {
			resMap.put("code",0);
			resMap.put("msg","The param 'domain' is required.");
			return resMap;
        }
        if (CommonTools.isBlank(port)) {
			resMap.put("code",0);
			resMap.put("msg","The param 'port' is required.");
			return resMap;
        }
        if (CommonTools.isBlank(scriptFile)) {
			resMap.put("code",0);
			resMap.put("msg","The param 'scriptFile' is required.");
			return resMap;
        }
        if (!CommonTools.isBlank(scriptFile)) {
			if(!new File(scriptFile).exists()){
				resMap.put("code",0);
				resMap.put("msg",String.format("The file '%s' does not exist.",scriptFile));
				return resMap;
			}
        }		
        Map<String, Object> params = new HashMap<>();
        params.put("scriptEnable", true);
		params.put("scriptFile", scriptFile);
        return setDomainMapping(domain, port, params);
    }

    public static Map<String, Object> setDomainMapping(String domain, String port) {
        Map<String, Object> params = new HashMap<>();
        params.put("scriptEnable", true);
        return setDomainMapping(domain, port, params);
    }

	/** Legacy 3-arg form: no uri (catch-all for every URI under domain:port). */
	public static Map<String, Object> setDomainMapping(String domain, String port, Map<String, Object> params) {
		return setDomainMapping(domain, port, "", params);
	}

	public static Map<String, Object> setDomainMapping(String domain, String port, String uri, Map<String, Object> params) {
		Map<String,Object> resMap = new HashMap<>();
		if (CommonTools.isBlank(domain)) {
			resMap.put("code",0);
			resMap.put("msg","The param 'domain' is null.");
			return resMap;
		}
		if (CommonTools.isBlank(port)) {
			resMap.put("code",0);
			resMap.put("msg","The param 'port' is null.");
			return resMap;
		}
		uri = normalizeUri(uri);
		// mode=internal performs an in-JVM forward to a local webapp; the
		// proxy filter script (scriptEnable) only applies to outbound proxy
		// traffic (Forward/Redirect), so the combination is invalid.
		String mode = params != null ? String.valueOf(params.get("mode")) : null;
		if ("internal".equalsIgnoreCase(mode) && params != null && params.containsKey("scriptEnable")
				&& Boolean.parseBoolean(String.valueOf(params.get("scriptEnable")))) {
			resMap.put("code", 0);
			resMap.put("msg",
					"mode=internal does not support scriptEnable: the filter script only applies to proxy (Forward/Redirect) traffic.");
			return resMap;
		}
		ConfigProperties cp = new ConfigProperties();
		if (params != null) {
			cp.putAll(params);
		}
		String domainPort = String.format("%s:%s", domain, port);
		try {
			if(getDomainMappingParam(domain, port, uri) == null){
				createDomainMappingParam(domain, port, uri, params);
				resMap.put("code",1);
				resMap.put("msg",String.format("DomainMapping %s%s created.", domainPort, uriDisplay(uri)));
			}else{
				updateDomainMappingParam(domain, port, uri, params);
				resMap.put("code",1);
				resMap.put("msg",String.format("DomainMapping %s%s updated.", domainPort, uriDisplay(uri)));
			}
			if (uri.isEmpty()) {
				Cache.set(domainPortKey(domain, port), cp);
				unregisterUri(domain, port, "");
			} else {
				Cache.set(domainUriKey(domain, port, uri), cp);
				registerUri(domain, port, uri);
			}
			return resMap;
		} catch (Exception e) {
			LoggerFactory.getLogger(DomainProxy.class).warn("DomainMapping {} config failed.", domainPort);
			resMap.put("code", -1);
			resMap.put("msg", "Failed to configure DomainMapping.");
			return resMap;
		}
	}

    /** Legacy 2-arg form: removes the blank-uri (catch-all) mapping. */
    public static Map<String, Object> removeDomainMapping(String domain, String port) {
        return removeDomainMapping(domain, port, "");
    }

    public static Map<String, Object> removeDomainMapping(String domain, String port, String uri) {
        Map<String, Object> resMap = new HashMap<>();
        if (CommonTools.isBlank(domain)) {
			resMap.put("code",0);
			resMap.put("msg","The param 'domain' is required.");
			return resMap;
        }
        if (CommonTools.isBlank(port)) {
			resMap.put("code",0);
			resMap.put("msg","The param 'port' is required.");
			return resMap;
        }		
		try{
			uri = normalizeUri(uri);
			removeDomainMappingParam(domain, port, uri);
			String domainPort = String.format("%s:%s", domain, port);
			if (uri.isEmpty()) {
				Cache.remove(domainPortKey(domain, port));
				// Dropping the catch-all invalidates every uri-specific mapping too.
				URI_PREFIX_REGISTRY.remove(String.format("%s:%s", domain, port));
				// B8 fix: also soft-delete the uri-specific DB rows so the removal
				// survives a restart (previously they resurrected on load).
				removeAllDomainMappingParams(domain, port);
			} else {
				Cache.remove(domainUriKey(domain, port, uri));
				unregisterUri(domain, port, uri);
			}
			resMap.put("code",1);
			resMap.put("msg", String.format("DomainMapping %s%s removed.", domainPort, uriDisplay(uri)));
			return resMap;
		}catch(Exception e){
			Logger lg = LoggerFactory.getLogger(DomainProxy.class);
			lg.warn(e.getMessage(), e);
			resMap.put("code", -1);
			resMap.put("msg", "Failed to remove DomainMapping.");
			return resMap;
		}
    }

    /** Legacy 2-arg form: queries the blank-uri (catch-all) mapping. */
    public static Map<String, Object> queryDomainMapping(String domain, String port) {
        return queryDomainMapping(domain, port, "");
    }

    public static Map<String, Object> queryDomainMapping(String domain, String port, String uri) {
        Map<String, Object> resMap = new HashMap<>();
        if (CommonTools.isBlank(domain)) {
			resMap.put("code",0);
			resMap.put("msg","The param 'domain' is required.");
			return resMap;
        }
        if (CommonTools.isBlank(port)) {
			resMap.put("code",0);
			resMap.put("msg","The param 'port' is required.");
			return resMap;
        }
        try {
            uri = normalizeUri(uri);
            Map<String, Object> result = getDomainMappingParam(domain, port, uri);
            if (result == null) {
                resMap.put("code",0);
                resMap.put("msg",String.format("The DomainMapping %s:%s%s does not exist.", domain, port, uriDisplay(uri)));
            } else {
                String msg = "";
                msg += String.format("Id: %s", result.get("id")) + System.lineSeparator();
                msg += String.format("Domain: %s", result.get("domain")) + System.lineSeparator();
                msg += String.format("Port: %s", result.get("port")) + System.lineSeparator();
                msg += String.format("Uri: %s", result.get("uri")) + System.lineSeparator();
                msg += String.format("ParamValue: %s", result.get("param_value")) + System.lineSeparator();
                msg += String.format("CreatedAt: %s", result.get("created_at")) + System.lineSeparator();
                msg += String.format("UpdatedAt: %s", result.get("updated_at")) + System.lineSeparator();
                resMap.put("code",1);
                resMap.put("msg",msg);
            }
            return resMap;
        } catch (Exception e) {
            LoggerFactory.getLogger(DomainProxy.class).warn(e.getMessage(), e);
            resMap.put("code", -1);
            resMap.put("msg", String.format("Failed to query DomainMapping %s:%s%s.", domain, port, uriDisplay(uri)));
            return resMap;
        }
    }

    public static Map<String, Object> queryDomainMappings() {
        Map<String, Object> resMap = new HashMap<>();
        try {
            List<Map<String, Object>> list = getDomainMappingParams();
            int size = list.size();
            if (size == 0) {
                resMap.put("code",0);
                resMap.put("msg","No domain_mapping_param records found.");
                return resMap;
            }
            String msg = "";
            for (int i = 0; i < size; i++) {
                Map item = list.get(i);
                msg += String.format("%s) %s:%s%s", i + 1, item.get("domain"), item.get("port"),
                        uriDisplay((String) item.get("uri"))) + System.lineSeparator();
            }
            resMap.put("code",1);
            resMap.put("msg",msg);
            return resMap;
        } catch (Exception e) {
            LoggerFactory.getLogger(DomainProxy.class).warn(e.getMessage(), e);
            resMap.put("code", -1);
            resMap.put("msg", "Failed to query DomainMappings.");
            return resMap;
        }
    }

    private static List<Map<String, Object>> getDomainMappingParams() throws Exception {
        CacheDriverExecutor cde = null;
        String sql = "SELECT * FROM domain_mapping_param WHERE deleted = :deleted ORDER BY created_at ASC";
        try {
            Connection conn = Db.getConnection();
            Map<String, Object> params = new HashMap<String, Object>();
            params.put("deleted", false);
            cde = new CacheDriverExecutor(conn);
            return cde.find(sql, params);
        } finally {
            Db.closeQuietly(cde);
        }
    }

    private static Map<String, Object> getDomainMappingParam(String domain, String port, String uri) throws Exception {
        CacheDriverExecutor cde = null;
        String sql = "SELECT * FROM domain_mapping_param WHERE domain = :domain AND port = :port AND uri = :uri AND deleted = :deleted";
        try {
            Connection conn = Db.getConnection();
            Map<String, Object> params = new HashMap<String, Object>();
            params.put("domain", domain);
            params.put("port", port);
            params.put("uri", uri);
            params.put("deleted", false);
            cde = new CacheDriverExecutor(conn);
            return cde.first(sql, params);
        } finally {
            Db.closeQuietly(cde);
        }
    }	

    // C12 fix: serialise create/update so two racing setDomainMapping calls
    // cannot both pass the "does it exist" check and write duplicate rows.
    private static synchronized boolean createDomainMappingParam(String domain, String port, String uri, Map<String, Object> domainParams) throws Exception {
		Map<String,Object> domainMappingParam = getDomainMappingParam(domain, port, uri);
		
		if(domainMappingParam != null) throw new Exception(String.format("The domain params %s:%s%s already exists.", domain, port, uriDisplay(uri))); 

        CacheDriverExecutor cde = null;
        String sql = "INSERT INTO domain_mapping_param (id,domain,port,uri,param_value,created_at,updated_at,deleted_at,deleted) VALUES (:id,:domain,:port,:uri,:param_value,:created_at,:updated_at,:deleted_at,:deleted)";
        try {
			String domainParamsJson = Json.format(domainParams);
            Connection conn = Db.getConnection();
			Timestamp currentTime = new Clock().getSqlTimestamp();
            Map<String, Object> params = new HashMap<String, Object>();
			params.put("id", CommonTools.generateId(16));
            params.put("domain", domain);
            params.put("port", port);
			params.put("uri", uri);
			params.put("param_value", domainParamsJson);
			params.put("created_at", currentTime);
			params.put("updated_at", currentTime);
			params.put("deleted_at", null);
            params.put("deleted", false);
            cde = new CacheDriverExecutor(conn);
            return cde.execute(sql, params) > 0;
        } finally {
            Db.closeQuietly(cde);
        }
    }

    // C12 fix: serialise create/update (see createDomainMappingParam).
    private static synchronized boolean updateDomainMappingParam(String domain, String port, String uri, Map<String, Object> domainParams) throws Exception {
		Map<String,Object> domainMappingParam = getDomainMappingParam(domain, port, uri);
		
		if(domainMappingParam == null) throw new Exception(String.format("The domain params %s:%s%s does not exist.", domain, port, uriDisplay(uri)));

        CacheDriverExecutor cde = null;
        String sql = "UPDATE domain_mapping_param SET param_value = :param_value,updated_at = :updated_at WHERE id = :id";
        try {
			String domainParamsJson = Json.format(domainParams);
            Connection conn = Db.getConnection();
			Timestamp currentTime = new Clock().getSqlTimestamp();
            Map<String, Object> params = new HashMap<String, Object>();
			params.put("id", domainMappingParam.get("id"));
			params.put("param_value", domainParamsJson);
			params.put("updated_at", currentTime);
            cde = new CacheDriverExecutor(conn);
            return cde.execute(sql, params) > 0;
        } finally {
            Db.closeQuietly(cde);
        }
    }	

    /** B8 fix: soft-delete every uri-specific mapping for domain:port so a
     *  catch-all removal also clears the DB rows (previously only the runtime
     *  cache/registry were dropped and the uri rows resurrected on restart). */
    private static boolean removeAllDomainMappingParams(String domain, String port) throws Exception {
        CacheDriverExecutor cde = null;
        String sql = "UPDATE domain_mapping_param SET deleted_at = :deleted_at, deleted = :deleted"
                + " WHERE domain = :domain AND port = :port AND uri <> '' AND deleted = false";
        try {
            Connection conn = Db.getConnection();
            Timestamp currentTime = new Clock().getSqlTimestamp();
            Map<String, Object> params = new HashMap<String, Object>();
            params.put("domain", domain);
            params.put("port", port);
            params.put("deleted_at", currentTime);
            params.put("deleted", true);
            cde = new CacheDriverExecutor(conn);
            return cde.execute(sql, params) > 0;
        } finally {
            Db.closeQuietly(cde);
        }
    }

    private static boolean removeDomainMappingParam(String domain, String port, String uri) throws Exception {
		Map<String,Object> domainMappingParam = getDomainMappingParam(domain, port, uri);
		
		if(domainMappingParam == null) throw new Exception(String.format("The domain params %s:%s%s does not exist.", domain, port, uriDisplay(uri)));

        CacheDriverExecutor cde = null;
        String sql = "UPDATE domain_mapping_param SET deleted_at = :deleted_at,deleted = :deleted WHERE id = :id";
        try {
            Connection conn = Db.getConnection();
			Timestamp currentTime = new Clock().getSqlTimestamp();
            Map<String, Object> params = new HashMap<String, Object>();
			params.put("id", domainMappingParam.get("id"));
			params.put("deleted_at", currentTime);
			params.put("deleted", true);
            cde = new CacheDriverExecutor(conn);
            return cde.execute(sql, params) > 0;
        } finally {
            Db.closeQuietly(cde);
        }
    }		

}
