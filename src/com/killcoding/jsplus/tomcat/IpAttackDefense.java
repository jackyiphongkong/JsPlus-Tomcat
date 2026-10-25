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
import java.sql.Connection;
import java.sql.Timestamp;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.killcoding.cache.Cache;
import com.killcoding.cache.CacheArray;
import com.killcoding.cache.CacheArrayFilter;
import com.killcoding.datasource.CacheDriverExecutor;
import com.killcoding.datasource.Clock;
import com.killcoding.log.LoggerFactory;
import com.killcoding.tool.CommonTools;
import com.killcoding.tool.ConfigProperties;
import com.killcoding.log.Logger;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Per-IP request logging and attack defense.
 * <p>
 * Every request is queued ({@link CacheArray}) and flushed to the
 * {@code ip_request_log} table in batches via {@link CacheDriverExecutor#executeBatch}
 * (asynchronous, like {@link ServerLog}).
 * <p>
 * Defense rules are stored in {@code ip_defense_rule}; each rule counts requests
 * per IP with a sliding-window cache counter and, once the threshold is hit,
 * automatically writes a {@code DENY} rule into {@code domain_ip_rule} (so
 * {@link IpFilter} blocks the IP immediately) and records the ban in
 * {@code ip_ban} for later expiry.
 * <p>
 * A rule matches by {@code domain:port} ({@code "0":"0"} makes it server-wide).
 * When {@code uri_pattern} is set, the request URI must also match the regex;
 * when it is blank, only the {@code domain:port} is checked.
 */
public class IpAttackDefense implements Filter {

    public final static String FILTER_NAME = "TomcatServerIpAttackDefense";

    private static final long EXPIRE_CHECK_MIN_INTERVAL_MS = 5000L;

    /** Minimum interval between ip_request_log retention cleanups (1 hour). */
    private static final long LOG_CLEANUP_MIN_INTERVAL_MS = 3600000L;

    /** Cache prefix marking an IP as auto-banned; TTL = ban_seconds, so it expires with the ban. */
    private static final String BANNED_IP_CACHE_PREFIX = "IpAttackDefense.banned.";

    private static CacheArray requestLogQueue = null;

    private static long lastExpireCheck = 0L;

    private static long lastLogCleanupCheck = 0L;

    private Logger log = null;

    private static ConfigProperties config = new ConfigProperties();

    public IpAttackDefense() {
        super();
        log = LoggerFactory.getLogger(IpAttackDefense.class);
    }

    @Override
    public void doFilter(ServletRequest arg0, ServletResponse arg1, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) arg0;
        if (!config.getBoolean("IpAttackDefense.enable", true)) {
            chain.doFilter(request, arg1);
            return;
        }
        try {
            // Count/record requests only on the original REQUEST dispatch.
            // Internal forwards (DispatcherType.FORWARD, e.g. DomainProxy
            // "internal" mode) must not double-count the same client request.
            if (request.getDispatcherType() == DispatcherType.REQUEST) {
                // Skip recording for IPs that are already blocked: auto-banned IPs
                // (see ban()) and IPs rejected by IpFilter DENY rules (manual or ip_ban)
                // must not accumulate more ip_request_log rows.
                String ip = request.getRemoteAddr();
                boolean blocked = isIpBanned(ip) || IpFilter.isIpDenied(request.getServerName(),
                        request.getLocalPort() + "", request.getRequestURI(), ip);
                if (!blocked) {
                    record(request);
                    checkDefense(request);
                }
            }
        } catch (Exception e) {
            // Defense must never break request serving.
            log.warn("IpAttackDefense skipped: {}", e.getMessage());
        }
        chain.doFilter(request, arg1);
    }

    // ------------------------------------------------------------------
    // Request recording (batched DB insert via CacheArray)
    // ------------------------------------------------------------------

    private void record(HttpServletRequest request) throws Exception {
        if (requestLogQueue == null) {
            initQueue();
        }
        Map<String, Object> item = new HashMap<String, Object>();
        Timestamp currentTime = new Clock().getSqlTimestamp();
        item.put("id", CommonTools.generateId(16));
        item.put("domain", request.getServerName() == null ? "" : request.getServerName().toLowerCase());
        item.put("port", request.getLocalPort() + "");
        item.put("uri", limit(request.getRequestURI(), 500));
        item.put("method", request.getMethod());
        item.put("remote_addr", limit(request.getRemoteAddr(), 200));
        item.put("user_agent", limit(request.getHeader("User-Agent"), 500));
        item.put("request_time", currentTime);
        item.put("created_at", currentTime);
        item.put("updated_at", currentTime);
        item.put("deleted_at", null);
        item.put("deleted", false);
        requestLogQueue.add(item);
    }

    private static synchronized void initQueue() {
        if (requestLogQueue != null) {
            return;
        }
        requestLogQueue = new CacheArray();
        long logTimer = config.getLong("IpAttackDefense.logTimer", 1000L);
        requestLogQueue.filter(new CacheArrayFilter(0L, logTimer) {
            @Override
            public void executeBatch(Integer index, List batch) {
                insertBatch(batch);
                expireBans();
                cleanupRequestLogs();
            }
        });
    }

    private static void insertBatch(List batch) {
        if (batch == null || batch.isEmpty()) {
            return;
        }
        CacheDriverExecutor cde = null;
        try {
            String sql = "INSERT INTO ip_request_log (id,domain,port,uri,method,remote_addr,user_agent,request_time,created_at,updated_at,deleted_at,deleted) VALUES (:id,:domain,:port,:uri,:method,:remote_addr,:user_agent,:request_time,:created_at,:updated_at,:deleted_at,:deleted)";
            Connection conn = Db.getConnection();
            cde = new CacheDriverExecutor(conn);
            int rows = cde.executeBatch(sql, batch);
            LoggerFactory.getLogger(IpAttackDefense.class).debug("ip_request_log batch inserted: {}", rows);
        } catch (Exception e) {
            LoggerFactory.getLogger(IpAttackDefense.class).warn(e.getMessage(), e);
        } finally {
            Db.closeQuietly(cde);
        }
    }

    // ------------------------------------------------------------------
    // Defense check (sliding-window counter + auto ban)
    // ------------------------------------------------------------------

    private void checkDefense(HttpServletRequest request) throws Exception {
        List<Map<String, Object>> rules = getDefenseRules();
        if (rules == null || rules.isEmpty()) {
            return;
        }
        String ip = request.getRemoteAddr();
        String domain = request.getServerName() == null ? "" : request.getServerName().toLowerCase();
        String port = request.getLocalPort() + "";
        String uri = request.getRequestURI();
        for (Map<String, Object> rule : rules) {
            try {
                if (!matchRule(rule, domain, port, uri)) {
                    continue;
                }
                String ruleId = (String) rule.get("id");
                int windowSeconds = ((Number) rule.get("window_seconds")).intValue();
                int threshold = ((Number) rule.get("threshold")).intValue();
                if (windowSeconds <= 0 || threshold <= 0) {
                    continue;
                }
                String countKey = String.format("IpAttackDefense.count.%s.%s", ruleId, ip);
                Integer count = (Integer) Cache.get(countKey);
                count = count == null ? 1 : count + 1;
                if (count >= threshold) {
                    ban(rule, ip, domain, port);
                    // Reset the counter so we do not re-trigger the ban on every request.
                    Cache.set(countKey, null, windowSeconds);
                } else {
                    Cache.set(countKey, count, windowSeconds);
                }
            } catch (Exception e) {
                log.warn("Defense rule check failed: {}", e.getMessage());
            }
        }
    }

    private boolean matchRule(Map<String, Object> rule, String domain, String port, String uri) {
        String ruleDomain = String.valueOf(rule.get("domain"));
        String rulePort = String.valueOf(rule.get("port"));
        // Server-wide fallback key (0:0), same convention as IpFilter/SecurityFilter:
        // the rule applies to every domain:port; only the URI pattern (if any) is checked.
        boolean isServerWide = "0".equalsIgnoreCase(ruleDomain) && "0".equalsIgnoreCase(rulePort);
        if (!isServerWide && (!domain.equalsIgnoreCase(ruleDomain) || !port.equalsIgnoreCase(rulePort))) {
            return false;
        }
        // A rule with a uri_pattern also matches the request URI (regex find);
        // a blank uri_pattern matches every URI on the domain:port.
        String uriPattern = (String) rule.get("uri_pattern");
        if (!CommonTools.isBlank(uriPattern) && !Pattern.compile(uriPattern).matcher(uri).find()) {
            return false;
        }
        return true;
    }

    private void ban(Map<String, Object> rule, String ip, String domain, String port) {
        try {
            String ruleId = (String) rule.get("id");
            int banSeconds = ((Number) rule.get("ban_seconds")).intValue();
            if (banSeconds <= 0) {
                return;
            }
            String banKey = String.format("IpAttackDefense.ban.%s.%s", ruleId, ip);
            if (Cache.get(banKey) != null) {
                return; // already banned by this rule
            }
            // One active ban per IP: a banned IP must never be re-banned by another rule.
            // Without this, one request matching several rules (e.g. a server-wide rule plus
            // a per-domain rule) would write one ip_ban row per rule (different domain:port
            // combinations are not caught by createDomainIpRule's duplicate check).
            if (isIpBanned(ip)) {
                return;
            }
            String ruleDomain = (String) rule.get("domain");
            String rulePort = (String) rule.get("port");
            if (CommonTools.isBlank(ruleDomain) || CommonTools.isBlank(rulePort)) {
                // Blank domain/port: ban at the server-wide fallback key.
                ruleDomain = "0";
                rulePort = "0";
            }
            Map<String, Object> resMap = IpFilter.createDomainIpRule(ruleDomain, rulePort, IpFilter.RULE_DENY, ip);
            if ("1".equals(String.valueOf(resMap.get("code")))) {
                String banId = insertIpBan(ruleId, ruleDomain, rulePort, ip, banSeconds);
                if (banId != null) {
                    updateDomainIpRuleIpBanId(ruleDomain, rulePort, ip, banId);
                }
                // Mark the IP as banned so no further ip_request_log rows are written;
                // the TTL equals ban_seconds and expires together with the ban.
                Cache.set(bannedIpKey(ip), "1", banSeconds);
                Logger.systemMark(IpAttackDefense.class, "IP {} banned by rule {} for {}s ({})", ip, ruleId, banSeconds,
                        String.format("%s:%s", ruleDomain, rulePort));
            }
            Cache.set(banKey, "1", banSeconds);
        } catch (Exception e) {
            log.warn("Ban failed for IP {}: {}", ip, e.getMessage());
        }
    }

    private static String insertIpBan(String ruleId, String domain, String port, String ip, int banSeconds)
            throws Exception {
        CacheDriverExecutor cde = null;
        try {
            String checkSql = "SELECT id FROM ip_ban WHERE ip_defense_rule_id = :ip_defense_rule_id AND domain = :domain AND port = :port AND ip = :ip AND deleted = :deleted";
            Connection conn = Db.getConnection();
            cde = new CacheDriverExecutor(conn);
            Map<String, Object> checkParams = new HashMap<String, Object>();
            checkParams.put("ip_defense_rule_id", ruleId);
            checkParams.put("domain", domain);
            checkParams.put("port", port);
            checkParams.put("ip", ip);
            checkParams.put("deleted", false);
            Map<String, Object> exists = cde.first(checkSql, checkParams);
            if (exists != null) {
                return null; // already banned by this rule, no new ban row
            }
            String banId = CommonTools.generateId(16);
            Timestamp now = new Clock().getSqlTimestamp();
            Timestamp expire = new Timestamp(System.currentTimeMillis() + (banSeconds * 1000L));
            String sql = "INSERT INTO ip_ban (id,ip_defense_rule_id,domain,port,ip,ban_time,expire_time,created_at,updated_at,deleted_at,deleted) VALUES (:id,:ip_defense_rule_id,:domain,:port,:ip,:ban_time,:expire_time,:created_at,:updated_at,:deleted_at,:deleted)";
            Map<String, Object> params = new HashMap<String, Object>();
            params.put("id", banId);
            params.put("ip_defense_rule_id", ruleId);
            params.put("domain", domain);
            params.put("port", port);
            params.put("ip", ip);
            params.put("ban_time", now);
            params.put("expire_time", expire);
            params.put("created_at", now);
            params.put("updated_at", now);
            params.put("deleted_at", null);
            params.put("deleted", false);
            cde.execute(sql, params);
            return banId;
        } finally {
            Db.closeQuietly(cde);
        }
    }

    private static void updateDomainIpRuleIpBanId(String domain, String port, String ip, String banId) {
        CacheDriverExecutor cde = null;
        try {
            String sql = "UPDATE domain_ip_rule SET ip_ban_id = :ip_ban_id,updated_at = :updated_at WHERE domain = :domain AND port = :port AND rule_type = :rule_type AND ip_pattern = :ip_pattern AND deleted = :deleted";
            Connection conn = Db.getConnection();
            cde = new CacheDriverExecutor(conn);
            Map<String, Object> params = new HashMap<String, Object>();
            params.put("ip_ban_id", banId);
            params.put("updated_at", new Clock().getSqlTimestamp());
            params.put("domain", domain.toLowerCase());
            params.put("port", port.toLowerCase());
            params.put("rule_type", IpFilter.RULE_DENY);
            params.put("ip_pattern", ip);
            params.put("deleted", false);
            cde.execute(sql, params);
        } catch (Exception e) {
            LoggerFactory.getLogger(IpAttackDefense.class).warn(e.getMessage(), e);
        } finally {
            Db.closeQuietly(cde);
        }
    }

    private static void expireBans() {
        long now = System.currentTimeMillis();
        if (now - lastExpireCheck < EXPIRE_CHECK_MIN_INTERVAL_MS) {
            return;
        }
        lastExpireCheck = now;
        CacheDriverExecutor cde = null;
        try {
            String sql = "SELECT * FROM ip_ban WHERE deleted = :deleted AND expire_time < :now";
            Connection conn = Db.getConnection();
            cde = new CacheDriverExecutor(conn);
            Map<String, Object> params = new HashMap<String, Object>();
            params.put("deleted", false);
            params.put("now", new Timestamp(now));
            List<Map<String, Object>> list = cde.find(sql, params);
            for (Map<String, Object> item : list) {
                String id = (String) item.get("id");
                String domain = (String) item.get("domain");
                String port = (String) item.get("port");
                String ip = (String) item.get("ip");
                try {
                    IpFilter.removeDomainIpRuleByBan(domain, port, ip, id);
                    String delSql = "UPDATE ip_ban SET deleted = :deleted, deleted_at = :deleted_at WHERE id = :id";
                    Map<String, Object> delParams = new HashMap<String, Object>();
                    delParams.put("id", id);
                    delParams.put("deleted", true);
                    delParams.put("deleted_at", new Clock().getSqlTimestamp());
                    cde.execute(delSql, delParams);
                    Logger.systemMark(IpAttackDefense.class, "IP {} ban expired and removed ({}).", ip,
                            String.format("%s:%s", domain, port));
                } catch (Exception e) {
                    LoggerFactory.getLogger(IpAttackDefense.class).warn("Failed to expire ban {}: {}", id,
                            e.getMessage());
                }
            }
        } catch (Exception e) {
            LoggerFactory.getLogger(IpAttackDefense.class).warn(e.getMessage(), e);
        } finally {
            Db.closeQuietly(cde);
        }
    }

    // ------------------------------------------------------------------
    // ip_request_log retention cleanup
    // ------------------------------------------------------------------

    /**
     * Runs periodically (hourly) on the batch-flush thread:
     * <ol>
     * <li>soft-deletes {@code ip_request_log} rows older than
     *     {@code IpAttackDefense.logRetention} (default 7d; 0 disables);</li>
     * <li>physically deletes rows that have been soft-deleted for longer than
     *     {@code IpAttackDefense.logPhysicalDelete} (default 30d; 0 disables).</li>
     * </ol>
     * Both values follow the project time format ({@code ms|s|m|h|d|w}).
     */
    private static void cleanupRequestLogs() {
        long now = System.currentTimeMillis();
        if (now - lastLogCleanupCheck < LOG_CLEANUP_MIN_INTERVAL_MS) {
            return;
        }
        lastLogCleanupCheck = now;
        CacheDriverExecutor cde = null;
        try {
            Connection conn = Db.getConnection();
            cde = new CacheDriverExecutor(conn);
            Timestamp nowTs = new Clock().getSqlTimestamp();

            long retentionMs = config.getMilliSeconds("IpAttackDefense.logRetention", 604800000L);
            if (retentionMs > 0) {
                Timestamp cutoff = new Timestamp(System.currentTimeMillis() - retentionMs);
                Map<String, Object> softParams = new HashMap<String, Object>();
                softParams.put("deleted", true);
                softParams.put("deleted_at", nowTs);
                softParams.put("cutoff", cutoff);
                softParams.put("not_deleted", false);
                int softRows = cde.execute(
                        "UPDATE ip_request_log SET deleted = :deleted, deleted_at = :deleted_at WHERE request_time < :cutoff AND deleted = :not_deleted",
                        softParams);
                if (softRows > 0) {
                    LoggerFactory.getLogger(IpAttackDefense.class).debug(
                            "ip_request_log soft-deleted {} record(s) older than {} ms.", softRows, retentionMs);
                }
            }

            long physicalMs = config.getMilliSeconds("IpAttackDefense.logPhysicalDelete", 2592000000L);
            if (physicalMs > 0) {
                Timestamp cutoff = new Timestamp(System.currentTimeMillis() - physicalMs);
                Map<String, Object> physicalParams = new HashMap<String, Object>();
                physicalParams.put("deleted", true);
                physicalParams.put("cutoff", cutoff);
                int physicalRows = cde.execute(
                        "DELETE FROM ip_request_log WHERE deleted = :deleted AND deleted_at < :cutoff", physicalParams);
                if (physicalRows > 0) {
                    LoggerFactory.getLogger(IpAttackDefense.class).debug(
                            "ip_request_log physically deleted {} record(s) soft-deleted more than {} ms ago.",
                            physicalRows, physicalMs);
                }
            }
        } catch (Exception e) {
            LoggerFactory.getLogger(IpAttackDefense.class).warn("ip_request_log cleanup failed: {}", e.getMessage());
        } finally {
            Db.closeQuietly(cde);
        }
    }

    private static List<Map<String, Object>> getDefenseRules() throws Exception {
        Integer cacheSeconds = config.getInteger("IpAttackDefense.ruleCacheSeconds", 60);
        Object cached = Cache.get("IpAttackDefense.rules");
        if (cached != null) {
            return (List<Map<String, Object>>) cached;
        }
        CacheDriverExecutor cde = null;
        try {
            String sql = "SELECT * FROM ip_defense_rule WHERE deleted = :deleted AND enabled = :enabled ORDER BY created_at ASC";
            Connection conn = Db.getConnection();
            cde = new CacheDriverExecutor(conn);
            Map<String, Object> params = new HashMap<String, Object>();
            params.put("deleted", false);
            params.put("enabled", true);
            List<Map<String, Object>> list = cde.find(sql, params);
            Cache.set("IpAttackDefense.rules", list, cacheSeconds);
            return list;
        } finally {
            Db.closeQuietly(cde);
        }
    }

    private static void invalidateRulesCache() {
        try {
            Integer cacheSeconds = config.getInteger("IpAttackDefense.ruleCacheSeconds", 60);
            Cache.set("IpAttackDefense.rules", null, cacheSeconds);
        } catch (Exception e) {
            LoggerFactory.getLogger(IpAttackDefense.class).warn(e.getMessage(), e);
        }
    }

    private static String limit(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() > maxLength ? value.substring(0, maxLength) : value;
    }

    private static String bannedIpKey(String ip) {
        return BANNED_IP_CACHE_PREFIX + ip;
    }

    private static boolean isIpBanned(String ip) {
        return Cache.get(bannedIpKey(ip)) != null;
    }

    // ------------------------------------------------------------------
    // Management API (called from the JS console via TomcatEmbed.js)
    // ------------------------------------------------------------------

    public static Map<String, Object> createDefenseRule(String ruleName, String domain,
            String port, String uriPattern, Integer windowSeconds, Integer threshold, Integer banSeconds,
            Boolean enabled) {
        Map<String, Object> resMap = new HashMap<String, Object>();
        if (CommonTools.isBlank(ruleName)) {
            resMap.put("code", 0);
            resMap.put("msg", "The param 'ruleName' is required.");
            return resMap;
        }
        if (windowSeconds == null || windowSeconds <= 0) {
            resMap.put("code", 0);
            resMap.put("msg", "The param 'windowSeconds' must be greater than 0.");
            return resMap;
        }
        if (threshold == null || threshold <= 0) {
            resMap.put("code", 0);
            resMap.put("msg", "The param 'threshold' must be greater than 0.");
            return resMap;
        }
        if (banSeconds == null || banSeconds <= 0) {
            resMap.put("code", 0);
            resMap.put("msg", "The param 'banSeconds' must be greater than 0.");
            return resMap;
        }

        String sql = "INSERT INTO ip_defense_rule (id,rule_name,domain,port,uri_pattern,window_seconds,threshold,ban_seconds,enabled,created_at,updated_at,deleted_at,deleted) VALUES (:id,:rule_name,:domain,:port,:uri_pattern,:window_seconds,:threshold,:ban_seconds,:enabled,:created_at,:updated_at,:deleted_at,:deleted)";
        CacheDriverExecutor cde = null;
        try {
            Timestamp currentTime = new Clock().getSqlTimestamp();
            Connection conn = Db.getConnection();
            Map<String, Object> params = new HashMap<String, Object>();
            params.put("id", CommonTools.generateId(16));
            params.put("rule_name", ruleName);
            params.put("domain", CommonTools.isBlank(domain) ? "" : domain.toLowerCase());
            params.put("port", CommonTools.isBlank(port) ? "" : port);
            params.put("uri_pattern", CommonTools.isBlank(uriPattern) ? "" : uriPattern);
            params.put("window_seconds", windowSeconds);
            params.put("threshold", threshold);
            params.put("ban_seconds", banSeconds);
            params.put("enabled", enabled == null ? true : enabled);
            params.put("created_at", currentTime);
            params.put("updated_at", currentTime);
            params.put("deleted_at", null);
            params.put("deleted", false);
            cde = new CacheDriverExecutor(conn);
            cde.execute(sql, params);
            invalidateRulesCache();
            resMap.put("code", 1);
            resMap.put("msg", String.format("DefenseRule %s created successfully.", ruleName));
            return resMap;
        } catch (Exception e) {
            LoggerFactory.getLogger(IpAttackDefense.class).warn(e.getMessage(), e);
            resMap.put("code", -1);
            resMap.put("msg", "Failed to create DefenseRule.");
            return resMap;
        } finally {
            Db.closeQuietly(cde);
        }
    }

    public static Map<String, Object> updateDefenseRule(String ruleId, String ruleName,
            String domain, String port, String uriPattern, Integer windowSeconds, Integer threshold, Integer banSeconds,
            Boolean enabled) {
        Map<String, Object> resMap = new HashMap<String, Object>();
        if (CommonTools.isBlank(ruleId)) {
            resMap.put("code", 0);
            resMap.put("msg", "The param 'ruleId' is required.");
            return resMap;
        }
        if (windowSeconds == null || windowSeconds <= 0) {
            resMap.put("code", 0);
            resMap.put("msg", "The param 'windowSeconds' must be greater than 0.");
            return resMap;
        }
        if (threshold == null || threshold <= 0) {
            resMap.put("code", 0);
            resMap.put("msg", "The param 'threshold' must be greater than 0.");
            return resMap;
        }
        if (banSeconds == null || banSeconds <= 0) {
            resMap.put("code", 0);
            resMap.put("msg", "The param 'banSeconds' must be greater than 0.");
            return resMap;
        }

        String sql = "UPDATE ip_defense_rule SET rule_name = :rule_name,domain = :domain,port = :port,uri_pattern = :uri_pattern,window_seconds = :window_seconds,threshold = :threshold,ban_seconds = :ban_seconds,enabled = :enabled,updated_at = :updated_at WHERE id = :id";
        CacheDriverExecutor cde = null;
        try {
            Connection conn = Db.getConnection();
            Map<String, Object> params = new HashMap<String, Object>();
            params.put("id", ruleId);
            params.put("rule_name", ruleName);
            params.put("domain", CommonTools.isBlank(domain) ? "" : domain.toLowerCase());
            params.put("port", CommonTools.isBlank(port) ? "" : port);
            params.put("uri_pattern", CommonTools.isBlank(uriPattern) ? "" : uriPattern);
            params.put("window_seconds", windowSeconds);
            params.put("threshold", threshold);
            params.put("ban_seconds", banSeconds);
            params.put("enabled", enabled == null ? true : enabled);
            params.put("updated_at", new Clock().getSqlTimestamp());
            cde = new CacheDriverExecutor(conn);
            int rows = cde.execute(sql, params);
            if (rows == 0) {
                resMap.put("code", 0);
                resMap.put("msg", String.format("The DefenseRule %s does not exist.", ruleId));
                return resMap;
            }
            invalidateRulesCache();
            resMap.put("code", 1);
            resMap.put("msg", String.format("DefenseRule %s updated successfully.", ruleId));
            return resMap;
        } catch (Exception e) {
            LoggerFactory.getLogger(IpAttackDefense.class).warn(e.getMessage(), e);
            resMap.put("code", -1);
            resMap.put("msg", "Failed to update DefenseRule.");
            return resMap;
        } finally {
            Db.closeQuietly(cde);
        }
    }

    public static Map<String, Object> removeDefenseRule(String ruleId) {
        Map<String, Object> resMap = new HashMap<String, Object>();
        if (CommonTools.isBlank(ruleId)) {
            resMap.put("code", 0);
            resMap.put("msg", "The param 'ruleId' is required.");
            return resMap;
        }

        String sql = "UPDATE ip_defense_rule SET deleted = :deleted,deleted_at = :deleted_at WHERE id = :id";
        CacheDriverExecutor cde = null;
        try {
            Connection conn = Db.getConnection();
            Map<String, Object> params = new HashMap<String, Object>();
            params.put("id", ruleId);
            params.put("deleted", true);
            params.put("deleted_at", new Clock().getSqlTimestamp());
            cde = new CacheDriverExecutor(conn);
            int rows = cde.execute(sql, params);
            if (rows == 0) {
                resMap.put("code", 0);
                resMap.put("msg", String.format("The DefenseRule %s does not exist.", ruleId));
                return resMap;
            }
            invalidateRulesCache();
            resMap.put("code", 1);
            resMap.put("msg", String.format("DefenseRule %s removed successfully.", ruleId));
            return resMap;
        } catch (Exception e) {
            LoggerFactory.getLogger(IpAttackDefense.class).warn(e.getMessage(), e);
            resMap.put("code", -1);
            resMap.put("msg", "Failed to remove DefenseRule.");
            return resMap;
        } finally {
            Db.closeQuietly(cde);
        }
    }

    public static Map<String, Object> queryDefenseRules() {
        Map<String, Object> resMap = new HashMap<String, Object>();
        String msg = "";
        try {
            CacheDriverExecutor cde = null;
            Connection conn = Db.getConnection();
            cde = new CacheDriverExecutor(conn);
            Map<String, Object> params = new HashMap<String, Object>();
            params.put("deleted", false);
            List<Map<String, Object>> list = cde
                    .find("SELECT * FROM ip_defense_rule WHERE deleted = :deleted ORDER BY created_at ASC", params);
            if (list == null || list.isEmpty()) {
                resMap.put("code", 0);
                resMap.put("msg", "No ip_defense_rule records found.");
                return resMap;
            }
            int size = list.size();
            for (int i = 0; i < size; i++) {
                Map item = list.get(i);
                msg += String.format("%s) %s %s %s:%s w=%s t=%s ban=%ss enabled=%s", i + 1, item.get("id"),
                        item.get("rule_name"), item.get("domain"), item.get("port"), item.get("window_seconds"),
                        item.get("threshold"), item.get("ban_seconds"),
                        item.get("enabled")) + System.lineSeparator();
            }
            resMap.put("code", 1);
            resMap.put("msg", msg);
            return resMap;
        } catch (Exception e) {
            LoggerFactory.getLogger(IpAttackDefense.class).warn(e.getMessage(), e);
            resMap.put("code", -1);
            resMap.put("msg", "Failed to query DefenseRules.");
            return resMap;
        }
    }

    public static Map<String, Object> queryIpBans() {
        Map<String, Object> resMap = new HashMap<String, Object>();
        String msg = "";
        try {
            CacheDriverExecutor cde = null;
            Connection conn = Db.getConnection();
            cde = new CacheDriverExecutor(conn);
            Map<String, Object> params = new HashMap<String, Object>();
            params.put("deleted", false);
            List<Map<String, Object>> list = cde
                    .find("SELECT * FROM ip_ban WHERE deleted = :deleted ORDER BY ban_time DESC", params);
            if (list == null || list.isEmpty()) {
                resMap.put("code", 0);
                resMap.put("msg", "No ip_ban records found.");
                return resMap;
            }
            int size = list.size();
            for (int i = 0; i < size; i++) {
                Map item = list.get(i);
                msg += String.format("%s) %s:%s IP=%s rule=%s expire=%s", i + 1, item.get("domain"), item.get("port"),
                        item.get("ip"), item.get("ip_defense_rule_id"), item.get("expire_time")) + System.lineSeparator();
            }
            resMap.put("code", 1);
            resMap.put("msg", msg);
            return resMap;
        } catch (Exception e) {
            LoggerFactory.getLogger(IpAttackDefense.class).warn(e.getMessage(), e);
            resMap.put("code", -1);
            resMap.put("msg", "Failed to query IpBans.");
            return resMap;
        }
    }

    public static Map<String, Object> removeIpBan(String domain, String port, String ip) {
        Map<String, Object> resMap = new HashMap<String, Object>();
        if (CommonTools.isBlank(domain) || CommonTools.isBlank(port) || CommonTools.isBlank(ip)) {
            resMap.put("code", 0);
            resMap.put("msg", "The params 'domain', 'port' and 'ip' are required.");
            return resMap;
        }
        CacheDriverExecutor cde = null;
        try {
            Connection conn = Db.getConnection();
            cde = new CacheDriverExecutor(conn);
            // Collect the matching ban ids first, then remove each auto-written
            // DENY rule by its ip_ban_id (manual rules are never touched).
            String qSql = "SELECT id FROM ip_ban WHERE domain = :domain AND port = :port AND ip = :ip AND deleted = :deleted";
            Map<String, Object> qParams = new HashMap<String, Object>();
            qParams.put("domain", domain.toLowerCase());
            qParams.put("port", port.toLowerCase());
            qParams.put("ip", ip);
            qParams.put("deleted", false);
            List<Map<String, Object>> bans = cde.find(qSql, qParams);
            for (Map<String, Object> ban : bans) {
                IpFilter.removeDomainIpRuleByBan(domain, port, ip, String.valueOf(ban.get("id")));
            }
            String sql = "UPDATE ip_ban SET deleted = :deleted,deleted_at = :deleted_at WHERE domain = :domain AND port = :port AND ip = :ip AND deleted = :deleted_flag";
            Map<String, Object> params = new HashMap<String, Object>();
            params.put("domain", domain.toLowerCase());
            params.put("port", port.toLowerCase());
            params.put("ip", ip);
            params.put("deleted", true);
            params.put("deleted_flag", false);
            cde.execute(sql, params);
            // Unmark the IP so its requests are recorded again once the ban is lifted.
            Cache.remove(bannedIpKey(ip));
            resMap.put("code", 1);
            resMap.put("msg", String.format("IpBan %s:%s %s removed successfully.", domain, port, ip));
            return resMap;
        } catch (Exception e) {
            LoggerFactory.getLogger(IpAttackDefense.class).warn(e.getMessage(), e);
            resMap.put("code", -1);
            resMap.put("msg", "Failed to remove IpBan.");
            return resMap;
        } finally {
            Db.closeQuietly(cde);
        }
    }

    public static Map<String, Object> queryIpRequestLogs() {
        Map<String, Object> resMap = new HashMap<String, Object>();
        String msg = "";
        try {
            CacheDriverExecutor cde = null;
            Connection conn = Db.getConnection();
            cde = new CacheDriverExecutor(conn);
            Map<String, Object> params = new HashMap<String, Object>();
            params.put("deleted", false);
            List<Map<String, Object>> list = cde.find(
                    "SELECT * FROM ip_request_log WHERE deleted = :deleted ORDER BY request_time DESC LIMIT 100",
                    params);
            if (list == null || list.isEmpty()) {
                resMap.put("code", 0);
                resMap.put("msg", "No ip_request_log records found.");
                return resMap;
            }
            int size = list.size();
            for (int i = 0; i < size; i++) {
                Map item = list.get(i);
                msg += String.format("%s) %s %s:%s %s %s %s", i + 1, item.get("request_time"), item.get("domain"),
                        item.get("port"), item.get("method"), item.get("uri"), item.get("remote_addr"))
                        + System.lineSeparator();
            }
            resMap.put("code", 1);
            resMap.put("msg", msg);
            return resMap;
        } catch (Exception e) {
            LoggerFactory.getLogger(IpAttackDefense.class).warn(e.getMessage(), e);
            resMap.put("code", -1);
            resMap.put("msg", "Failed to query IpRequestLogs.");
            return resMap;
        }
    }

    public static Map<String, Object> clearIpRequestLogs() {
        Map<String, Object> resMap = new HashMap<String, Object>();
        CacheDriverExecutor cde = null;
        try {
            Connection conn = Db.getConnection();
            cde = new CacheDriverExecutor(conn);
            int rows = cde.execute("DELETE FROM ip_request_log");
            resMap.put("code", 1);
            resMap.put("msg", String.format("%d ip_request_log record(s) cleared.", rows));
            return resMap;
        } catch (Exception e) {
            LoggerFactory.getLogger(IpAttackDefense.class).warn(e.getMessage(), e);
            resMap.put("code", -1);
            resMap.put("msg", "Failed to clear IpRequestLogs.");
            return resMap;
        } finally {
            Db.closeQuietly(cde);
        }
    }

    // Called once during server startup (TomcatServer config load); no concurrent writes.
    public static void setParams(final Map params) {
        config.putAllAndReturnSlef(params);
    }

}
