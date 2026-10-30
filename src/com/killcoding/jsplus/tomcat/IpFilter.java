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

import java.io.IOException;
import java.net.InetAddress;
import java.sql.Connection;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IllegalFormatException;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Pattern;

import com.killcoding.log.LoggerFactory;
import com.killcoding.tool.CommonTools;
import com.killcoding.tool.ConfigProperties;
import com.killcoding.log.Logger;
import com.killcoding.cache.Cache;
import com.killcoding.datasource.CacheDriverExecutor;
import com.killcoding.datasource.Clock;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Per-domain IP access control filter.
 * <p>
 * Each rule is one row in the {@code domain_ip_rule} table: a {@code domain:port},
 * a {@code rule_type} ({@code ALLOW} / {@code DENY}) and one {@code ip_pattern}.
 * <p>
 * Pattern formats: exact IP (e.g. {@code 192.168.1.10}), CIDR
 * (e.g. {@code 192.168.1.0/24}) and wildcard (e.g. {@code 192.168.1.*}).
 * Evaluation: an {@code ALLOW} match always passes (allow wins over deny),
 * otherwise a {@code DENY} match is rejected with {@code 403}, otherwise a
 * domain that has allow rules rejects IPs that match none of them (whitelist
 * mode), and a domain with no rules lets everything through. A row with
 * {@code domain = "0"} and {@code port = "0"} acts as a server-wide fallback
 * and applies to every domain that has no rules of its own (same convention
 * as {@code SecurityFilter}).
 */
public class IpFilter implements Filter {

    public final static String FILTER_NAME = "TomcatServerIpFilter";

    public final static String RULE_ALLOW = "ALLOW";
    public final static String RULE_DENY = "DENY";

    private static final Object NO_RULE = new Object();

    private static final Logger LOG = LoggerFactory.getLogger(IpFilter.class);

    private static ConfigProperties config = new ConfigProperties();

    /** Servlet-filter constructor: state is injected later via {@link #setParams(Map)}. */
    public IpFilter() {
        super();
    }

    /**
     * Servlet entry point: enforces the allow/deny rules for the request's
     * domain:port + URI against the client IP. Denied requests get a 403 and a
     * {@code Denied} web-log line; rule/DB errors fail open with a 500.
     */
    @Override
    public void doFilter(ServletRequest arg0, ServletResponse arg1, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) arg0;
        HttpServletResponse response = (HttpServletResponse) arg1;
        String domain = request.getServerName();
        String port = request.getLocalPort() + "";
        String requestUri = request.getRequestURI();
        try {
            // Global switch: IpFilter.enable=false disables all IP checks (requests pass through).
            if (!config.getBoolean("IpFilter.enable", true)) {
                chain.doFilter(request, response);
                return;
            }
            boolean allowed = isAllowed(domain, port, requestUri, request.getRemoteAddr());
            if (allowed) {
                chain.doFilter(request, response);
            } else {
                ServerLog.writeWebLog(domain, port, request.getRequestURI(), "Denied", System.currentTimeMillis(),
                        request.getQueryString(), ServerLog.getHeaders(request),
                        String.format("remoteAddr=%s", request.getRemoteAddr()), request.getRemoteAddr());
                safeSendError(response, HttpServletResponse.SC_FORBIDDEN, "Access denied: IP is not allowed.");
            }
        } catch (Exception e) {
            LOG.error(e.getMessage(), e);
            safeSendError(response, 500, "Server Error.");
        }
    }

    /** Whether the request IP passes the domain:port rules (catch-all URIs). */
    @SuppressWarnings("unchecked")
    private static boolean isAllowed(String domain, String port, String remoteAddr) throws Exception {
        return isAllowed(domain, port, null, remoteAddr);
    }

    /**
     * URI-aware allow check: resolves the longest-prefix URI rule group for the
     * request path, then applies allow-wins-over-deny and whitelist semantics.
     *
     * @return {@code true} when the request may proceed
     */
    @SuppressWarnings("unchecked")
    private static boolean isAllowed(String domain, String port, String requestPath, String remoteAddr)
            throws Exception {
        // Per-domain rules from the domain_ip_rule table (cached);
        // fall back to the global "0:0" rules when the domain has no rules of its own
        // (same convention as SecurityFilter).
        Map<String, Object> rules = getDomainIpRuleFromCache(domain, port);
        if (rules == null) {
            rules = getDomainIpRuleFromCache("0", "0");
        }

        List<String> allow = new ArrayList<String>();
        List<String> deny = new ArrayList<String>();
        if (rules != null) {
            // URI-specific rules (longest-prefix match on the request path) take
            // precedence over the blank-uri (catch-all) rules for that domain:port.
            Map<String, Object> uriGroup = findLongestUriRules(rules, requestPath);
            if (uriGroup != null) {
                allow.addAll((List<String>) uriGroup.get("allow"));
                deny.addAll((List<String>) uriGroup.get("deny"));
            } else {
                allow.addAll((List<String>) rules.get("allow"));
                deny.addAll((List<String>) rules.get("deny"));
            }
        }

        // Allow wins over deny: a matching ALLOW rule passes first, regardless of DENY.
        if (matchAny(remoteAddr, allow)) {
            return true;
        }
        if (matchAny(remoteAddr, deny)) {
            return false;
        }
        // Whitelist mode: allow rules exist but the IP does not match any of them.
        if (!allow.isEmpty()) {
            return false;
        }
        return true;
    }

    /**
     * True when the request would be rejected by the current allow/deny rules
     * (a {@code 403} would be sent by {@link #doFilter}). Used by
     * {@link IpAttackDefense} to skip request logging for already-blocked IPs.
     * Fail-open: on any rule/DB error the request is treated as allowed.
     */
    public static boolean isIpDenied(String domain, String port, String remoteAddr) {
        return isIpDenied(domain, port, null, remoteAddr);
    }

    /**
     * URI-aware variant of {@link #isIpDenied}: the request path is checked
     * against URI-scoped rules (longest-prefix match) before the catch-all rules.
     */
    public static boolean isIpDenied(String domain, String port, String requestPath, String remoteAddr) {
        if (!config.getBoolean("IpFilter.enable", true)) {
            return false;
        }
        try {
            return !isAllowed(domain, port, requestPath, remoteAddr);
        } catch (Exception e) {
            LOG.warn("isIpDenied check failed, treated as allowed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Loads the domain:port rule group (allow/deny lists + URI-scoped groups)
     * from the cache, falling back to the DB on cache miss.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> getDomainIpRuleFromCache(String domain, String port) throws Exception {
        String cacheKey = ipRuleCacheKey(domain, port);
        Object cached = Cache.get(cacheKey);
        if (cached == NO_RULE) {
            return null;
        }
        if (cached != null) {
            return (Map<String, Object>) cached;
        }
        List<Map<String, Object>> rows = getDomainIpRules(domain, port);
        Map<String, Object> rules = new HashMap<String, Object>();
        List<String> allow = new ArrayList<String>();
        List<String> deny = new ArrayList<String>();
        // uri -> {allow:[...], deny:[...]}; prefixes kept in a descending TreeSet
        // so a request path can pick the longest matching uri prefix.
        Map<String, Map<String, Object>> uriRules = new HashMap<String, Map<String, Object>>();
        TreeSet<String> uriPrefixes = new TreeSet<String>();
        for (Map<String, Object> row : rows) {
            String ruleType = String.valueOf(row.get("rule_type"));
            String ipPattern = (String) row.get("ip_pattern");
            String uri = normalizeUri(String.valueOf(row.get("uri")));
            if (uri.isEmpty()) {
                if (RULE_ALLOW.equalsIgnoreCase(ruleType)) {
                    allow.add(ipPattern);
                } else {
                    deny.add(ipPattern);
                }
            } else {
                Map<String, Object> group = uriRules.get(uri);
                if (group == null) {
                    group = new HashMap<String, Object>();
                    group.put("allow", new ArrayList<String>());
                    group.put("deny", new ArrayList<String>());
                    uriRules.put(uri, group);
                    uriPrefixes.add(uri);
                }
                List<String> bucket = RULE_ALLOW.equalsIgnoreCase(ruleType)
                        ? (List<String>) group.get("allow") : (List<String>) group.get("deny");
                bucket.add(ipPattern);
            }
        }
        rules.put("allow", allow);
        rules.put("deny", deny);
        rules.put("uriRules", uriRules);
        rules.put("uriPrefixes", uriPrefixes);
        Integer cacheSeconds = config.getInteger("IpFilter.cacheSeconds", 30);
        // D3 fix (Core 6.1.163): Cache.set treats life==0 as "never expire",
        // while the project convention is 0 = do not cache.
        if (cacheSeconds != null && cacheSeconds > 0) {
            Cache.set(cacheKey, rows.isEmpty() ? NO_RULE : rules, cacheSeconds);
        }
        return rows.isEmpty() ? null : rules;
    }

    /** Cache key for a domain:port rule group ({@code iprules:<domain>:<port>}). */
    private static String ipRuleCacheKey(String domain, String port) {
        return String.format("IpFilter.%s:%s.rules", domain == null ? "" : domain.toLowerCase(),
                port == null ? "" : port.toLowerCase());
    }

    /**
     * Picks the URI rule group whose {@code uri} prefix best matches the request
     * path (longest-prefix wins); {@code null} means fall back to the catch-all
     * (blank-uri) rules.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> findLongestUriRules(Map<String, Object> rules, String requestPath) {
        if (rules == null || requestPath == null || requestPath.isEmpty()) {
            return null;
        }
        TreeSet<String> prefixes = (TreeSet<String>) rules.get("uriPrefixes");
        if (prefixes == null || prefixes.isEmpty()) {
            return null;
        }
        Map<String, Map<String, Object>> uriRules = (Map<String, Map<String, Object>>) rules.get("uriRules");
        for (String prefix : prefixes.descendingSet()) {
            if (requestPath.equals(prefix) || requestPath.startsWith(prefix + "/")) {
                return uriRules.get(prefix);
            }
        }
        return null;
    }

    /** Normalizes a URI for rule matching (null-safe, lowercase path). */
    private static String normalizeUri(String uri) {
        if (uri == null) {
            return "";
        }
        String u = uri.trim();
        if (u.isEmpty() || "/".equals(u)) {
            return "";
        }
        if (!u.startsWith("/")) {
            u = "/" + u;
        }
        while (u.length() > 1 && u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        return u;
    }

    /** Human-readable URI for logs (empty URI shows as {@code *}). */
    private static String uriDisplay(String uri) {
        if (uri == null || uri.isEmpty()) {
            return "";
        }
        return uri.startsWith("/") ? uri : "/" + uri;
    }

    /** Whether the IP matches any of the patterns (exact / CIDR / wildcard). */
    private static boolean matchAny(String ip, List<String> patterns) {
        for (String pattern : patterns) {
            if (ipMatches(ip, pattern)) {
                return true;
            }
        }
        return false;
    }

    /** Matches a single pattern: exact IP, {@code ip/cidr} or wildcard form. */
    private static boolean ipMatches(String ip, String pattern) {
        if (CommonTools.isBlank(ip) || CommonTools.isBlank(pattern)) {
            return false;
        }
        ip = ip.trim();
        pattern = pattern.trim();
        if (pattern.indexOf('/') > 0) {
            return cidrMatch(ip, pattern);
        }
        if (pattern.indexOf('*') >= 0) {
            return Pattern.compile(wildcardToRegex(pattern)).matcher(ip).matches();
        }
        return ip.equalsIgnoreCase(pattern);
    }

    /** Converts a wildcard pattern ({@code 192.168.1.*}) to a regex. */
    private static String wildcardToRegex(String pattern) {
        StringBuilder sb = new StringBuilder("^");
        for (char c : pattern.toCharArray()) {
            if (c == '*') {
                sb.append(".*");
            } else if ("\\.[]{}()+-^$|?".indexOf(c) >= 0) {
                sb.append('\\').append(c);
            } else {
                sb.append(c);
            }
        }
        return sb.append('$').toString();
    }

    /** Whether the IP lies inside the given {@code ip/prefix} CIDR range. */
    private static boolean cidrMatch(String ip, String cidr) {
        try {
            int idx = cidr.indexOf('/');
            int prefix = Integer.parseInt(cidr.substring(idx + 1).trim());
            String netStr = cidr.substring(0, idx).trim();
            byte[] addr = InetAddress.getByName(ip).getAddress();
            byte[] net = InetAddress.getByName(netStr).getAddress();
            if (addr.length != net.length) {
                return false;
            }
            // C4 fix: an out-of-range prefix (e.g. /-1 or /99) used to behave
            // like /32 (or /0) and could match unintended traffic; reject it.
            if (prefix < 0 || prefix > addr.length * 8) {
                return false;
            }
            int bits = prefix;
            for (int i = 0; i < addr.length && bits > 0; i++) {
                int maskByte = bits >= 8 ? 0xFF : ((0xFF << (8 - bits)) & 0xFF);
                if ((addr[i] & maskByte) != (net[i] & maskByte)) {
                    return false;
                }
                bits -= 8;
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ------------------------------------------------------------------
    // Management API (called from the JS console via TomcatEmbed.js)
    // ------------------------------------------------------------------

    /** Validates/normalizes a rule type; on failure writes {@code code=1 + msg} into resMap. */
    private static String normalizeRuleType(String ruleType, Map<String, Object> resMap) {
        if (CommonTools.isBlank(ruleType)) {
            resMap.put("code", 0);
            resMap.put("msg", "The param 'ruleType' is required (ALLOW or DENY).");
            return null;
        }
        String normalized = ruleType.trim().toUpperCase();
        if (!RULE_ALLOW.equals(normalized) && !RULE_DENY.equals(normalized)) {
            resMap.put("code", 0);
            resMap.put("msg", String.format("The param 'ruleType' must be ALLOW or DENY, got '%s'.", ruleType));
            return null;
        }
        return normalized;
    }

    public synchronized static Map<String, Object> createDomainIpRule(String domain, String port, String ruleType,
            String ipPattern) {
        return createDomainIpRule(domain, port, "", ruleType, ipPattern);
    }

    /** Create a rule scoped to {@code domain:port} (blank uri = every URI) or to
     *  {@code domain:port/uri} (uri = longest-prefix match on the request path). */
    public synchronized static Map<String, Object> createDomainIpRule(String domain, String port, String uri,
            String ruleType, String ipPattern) {
        Map<String, Object> resMap = new HashMap<String, Object>();
        if (CommonTools.isBlank(domain)) {
            resMap.put("code", 0);
            resMap.put("msg", "The param 'domain' is required.");
            return resMap;
        }
        if (CommonTools.isBlank(port)) {
            resMap.put("code", 0);
            resMap.put("msg", "The param 'port' is required.");
            return resMap;
        }
        String normalizedType = normalizeRuleType(ruleType, resMap);
        if (normalizedType == null) {
            return resMap;
        }
        if (CommonTools.isBlank(ipPattern)) {
            resMap.put("code", 0);
            resMap.put("msg", "The param 'ipPattern' is required.");
            return resMap;
        }

        String sql = "INSERT INTO domain_ip_rule (id,domain,port,uri,rule_type,ip_pattern,created_at,updated_at,deleted_at,deleted) VALUES (:id,:domain,:port,:uri,:rule_type,:ip_pattern,:created_at,:updated_at,:deleted_at,:deleted)";
        CacheDriverExecutor cde = null;
        try {
            domain = domain.toLowerCase();
            port = port.toLowerCase();
            uri = normalizeUri(uri);
            ipPattern = ipPattern.trim();
            if (getManualDomainIpRule(domain, port, uri, normalizedType, ipPattern) != null) {
                resMap.put("code", 0);
                resMap.put("msg", String.format("The %s:%s%s [%s] %s already exists (manual rule).", domain, port,
                        uriDisplay(uri), normalizedType, ipPattern));
                return resMap;
            }
            Timestamp currentTime = new Clock().getSqlTimestamp();
            Connection conn = Db.getConnection();
            Map<String, Object> params = new HashMap<String, Object>();
            params.put("id", CommonTools.generateId(16));
            params.put("domain", domain);
            params.put("port", port);
            params.put("uri", uri);
            params.put("rule_type", normalizedType);
            params.put("ip_pattern", ipPattern);
            params.put("created_at", currentTime);
            params.put("updated_at", currentTime);
            params.put("deleted_at", null);
            params.put("deleted", false);
            cde = new CacheDriverExecutor(conn);
            cde.execute(sql, params);
            invalidateCache(domain, port);
            resMap.put("code", 1);
            resMap.put("msg",
                    String.format("DomainIpRule %s:%s%s [%s] %s created successfully.", domain, port,
                            uriDisplay(uri), normalizedType, ipPattern));
            return resMap;
        } catch (Exception e) {
            LOG.warn(e.getMessage(), e);
            resMap.put("code", -1);
            resMap.put("msg", "Failed to create DomainIpRule.");
            return resMap;
        } finally {
            Db.closeQuietly(cde);
        }
    }

    public synchronized static Map<String, Object> updateDomainIpRule(String domain, String port, String ruleType,
            String oldIpPattern, String newIpPattern) {
        return updateDomainIpRule(domain, port, "", ruleType, oldIpPattern, newIpPattern);
    }

    /** Update a rule scoped to {@code domain:port/uri} (blank uri = catch-all). */
    public synchronized static Map<String, Object> updateDomainIpRule(String domain, String port, String uri,
            String ruleType, String oldIpPattern, String newIpPattern) {
        Map<String, Object> resMap = new HashMap<String, Object>();
        if (CommonTools.isBlank(domain)) {
            resMap.put("code", 0);
            resMap.put("msg", "The param 'domain' is required.");
            return resMap;
        }
        if (CommonTools.isBlank(port)) {
            resMap.put("code", 0);
            resMap.put("msg", "The param 'port' is required.");
            return resMap;
        }
        String normalizedType = normalizeRuleType(ruleType, resMap);
        if (normalizedType == null) {
            return resMap;
        }
        if (CommonTools.isBlank(oldIpPattern) || CommonTools.isBlank(newIpPattern)) {
            resMap.put("code", 0);
            resMap.put("msg", "The params 'oldIpPattern' and 'newIpPattern' are required.");
            return resMap;
        }

        String sql = "UPDATE domain_ip_rule SET ip_pattern = :ip_pattern,updated_at = :updated_at WHERE id = :id";
        CacheDriverExecutor cde = null;
        try {
            domain = domain.toLowerCase();
            port = port.toLowerCase();
            uri = normalizeUri(uri);
            oldIpPattern = oldIpPattern.trim();
            newIpPattern = newIpPattern.trim();
            Map<String, Object> existing = getManualDomainIpRule(domain, port, uri, normalizedType, oldIpPattern);
            if (existing == null) {
                resMap.put("code", 0);
                resMap.put("msg", String.format("The %s:%s%s [%s] %s does not exist (manual rule).", domain, port,
                        uriDisplay(uri), normalizedType, oldIpPattern));
                return resMap;
            }
            if (!oldIpPattern.equalsIgnoreCase(newIpPattern)
                    && getManualDomainIpRule(domain, port, uri, normalizedType, newIpPattern) != null) {
                resMap.put("code", 0);
                resMap.put("msg", String.format("The %s:%s%s [%s] %s already exists (manual rule).", domain, port,
                        uriDisplay(uri), normalizedType, newIpPattern));
                return resMap;
            }
            Timestamp currentTime = new Clock().getSqlTimestamp();
            Connection conn = Db.getConnection();
            Map<String, Object> params = new HashMap<String, Object>();
            params.put("id", existing.get("id"));
            params.put("ip_pattern", newIpPattern);
            params.put("updated_at", currentTime);
            cde = new CacheDriverExecutor(conn);
            cde.execute(sql, params);
            invalidateCache(domain, port);
            resMap.put("code", 1);
            resMap.put("msg", String.format("DomainIpRule %s:%s%s [%s] %s updated successfully.", domain, port,
                    uriDisplay(uri), normalizedType, newIpPattern));
            return resMap;
        } catch (Exception e) {
            LOG.warn(e.getMessage(), e);
            resMap.put("code", -1);
            resMap.put("msg", "Failed to update DomainIpRule.");
            return resMap;
        } finally {
            Db.closeQuietly(cde);
        }
    }

    public static Map<String, Object> removeDomainIpRule(String domain, String port, String ruleType,
            String ipPattern) {
        return removeDomainIpRule(domain, port, "", ruleType, ipPattern);
    }

    /** Remove a rule scoped to {@code domain:port/uri} (blank uri = catch-all). */
    public static Map<String, Object> removeDomainIpRule(String domain, String port, String uri, String ruleType,
            String ipPattern) {
        Map<String, Object> resMap = new HashMap<String, Object>();
        if (CommonTools.isBlank(domain)) {
            resMap.put("code", 0);
            resMap.put("msg", "The param 'domain' is required.");
            return resMap;
        }
        if (CommonTools.isBlank(port)) {
            resMap.put("code", 0);
            resMap.put("msg", "The param 'port' is required.");
            return resMap;
        }
        String normalizedType = normalizeRuleType(ruleType, resMap);
        if (normalizedType == null) {
            return resMap;
        }
        if (CommonTools.isBlank(ipPattern)) {
            resMap.put("code", 0);
            resMap.put("msg", "The param 'ipPattern' is required.");
            return resMap;
        }

        String sql = "UPDATE domain_ip_rule SET deleted = :deleted,deleted_at = :deleted_at WHERE id = :id";
        CacheDriverExecutor cde = null;
        try {
            domain = domain.toLowerCase();
            port = port.toLowerCase();
            uri = normalizeUri(uri);
            ipPattern = ipPattern.trim();
            Map<String, Object> existing = getManualDomainIpRule(domain, port, uri, normalizedType, ipPattern);
            if (existing == null) {
                resMap.put("code", 0);
                resMap.put("msg", String.format("The %s:%s%s [%s] %s does not exist (manual rule).", domain, port,
                        uriDisplay(uri), normalizedType, ipPattern));
                return resMap;
            }
            Timestamp currentTime = new Clock().getSqlTimestamp();
            Connection conn = Db.getConnection();
            Map<String, Object> params = new HashMap<String, Object>();
            params.put("id", existing.get("id"));
            params.put("deleted", true);
            params.put("deleted_at", currentTime);
            cde = new CacheDriverExecutor(conn);
            cde.execute(sql, params);
            invalidateCache(domain, port);
            resMap.put("code", 1);
            resMap.put("msg", String.format("DomainIpRule %s:%s%s [%s] %s removed successfully.", domain, port,
                    uriDisplay(uri), normalizedType, ipPattern));
            return resMap;
        } catch (Exception e) {
            LOG.warn(e.getMessage(), e);
            resMap.put("code", -1);
            resMap.put("msg", "Failed to remove DomainIpRule.");
            return resMap;
        } finally {
            Db.closeQuietly(cde);
        }
    }

    public static Map<String, Object> queryDomainIpRule(String domain, String port, String ruleType,
            String ipPattern) {
        return queryDomainIpRule(domain, port, "", ruleType, ipPattern);
    }

    /** Query a rule scoped to {@code domain:port/uri} (blank uri = catch-all). */
    public static Map<String, Object> queryDomainIpRule(String domain, String port, String uri, String ruleType,
            String ipPattern) {
        Map<String, Object> resMap = new HashMap<String, Object>();
        String msg = "";
        try {
            Map<String, Object> result = getDomainIpRule(domain, port, uri, ruleType, ipPattern);
            if (result == null) {
                resMap.put("code", 0);
                resMap.put("msg", String.format("The %s:%s%s [%s] %s does not exist.", domain, port,
                        uriDisplay(normalizeUri(uri)),
                        ruleType == null ? "" : ruleType, ipPattern == null ? "" : ipPattern));
            } else {
                msg += String.format("Id: %s", result.get("id")) + System.lineSeparator();
                msg += String.format("Domain: %s", result.get("domain")) + System.lineSeparator();
                msg += String.format("Port: %s", result.get("port")) + System.lineSeparator();
                msg += String.format("Uri: %s", result.get("uri") == null ? "" : result.get("uri"))
                        + System.lineSeparator();
                msg += String.format("RuleType: %s", result.get("rule_type")) + System.lineSeparator();
                msg += String.format("IpPattern: %s", result.get("ip_pattern")) + System.lineSeparator();
                msg += String.format("Source: %s", result.get("ip_ban_id") == null ? "manual" : "ip_ban")
                        + System.lineSeparator();
                msg += String.format("IpBanId: %s", result.get("ip_ban_id") == null ? "" : result.get("ip_ban_id"))
                        + System.lineSeparator();
                msg += String.format("CreatedAt: %s", result.get("created_at")) + System.lineSeparator();
                msg += String.format("UpdatedAt: %s", result.get("updated_at")) + System.lineSeparator();
                resMap.put("code", 1);
                resMap.put("msg", msg);
            }
            return resMap;
        } catch (Exception e) {
            LOG.warn(e.getMessage(), e);
            resMap.put("code", -1);
            resMap.put("msg", String.format("Failed to query DomainIpRule %s:%s.", domain, port));
            return resMap;
        }
    }

    /** Returns all IP rules as a management-API result map ({@code code/msg/data}). */
    public static Map<String, Object> queryDomainIpRules() {
        Map<String, Object> resMap = new HashMap<String, Object>();
        String msg = "";
        try {
            List<Map<String, Object>> list = getDomainIpRules();
            int size = list.size();
            if (size == 0) {
                resMap.put("code", 0);
                resMap.put("msg", "No domain_ip_rule records found.");
                return resMap;
            }
            for (int i = 0; i < size; i++) {
                Map item = list.get(i);
                String ipBanId = item.get("ip_ban_id") == null ? "" : String.valueOf(item.get("ip_ban_id"));
                String uri = item.get("uri") == null ? "" : String.valueOf(item.get("uri"));
                msg += String.format("%s) %s:%s%s [%s] %s (%s)", i + 1, item.get("domain"), item.get("port"),
                        uriDisplay(normalizeUri(uri)),
                        item.get("rule_type"), item.get("ip_pattern"),
                        CommonTools.isBlank(ipBanId) ? "manual" : "ip_ban") + System.lineSeparator();
            }
            resMap.put("code", 1);
            resMap.put("msg", msg);
            return resMap;
        } catch (Exception e) {
            LOG.warn(e.getMessage(), e);
            resMap.put("code", -1);
            resMap.put("msg", "Failed to query DomainIpRules.");
            return resMap;
        }
    }

    /** Reloads all IP rules into cache (management API: force refresh). */
    public static Map<String, Object> loadDomainIpRules() {
        Map<String, Object> resMap = new HashMap<String, Object>();
        try {
            List<Map<String, Object>> domains = getDistinctDomains();
            for (Map<String, Object> item : domains) {
                String domain = (String) item.get("domain");
                String port = (String) item.get("port");
                // D1 fix: Cache.set(key, null, ...) is a no-op (MemoryCache
                // skips null values), so eviction must use Cache.remove instead.
                Cache.remove(ipRuleCacheKey(domain, port));
            }
            resMap.put("code", 1);
            resMap.put("msg", String.format("IpFilter cache cleared for %d domain(s).", domains.size()));
            return resMap;
        } catch (Exception e) {
            LOG.warn(e.getMessage(), e);
            resMap.put("code", -1);
            resMap.put("msg", "Failed to reload DomainIpRules.");
            return resMap;
        }
    }

    /** Evicts the cached rule group for a domain:port after a write. */
    private static void invalidateCache(String domain, String port) {
        try {
            // D1 fix: Cache.set(key, null, ...) is a no-op; use Cache.remove so
            // the next request re-reads the DB instead of serving stale rules.
            Cache.remove(ipRuleCacheKey(domain, port));
        } catch (Exception e) {
            LOG.error(e.getMessage(), e);
        }
    }

    private static Map<String, Object> getDomainIpRule(String domain, String port, String uri, String ruleType,
            String ipPattern) throws Exception {
        CacheDriverExecutor cde = null;
        String sql = "SELECT * FROM domain_ip_rule WHERE domain = :domain AND port = :port AND COALESCE(uri,'') = :uri AND rule_type = :rule_type AND ip_pattern = :ip_pattern AND deleted = :deleted";
        try {
            Connection conn = Db.getConnection();
            Map<String, Object> params = new HashMap<String, Object>();
            params.put("domain", domain.toLowerCase());
            params.put("port", port.toLowerCase());
            params.put("uri", normalizeUri(uri));
            params.put("rule_type", ruleType == null ? "" : ruleType.trim().toUpperCase());
            params.put("ip_pattern", ipPattern == null ? "" : ipPattern.trim());
            params.put("deleted", false);
            cde = new CacheDriverExecutor(conn);
            return cde.first(sql, params);
        } finally {
            Db.closeQuietly(cde);
        }
    }

    /**
     * Manual-only lookup: rows that were created by an automatic {@code ip_ban}
     * ({@code ip_ban_id} NOT NULL) are excluded, so the create/update/remove
     * API never touches or is blocked by auto-ban rows. To lift an auto ban,
     * use {@code IpAttackDefense.removeIpBan(...)} instead.
     */
    private static Map<String, Object> getManualDomainIpRule(String domain, String port, String uri, String ruleType,
            String ipPattern) throws Exception {
        CacheDriverExecutor cde = null;
        String sql = "SELECT * FROM domain_ip_rule WHERE domain = :domain AND port = :port AND COALESCE(uri,'') = :uri AND rule_type = :rule_type AND ip_pattern = :ip_pattern AND deleted = :deleted AND ip_ban_id IS NULL";
        try {
            Connection conn = Db.getConnection();
            Map<String, Object> params = new HashMap<String, Object>();
            params.put("domain", domain.toLowerCase());
            params.put("port", port.toLowerCase());
            params.put("uri", normalizeUri(uri));
            params.put("rule_type", ruleType == null ? "" : ruleType.trim().toUpperCase());
            params.put("ip_pattern", ipPattern == null ? "" : ipPattern.trim());
            params.put("deleted", false);
            cde = new CacheDriverExecutor(conn);
            return cde.first(sql, params);
        } finally {
            Db.closeQuietly(cde);
        }
    }

    /**
     * Internal helper for IpAttackDefense: soft-delete the DENY rule that was
     * written by an automatic {@code ip_ban}, identified by its {@code ip_ban_id}.
     * Manual rules are never touched. Not exposed as a JS API.
     */
    public static Map<String, Object> removeDomainIpRuleByBan(String domain, String port, String ip,
            String banId) {
        Map<String, Object> resMap = new HashMap<String, Object>();
        if (CommonTools.isBlank(banId)) {
            resMap.put("code", 0);
            resMap.put("msg", "The param 'banId' is required.");
            return resMap;
        }
        CacheDriverExecutor cde = null;
        try {
            String sql = "UPDATE domain_ip_rule SET deleted = :deleted, deleted_at = :deleted_at WHERE domain = :domain AND port = :port AND COALESCE(uri,'') = :uri AND rule_type = :rule_type AND ip_pattern = :ip_pattern AND ip_ban_id = :ip_ban_id AND deleted = :deleted_flag";
            Connection conn = Db.getConnection();
            Map<String, Object> params = new HashMap<String, Object>();
            params.put("domain", domain.toLowerCase());
            params.put("port", port.toLowerCase());
            params.put("uri", "");
            params.put("rule_type", RULE_DENY);
            params.put("ip_pattern", ip == null ? "" : ip.trim());
            params.put("ip_ban_id", banId);
            params.put("deleted", true);
            params.put("deleted_flag", false);
            cde = new CacheDriverExecutor(conn);
            int rows = cde.execute(sql, params);
            invalidateCache(domain, port);
            if (rows == 0) {
                // C5 fix: a stale/unknown banId used to report success even
                // though nothing was removed; report it as not found instead.
                resMap.put("code", 0);
                resMap.put("msg", "No DomainIpRule created by this ip_ban found to remove.");
                return resMap;
            }
            resMap.put("code", 1);
            resMap.put("msg", "DomainIpRule created by ip_ban removed successfully.");
            return resMap;
        } catch (Exception e) {
            LOG.warn(e.getMessage(), e);
            resMap.put("code", -1);
            resMap.put("msg", "Failed to remove DomainIpRule created by ip_ban.");
            return resMap;
        } finally {
            Db.closeQuietly(cde);
        }
    }

    /** Active (non-deleted) rules for a domain:port from the {@code domain_ip_rule} table. */
    private static List<Map<String, Object>> getDomainIpRules(String domain, String port) throws Exception {
        CacheDriverExecutor cde = null;
        String sql = "SELECT * FROM domain_ip_rule WHERE domain = :domain AND port = :port AND deleted = :deleted ORDER BY created_at ASC";
        try {
            Connection conn = Db.getConnection();
            Map<String, Object> params = new HashMap<String, Object>();
            params.put("domain", domain.toLowerCase());
            params.put("port", port.toLowerCase());
            params.put("deleted", false);
            cde = new CacheDriverExecutor(conn);
            return cde.find(sql, params);
        } finally {
            Db.closeQuietly(cde);
        }
    }

    /** All active rules across every domain (management API). */
    private static List<Map<String, Object>> getDomainIpRules() throws Exception {
        CacheDriverExecutor cde = null;
        String sql = "SELECT * FROM domain_ip_rule WHERE deleted = :deleted ORDER BY created_at ASC";
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

    /** Distinct non-deleted domains present in the rule table. */
    private static List<Map<String, Object>> getDistinctDomains() throws Exception {
        CacheDriverExecutor cde = null;
        String sql = "SELECT DISTINCT domain, port FROM domain_ip_rule WHERE deleted = :deleted";
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

    /** Sends an HTTP error page without exposing stack traces to the client. */
    private void safeSendError(HttpServletResponse response, int code, String msg) throws IOException {
        if (!response.isCommitted()) {
            boolean errorRedirectEnable = config.getBoolean("errorRedirectEnable", false);
            if (errorRedirectEnable) {
                // B16 fix: never crash the request path on a mis-configured
                // errorRedirectUrl (a template without %s, or with a foreign
                // format spec, used to throw IllegalFormatConversionException).
                String errorRedirectTpl = config.getString("errorRedirectUrl", "http://errorRedirectUrl/%s.html");
                String errorRedirectUrl;
                try {
                    errorRedirectUrl = String.format(errorRedirectTpl, code + "");
                } catch (IllegalFormatException ife) {
                    errorRedirectUrl = errorRedirectTpl.replace("%s", code + "").replace("%d", code + "");
                }
                response.setStatus(302);
                response.setHeader("Location", errorRedirectUrl);
            } else {
                response.sendError(code, msg);
            }
        } else {
            LOG.warn("Response already committed, cannot send error: {} {}", code, msg);
        }
    }

    // Called once during server startup (TomcatServer config load); no concurrent writes.
    /** Applies the {@code IpFilter} configuration block at startup. */
    public static void setParams(final Map params) {
        config.putAllAndReturnSelf(params);
    }

}
