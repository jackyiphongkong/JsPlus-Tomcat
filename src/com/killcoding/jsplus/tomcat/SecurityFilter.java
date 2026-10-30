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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IllegalFormatException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.killcoding.log.LoggerFactory;
import com.killcoding.tool.Base64Tools;
import com.killcoding.tool.CipherTools;
import com.killcoding.tool.CommonTools;
import com.killcoding.tool.ConfigProperties;
import com.killcoding.log.Logger;
import com.killcoding.cache.Cache;
import com.killcoding.datasource.CacheDriverExecutor;
import com.killcoding.datasource.Clock;
import com.killcoding.file.BaseFile;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Basic-Auth gateway filter backed by {@code domain_auth} /
 * {@code domain_auth_user}.
 * <p>
 * Every request is first recorded through {@link ServerLog}. When auth is
 * enabled for the domain:port (or the server-wide {@code 0:0} fallback), the
 * {@code Authorization: Basic} header is validated against the configured
 * users; failed attempts are counted and the account is locked once the
 * configured attempt limit is reached (see {@link #setAuthLockPolicy}). Unlock
 * happens after the lock window expires or via {@link #unlockAuthUser}.
 */
public class SecurityFilter implements Filter {

    public final static String FILTER_NAME = "TomcatServerSecurityFilter";

    private static final Logger LOG = LoggerFactory.getLogger(SecurityFilter.class);

    private static ConfigProperties config = new ConfigProperties();

    /** B4 fix: per-account locks so racing threads on the same cache miss can
     *  never double-increment the failed-attempt counter (which used to lock
     *  accounts early under concurrency). */
    private static final ConcurrentHashMap<String, Object> AUTH_CHECK_LOCKS = new ConcurrentHashMap<>();

    /** Gets the per-account lock object for a cache-key (one lock per account). */
    private static Object authCheckLock(String key) {
        return AUTH_CHECK_LOCKS.computeIfAbsent(key, k -> new Object());
    }

    /** Servlet-filter constructor: state is injected later via {@link #setParams(Map)}. */
    public SecurityFilter() {
        super();
    }

    /**
     * Filter entry point: writes the web-log line, then enforces Basic auth when
     * enabled — 401 + {@code WWW-Authenticate} on missing/invalid credentials.
     */
    @Override
    public void doFilter(ServletRequest arg0, ServletResponse arg1, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) arg0;
        HttpServletResponse response = (HttpServletResponse) arg1;
        String domain = request.getServerName();
        String port = request.getLocalPort() + "";
        try {
            ServerLog.writeWebLog(domain, port, request.getRequestURI(), request.getMethod(),
                    System.currentTimeMillis(),
                    request.getQueryString(), ServerLog.getHeaders(request), null,request.getRemoteAddr());

            Boolean authEnable = isAuthEnable(domain, port);

            if(!authEnable) authEnable = isAuthEnable("0", "0");

            if (authEnable) {
                try {
                    boolean allowed = false;
                    String authorization = request.getHeader("Authorization");
                    if (!CommonTools.isBlank(authorization)) {
                        // B5 fix: the Basic scheme is case-insensitive per
                        // RFC 7235 (old code only matched "Basic "); a missing
                        // ":" in the decoded pair used to throw AIOOBE at up[1].
                        String[] authParts = authorization.trim().split("\\s+", 2);
                        if (authParts.length == 2 && authParts[0].equalsIgnoreCase("Basic")) {
                            String userAndPass = new String(Base64Tools.decode(authParts[1].trim()));
                            String[] up = userAndPass.split(":", 2);
                            if (up.length == 2) {
                                String user = up[0];
                                String pass = up[1];
                                allowed = checkUserFromCache(request, domain, port, user, pass);
                                if (!allowed) allowed = checkUserFromCache(request, "0", "0", user, pass);
                            }
                        }
                    }
                    if (allowed) {
                        chain.doFilter(request, response);
                    } else {
                        response.addHeader("WWW-Authenticate", "BASIC realm=\"Username and password\"");
                        response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
                    }
                } catch (Exception e) {
                    LOG.error(e.getMessage(), e);
                    response.addHeader("WWW-Authenticate", "BASIC realm=\"Username and password\"");
                    response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
                }
            } else {
                chain.doFilter(request, response);
            }
        } catch (Exception e) {
            LOG.error(e.getMessage(), e);
            ServerLog.writeWebLog(domain, port, request.getRequestURI(), "error", System.currentTimeMillis(),
                    request.getQueryString(), ServerLog.getHeaders(request), null,request.getRemoteAddr());
            safeSendError(response, 500, "Server Error.");
        }
    }

    /** Validates credentials against the cached user (lockout-aware). */
    private boolean checkUserFromCache(HttpServletRequest request ,String domain, String port, String username, String pass) {
        if(CommonTools.isBlank(domain)){
            return false;
        }
        if(CommonTools.isBlank(port)){
            return false;
        }    
        if(CommonTools.isBlank(username)){
            return false;
        }    
        if(CommonTools.isBlank(pass)){
            return false;
        }           
        String cacheKey = String.format("%s:%s.%s",domain,port,username);
        Boolean userAuthed = (Boolean)Cache.get(cacheKey);
        if(userAuthed == null){
            // B4 fix: serialise concurrent misses for the same account (double
            // checked locking) so the failed-attempt counter stays accurate.
            synchronized (authCheckLock(cacheKey)) {
                userAuthed = (Boolean) Cache.get(cacheKey);
                if (userAuthed == null) {
                    userAuthed = checkUser(domain, port, username, pass);
                    if (userAuthed != null && userAuthed.booleanValue()) {
                        // Only successful results are cached. Failed attempts must
                        // reach checkUser() every time so the failed-attempt counter
                        // and the account lockout (failed_attempts / locked_until)
                        // stay accurate.
                        Integer cacheSeconds = config.getInteger("SecurityFilter.cacheSeconds", 60);
                        // D3 fix (Core 6.1.163): life==0 means "never expire" in
                        // Cache.set; the project convention is 0 = no cache.
                        if (cacheSeconds != null && cacheSeconds > 0) {
                            Cache.set(cacheKey,userAuthed,cacheSeconds);
                        }
                    }
                    String logType = userAuthed ? "Authorized" : "Unauthorized";
                    String paramsMsg = String.format("username=%s",username);
                    ServerLog.writeWebLog(domain, port, request.getRequestURI(), logType,
                    System.currentTimeMillis(),
                    request.getQueryString(), ServerLog.getHeaders(request),paramsMsg,request.getRemoteAddr());
                }
            }
        }
        return userAuthed;
    }

    /**
     * Core credential check with lockout: locked accounts are rejected until
     * the lock window passes; each failure increments the attempt counter and
     * locks the account at the limit.
     */
    private boolean checkUser(String domain, String port, String username, String pass) {
        if(CommonTools.isBlank(domain)){
            return false;
        }
        if(CommonTools.isBlank(port)){
            return false;
        }    
        if(CommonTools.isBlank(username)){
            return false;
        }    
        if(CommonTools.isBlank(pass)){
            return false;
        }           
        try {
            domain = domain.toLowerCase();
            port = port.toLowerCase();
            username = username.toLowerCase(); 
            Map<String,Object> domainAuth = getDomainAuth(domain,port);  
            if(domainAuth == null){
                return false;
            }         
            Map<String,Object> user = getDomainAuthUser(domain,port,username);
            if(user == null){
                return false;
            }

            // ---- account lockout (failed_attempts / locked_until) ----
            Timestamp now = new Clock().getSqlTimestamp();
            Timestamp lockedUntil = (Timestamp) user.get("locked_until");
            int failedAttempts = toInt(user.get("failed_attempts"));
            int maxAttempts = toInt(domainAuth.get("max_attempts"));
            int lockMinutes = toInt(domainAuth.get("lock_minutes"));
            if (maxAttempts <= 0) maxAttempts = 5;
            if (lockMinutes <= 0) lockMinutes = 30;

            if (lockedUntil != null) {
                if (now.before(lockedUntil)) {
                    // Locked until a future time point: reject every attempt,
                    // even with the correct password.
                    LOG.warn(String.format("Account %s@%s:%s is locked until %s.", username, domain, port, lockedUntil));
                    return false;
                }
                // Lock window has passed -> auto unlock and continue normally.
                resetAuthLockout(user);
                failedAttempts = 0;
                lockedUntil = null;
            }

            String encryptKey = (String)domainAuth.get("encrypt_key");
            String encryptPassBase64 = (String)user.get("encrypt_pass");
            byte[] encryptPassBytes = Base64Tools.decodeLines(encryptPassBase64);
            String decryptPass = new String(CipherTools.AESDecrypt(encryptKey,encryptPassBytes),BaseFile.CHARSET);
            if (decryptPass.equals(pass)) {
                // A successful login clears any previous failures / lock.
                if (failedAttempts > 0 || lockedUntil != null) {
                    resetAuthLockout(user);
                }
                return true;
            }

            // Wrong password: count it, then lock the account when the limit
            // is reached (auto-unlock after lockMinutes).
            int newFailed = failedAttempts + 1;
            if (newFailed >= maxAttempts) {
                Timestamp lockUntil = new Timestamp(now.getTime() + lockMinutes * 60000L);
                updateAuthUserLock(user, 0, lockUntil);
                LOG.warn(String.format("Account %s@%s:%s locked after %d failed attempts, auto-unlock at %s.",
                        username, domain, port, newFailed, lockUntil));
            } else {
                updateAuthUserLock(user, newFailed, null);
            }
            return false;
        } catch (Exception e) {
            LOG.error(e.getMessage(), e);
        }
        return false;
    }

    /** Safe integer parse (blank/non-numeric → 0). */
    private static int toInt(Object value) {
        if (value == null) return 0;
        if (value instanceof Number) return ((Number) value).intValue();
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (Exception e) {
            return 0;
        }
    }

    /** Clears the failed-attempt counter after a successful login. */
    private static void resetAuthLockout(Map<String, Object> user) throws Exception {
        String sql = "UPDATE domain_auth_user SET failed_attempts = 0, locked_until = NULL, updated_at = :updated_at WHERE id = :id";
        CacheDriverExecutor cde = null;
        try {
            // DB-CLOSE fix: acquire the connection inside the try so an early
            // exception cannot leak it (the old code got it before the try).
            Connection conn = Db.getConnection();
            Map<String, Object> params = new HashMap<String, Object>();
            params.put("id", user.get("id"));
            params.put("updated_at", new Clock().getSqlTimestamp());
            cde = new CacheDriverExecutor(conn);
            cde.execute(sql, params);
        } finally {
            Db.closeQuietly(cde);
        }
    }

    /** Persists the lock state (failed attempts + locked-until timestamp) for a user. */
    private static void updateAuthUserLock(Map<String, Object> user, int failedAttempts, Timestamp lockedUntil) throws Exception {
        String sql = "UPDATE domain_auth_user SET failed_attempts = :failed_attempts, locked_until = :locked_until, updated_at = :updated_at WHERE id = :id";
        CacheDriverExecutor cde = null;
        try {
            // DB-CLOSE fix: acquire the connection inside the try so an early
            // exception cannot leak it (the old code got it before the try).
            Connection conn = Db.getConnection();
            Map<String, Object> params = new HashMap<String, Object>();
            params.put("id", user.get("id"));
            params.put("failed_attempts", failedAttempts);
            params.put("locked_until", lockedUntil);
            params.put("updated_at", new Clock().getSqlTimestamp());
            cde = new CacheDriverExecutor(conn);
            cde.execute(sql, params);
        } finally {
            Db.closeQuietly(cde);
        }
    }

    /** Whether auth is enabled for the domain:port ({@code domain_auth} row, cached). */
    private synchronized boolean isAuthEnable(String domain, String port) throws Exception {
        String domainPort = String.format("%s:%s", domain, port);
        String domainPortKey = String.format("SecurityFilter.%s.authEnable", domainPort);
        Boolean authEnable = (Boolean) Cache.get(domainPortKey);

        if (authEnable == null) {
            authEnable = checkDomainAuthEnable(domain, port);
            Integer cacheSeconds = config.getInteger("SecurityFilter.cacheSeconds", 60);
            // D3 fix (Core 6.1.163): Cache.set treats life==0 as "never expire",
            // while this project uses 0 as "do not cache" (tests need immediate
            // visibility of auth changes), so only positive values are cached.
            if (cacheSeconds != null && cacheSeconds > 0) {
                Cache.set(domainPortKey, authEnable, cacheSeconds);
            }
        }
        return authEnable;
    }    

    /** Loads one user row ({@code domain_auth_user}) for domain:port + username. */
    private static Map<String, Object> getDomainAuthUser(String domain, String port,String username) throws Exception {         
        CacheDriverExecutor cde = null;
        String sql = "SELECT * FROM domain_auth_user WHERE domain_auth_id = :domain_auth_id AND username = :username AND deleted = :deleted";
        try {
            domain = domain.toLowerCase();
            port = port.toLowerCase();
            username = username.toLowerCase();
            Map<String,Object> domainAuth = getDomainAuth(domain, port);
            // C2 fix: a query against a domain:port without a domain_auth row
            // used to NPE at domainAuth.get("id") and escape as a raw exception
            // instead of a "no records" result.
            if (domainAuth == null) {
                return null;
            }
            Connection conn = Db.getConnection();
            Map<String, Object> params = new HashMap<String, Object>();
            params.put("domain_auth_id", domainAuth.get("id"));
            params.put("username", username);
            params.put("deleted", false);
            cde = new CacheDriverExecutor(conn);
            return cde.first(sql, params);
        } finally {
            Db.closeQuietly(cde);
        }
    }  

    /** Management API: single user lookup. */
    public static Map<String, Object> queryDomainAuthUser(String domain, String port,String username) throws Exception { 
        Map<String,Object> resMap = new HashMap<String,Object>();
        String msg = "";
        Map<String,Object> user = getDomainAuthUser(domain,port,username);
        if(user == null){
            resMap.put("code",0);
            resMap.put("msg","No records found.");
        }else{
            user.remove("encrypt_pass");
            msg += String.format("Id: %s",user.get("id")) + System.lineSeparator();
            msg += String.format("DomainAuthId: %s",user.get("domain_auth_id")) + System.lineSeparator();
            msg += String.format("Username: %s",user.get("username")) + System.lineSeparator();
            msg += String.format("CreatedAt: %s",user.get("created_at")) + System.lineSeparator();
            msg += String.format("UpdatedAt: %s",user.get("updated_at")) + System.lineSeparator();
            resMap.put("code",1);
            resMap.put("msg",msg);            
        }        
        return resMap;
    }
    
    /** Management API: lists all users of a domain:port. */
    public static Map<String, Object> queryDomainAuthUsers(String domain, String port) throws Exception {   
        Map<String,Object> resMap = new HashMap<String,Object>();
        String msg = "";
        List<Map<String, Object>> list = getDomainAuthUsers(domain, port);
        int size = list.size();
        if(size == 0){
            resMap.put("code",0);
            resMap.put("msg","No records found.");
            return  resMap;
        }
        for(int i = 0;i < size;i++){
            msg += String.format("%s) %s",i + 1,list.get(i).get("username")) + System.lineSeparator();
        }
        resMap.put("code",1);
        resMap.put("msg",msg);
        return resMap;
    }     
    
    /** All users of a domain:port from {@code domain_auth_user}. */
    private static List<Map<String, Object>> getDomainAuthUsers(String domain, String port) throws Exception {         
        CacheDriverExecutor cde = null;
        String sql = "SELECT * FROM domain_auth_user WHERE domain_auth_id = :domain_auth_id AND deleted = :deleted ORDER BY username ASC";
        try {
            domain = domain.toLowerCase();
            port = port.toLowerCase();
            Map<String,Object> domainAuth = getDomainAuth(domain, port);
            if (domainAuth == null) {
                return new ArrayList<Map<String, Object>>();
            }
            Connection conn = Db.getConnection();
            Map<String, Object> params = new HashMap<String, Object>();
            params.put("domain_auth_id", domainAuth.get("id"));
            params.put("deleted", false);
            cde = new CacheDriverExecutor(conn);
            return cde.find(sql, params);
        } finally {
            Db.closeQuietly(cde);
        }
    }      

    /** Management API: creates/updates a user (plaintext password is hashed on write). */
    public static Map<String, Object> updateDomainAuthUser(String domain, String port, String username,String plaintextPass) {
        Map<String,Object> resMap = new HashMap<String,Object>();
        if(CommonTools.isBlank(domain)){
            resMap.put("code",0);
            resMap.put("msg","The param 'domain' is required.");
            return  resMap;
        }
        if(CommonTools.isBlank(port)){
            resMap.put("code",0);
            resMap.put("msg","The param 'port' is required.");
            return  resMap;
        }    
        if(CommonTools.isBlank(username)){
            resMap.put("code",0);
            resMap.put("msg","The param 'username' is required.");
            return  resMap;
        }     
        if(CommonTools.isBlank(plaintextPass)){
            resMap.put("code",0);
            resMap.put("msg","The param 'plaintextPass' is required.");
            return  resMap;
        }   
        domain = domain.toLowerCase();
        port = port.toLowerCase();
        username = username.toLowerCase();   
        String sql = "UPDATE domain_auth_user SET encrypt_pass = :encrypt_pass, failed_attempts = 0, locked_until = NULL, updated_at = :updated_at WHERE id = :id";
        CacheDriverExecutor cde = null;
        Map<String,Object> domainAuth = null;
        Map<String,Object> domainAuthUser = null;
        try {
            domainAuth = getDomainAuth(domain, port);
            if (domainAuth == null) {
                resMap.put("code",0);
                resMap.put("msg",String.format("DomainAuth %s:%s does not exist.", domain, port));
                return resMap;
            }
            domainAuthUser = getDomainAuthUser(domain, port,username);
            if (domainAuthUser == null) {
                resMap.put("code",0);
                resMap.put("msg",String.format("DomainAuthUser %s:%s.%s does not exist.", domain, port,username));
                return resMap;
            }            
            String encryptKey = (String)domainAuth.get("encrypt_key");
            byte[] encryptPass = CipherTools.AESEncrypt(encryptKey,plaintextPass.getBytes());
            Timestamp currentTime = new Clock().getSqlTimestamp();
            Connection conn = Db.getConnection();
            Map<String, Object> params = new HashMap<String, Object>();
            params.put("id", domainAuthUser.get("id"));
            params.put("encrypt_pass", Base64Tools.encodeLines(encryptPass));
            params.put("updated_at", currentTime);
            cde = new CacheDriverExecutor(conn);
            cde.execute(sql, params);
            // D1 fix: Cache.set(key, null, ...) is a no-op; Cache.remove evicts
            // the cached auth result so the next login re-reads the password.
            String cacheKey = String.format("%s:%s.%s",domain,port,username);
            Cache.remove(cacheKey);
            resMap.put("code",1);
            resMap.put("msg",String.format("DomainAuthUser %s:%s.%s updated successfully.", domain, port,username));
            return resMap;
        } catch (Exception e) {
            logError(resMap, e);
            resMap.put("msg",String.format("Failed to update DomainAuthUser %s:%s.%s.", domain, port,username));
            return resMap;
        } finally {
            Db.closeQuietly(cde);
        }
    }

    /** Management API: removes a user (soft delete). */
   public static Map<String, Object> removeDomainAuthUser(String domain, String port, String username) {
    Map<String, Object> resMap = new HashMap<>();
        if(CommonTools.isBlank(domain)){
            resMap.put("code",0);
            resMap.put("msg","The param 'domain' is required.");
            return  resMap;
        }
        if(CommonTools.isBlank(port)){
            resMap.put("code",0);
            resMap.put("msg","The param 'port' is required.");
            return  resMap;
        }    
        if(CommonTools.isBlank(username)){
            resMap.put("code",0);
            resMap.put("msg","The param 'username' is required.");
            return  resMap;
        }
        username = username.toLowerCase();        
        String sql = "UPDATE domain_auth_user SET deleted = :deleted,deleted_at = :deleted_at WHERE id = :id";
        CacheDriverExecutor cde = null;
        Map<String,Object> domainAuth = null;
        Map<String,Object> domainAuthUser = null;
        try {
            domainAuth = getDomainAuth(domain, port);
            if (domainAuth == null) {
                resMap.put("code",0);
                resMap.put("msg",String.format("DomainAuth %s:%s does not exist.", domain, port));
                return resMap;
            }
            domainAuthUser = getDomainAuthUser(domain, port,username);
            if (domainAuthUser == null) {
                resMap.put("code",0);
                resMap.put("msg",String.format("DomainAuthUser %s:%s - %s does not exist.", domain, port,username));
                return resMap;
            }            
            Timestamp currentTime = new Clock().getSqlTimestamp();
            Connection conn = Db.getConnection();
            Map<String, Object> params = new HashMap<String, Object>();
            params.put("id", domainAuthUser.get("id"));
            params.put("deleted",true);
            params.put("deleted_at", currentTime);
            cde = new CacheDriverExecutor(conn);
            cde.execute(sql, params);
            // D1 fix: Cache.set(key, null, ...) is a no-op; use Cache.remove so
            // the next login attempt re-reads the DB (no stale auth result).
            String cacheKey = String.format("%s:%s.%s",domain,port,username);
            Cache.remove(cacheKey);
            resMap.put("code",1);
            resMap.put("msg",String.format("DomainAuthUser %s:%s - %s removed successfully.", domain, port,username));
            return resMap;
        } catch (Exception e) {
            logError(resMap, e);
            resMap.put("msg",String.format("Failed to remove DomainAuthUser %s:%s - %s.", domain, port,username));
            return resMap;
        } finally {
            Db.closeQuietly(cde);
        }
    }    

    // C6 fix: serialise creates so two racing calls cannot both pass the
    // duplicate check and write two domain_auth_user rows for the same login.
    /** Management API: creates a user with default lock policy (idempotent per username). */
    public synchronized static Map<String, Object> createDomainAuthUser(String domain, String port, String username,String plaintextPass) {
        Map<String, Object> resMap = new HashMap<>();
        if(CommonTools.isBlank(domain)){
            resMap.put("code",0);
            resMap.put("msg","The param 'domain' is required.");
            return  resMap;
        }
        if(CommonTools.isBlank(port)){
            resMap.put("code",0);
            resMap.put("msg","The param 'port' is required.");
            return  resMap;
        }  
        if(CommonTools.isBlank(username)){
            resMap.put("code",0);
            resMap.put("msg","The param 'username' is required.");
            return  resMap;
        } 
        if(CommonTools.isBlank(plaintextPass)){
            resMap.put("code",0);
            resMap.put("msg","The param 'plaintextPass' is required.");
            return  resMap;
        }                 
        String sql = "INSERT INTO domain_auth_user (id,domain_auth_id,username,encrypt_pass,created_at,updated_at,deleted_at,deleted) VALUES (:id,:domain_auth_id,:username,:encrypt_pass,:created_at,:updated_at,:deleted_at,:deleted)";
        CacheDriverExecutor cde = null;
        Map<String,Object> domainAuth = null;
        Map<String,Object> domainAuthUser = null;
        try {
            domain = domain.toLowerCase();
            port = port.toLowerCase();
            username = username.toLowerCase();                  
            domainAuth = getDomainAuth(domain, port);
            if (domainAuth == null) {
                resMap.put("code",0);
                resMap.put("msg",String.format("DomainAuth %s:%s does not exist.", domain, port));
                return resMap;
            }
            domainAuthUser = getDomainAuthUser(domain, port,username);
            if (domainAuthUser != null) {
                resMap.put("code",0);
                resMap.put("msg",String.format("DomainAuthUser %s:%s - %s already exists.", domain, port,username));
                return resMap;
            }            
            String encryptKey = (String)domainAuth.get("encrypt_key");
            byte[] encryptPass = CipherTools.AESEncrypt(encryptKey,plaintextPass.getBytes());
            Timestamp currentTime = new Clock().getSqlTimestamp();
            Connection conn = Db.getConnection();
            Map<String, Object> params = new HashMap<String, Object>();
            params.put("id", CommonTools.generateId(16));
            params.put("domain_auth_id", domainAuth.get("id"));
            params.put("username", username);
            params.put("encrypt_pass", Base64Tools.encodeLines(encryptPass));
            params.put("created_at", currentTime);
            params.put("updated_at", currentTime);
            params.put("deleted_at", null);
            params.put("deleted", false);
            cde = new CacheDriverExecutor(conn);
            cde.execute(sql, params);
            resMap.put("code",1);
            resMap.put("msg","DomainAuthUser created successfully.");
            return resMap;
        } catch (Exception e) {
            logError(resMap, e);
            resMap.put("msg","Failed to create DomainAuthUser.");
            return resMap;
        } finally {
            Db.closeQuietly(cde);
        }
    }

    // C6 fix: serialise creates so two racing calls cannot both write a
    // duplicate domain_auth row for the same domain:port.
    /** Management API: enables/disables Basic auth for a domain:port. */
    public synchronized static Map<String,Object> createDomainAuth(String domain, String port, Boolean authEnable) {
        Map<String,Object> resMap = new HashMap<String,Object>();

        // B22 fix: validate before touching the DB. Previously a null
        // domain/port NPE'd at .toLowerCase() (swallowed into "Failed to create
        // DomainAuth."), and a null authEnable wrote SQL NULL into
        // auth_enable — which later crashed checkDomainAuthEnable (B20).
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
        if (authEnable == null) {
            resMap.put("code", 0);
            resMap.put("msg", "The param 'authEnable' is required.");
            return resMap;
        }
        domain = domain.trim().toLowerCase();
        port = port.trim().toLowerCase();

        String sql = "INSERT INTO domain_auth (id,domain,port,auth_enable,encrypt_mode,encrypt_key,created_at,updated_at,deleted_at,deleted) VALUES (:id,:domain,:port,:auth_enable,:encrypt_mode,:encrypt_key,:created_at,:updated_at,:deleted_at,:deleted)";
        CacheDriverExecutor cde = null;
        try {
            if (getDomainAuth(domain, port) != null) {
                resMap.put("code",0);
                resMap.put("msg",String.format("The %s:%s already exists.", domain, port));
                return resMap;
            }
            String encryptMode = "AES";
            String encryptKey = CommonTools.generateId(256);
            Timestamp currentTime = new Clock().getSqlTimestamp();
            Connection conn = Db.getConnection();
            Map<String, Object> params = new HashMap<String, Object>();
            params.put("id", CommonTools.generateId(16));
            params.put("domain", domain.toLowerCase());
            params.put("port", port.toLowerCase());
            params.put("auth_enable", authEnable);
            params.put("encrypt_mode", encryptMode);
            params.put("encrypt_key", encryptKey);
            params.put("created_at", currentTime);
            params.put("updated_at", currentTime);
            params.put("deleted_at", null);
            params.put("deleted", false);
            cde = new CacheDriverExecutor(conn);
            cde.execute(sql, params);
            // D1 fix: drop any cached "auth disabled" flag for this domain:port so
            // the first request after creation already sees auth enabled.
            Cache.remove(String.format("SecurityFilter.%s:%s.authEnable", domain, port));
            resMap.put("code",1);
            resMap.put("msg","DomainAuth created successfully.");
            return resMap;
        } catch (Exception e) {
            LOG.warn(e.getMessage(), e);
            resMap.put("code", -1);
            resMap.put("msg", "Failed to create DomainAuth.");
            return resMap;
        } finally {
            Db.closeQuietly(cde);
        }
    }

    /** Management API: toggles the auth switch for a domain:port. */
    public static Map<String,Object> updateDomainAuth(String domain, String port, Boolean authEnable) {
        Map<String,Object> resMap = new HashMap<String,Object>();

        // B22 fix: see createDomainAuth — null/blank inputs must return a
        // precise validation error instead of NPE-ing or writing SQL NULL.
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
        if (authEnable == null) {
            resMap.put("code", 0);
            resMap.put("msg", "The param 'authEnable' is required.");
            return resMap;
        }
        domain = domain.trim().toLowerCase();
        port = port.trim().toLowerCase();

        CacheDriverExecutor cde = null;
        String sql = "UPDATE domain_auth SET auth_enable = :auth_enable,updated_at = :updated_at WHERE domain = :domain AND port = :port AND deleted = :deleted";

        try {
            if (getDomainAuth(domain, port) == null) {
                resMap.put("code",0);
                resMap.put("msg",String.format("The %s:%s does not exist.", domain, port));
                return resMap;
            }

            Timestamp currentTime = new Clock().getSqlTimestamp();
            Connection conn = Db.getConnection();
            cde = new CacheDriverExecutor(conn);
            Map<String, Object> params = new HashMap<String, Object>();
            params.put("domain", domain);
            params.put("port", port);
            params.put("auth_enable", authEnable);
            params.put("updated_at", currentTime);
            params.put("deleted", false);
            cde.execute(sql, params);
            // D1 fix: invalidate the cached auth-enable flag so the switch applies
            // immediately instead of waiting out SecurityFilter.cacheSeconds.
            Cache.remove(String.format("SecurityFilter.%s:%s.authEnable", domain, port));
            resMap.put("code",1);
            resMap.put("msg","DomainAuth updated successfully.");
            return resMap;
        } catch (Exception e) {
            LOG.warn(e.getMessage(), e); 
            resMap.put("code", -1);
            resMap.put("msg", "Failed to update DomainAuth.");
            return resMap;
        } finally {
            Db.closeQuietly(cde);
        }
    }

    /** Management API: disables auth for a domain:port (soft delete). */
   public static Map<String,Object> removeDomainAuth(String domain, String port) {
        Map<String,Object> resMap = new HashMap<String,Object>();
        CacheDriverExecutor cde = null;
        String sql = "UPDATE domain_auth SET deleted = :deleted,deleted_at = :deleted_at WHERE id = :id";

        try {
            Map<String, Object> domainAuth = getDomainAuth(domain, port);
            if (getDomainAuth(domain, port) == null) {
                resMap.put("code",0);
                resMap.put("msg",String.format("The %s:%s does not exist.", domain, port));
                return resMap;
            }
            List userList = getDomainAuthUsers(domain, port);
            if (userList.size() > 0) {
                resMap.put("code",0);
                resMap.put("msg",String.format("The domain %s:%s contains users so cannot be deleted.", domain, port));
                return resMap;
            }
            Timestamp currentTime = new Clock().getSqlTimestamp();
            Connection conn = Db.getConnection();
            cde = new CacheDriverExecutor(conn);
            Map<String, Object> params = new HashMap<String, Object>();
            params.put("id", domainAuth.get("id"));
            params.put("deleted_at", currentTime);
            params.put("deleted", true);
            cde.execute(sql, params);
            // D1 fix: invalidate the cached auth-enable flag so auth is lifted
            // immediately after the row is soft-deleted.
            Cache.remove(String.format("SecurityFilter.%s:%s.authEnable", domain, port));
            resMap.put("code",1);
            resMap.put("msg","DomainAuth removed successfully.");
            return resMap;
        } catch (Exception e) {
            LOG.warn(e.getMessage(), e); 
            resMap.put("code", -1);
            resMap.put("msg", "Failed to remove DomainAuth.");
            return resMap;
        } finally {
            Db.closeQuietly(cde);
        }
    }    

    /** Loads the auth config row for a domain:port (cached). */
    private static Map<String, Object> getDomainAuth(String domain, String port) throws Exception {
        CacheDriverExecutor cde = null;
        String sql = "SELECT * FROM domain_auth WHERE domain = :domain AND port = :port AND deleted = :deleted";
        try {
            Connection conn = Db.getConnection();
            Map<String, Object> params = new HashMap<String, Object>();
            // C7 fix: normalise case here so lookups are case-insensitive even
            // when the caller did not lower-case first (IpFilter already does).
            params.put("domain", domain == null ? "" : domain.toLowerCase());
            params.put("port", port == null ? "" : port.toLowerCase());
            params.put("deleted", false);
            cde = new CacheDriverExecutor(conn);
            return cde.first(sql, params);
        } finally {
            Db.closeQuietly(cde);
        }
    }

    /** All auth config rows (management API). */
    private static List<Map<String, Object>> getDomainAuths() throws Exception {
        CacheDriverExecutor cde = null;
        String sql = "SELECT * FROM domain_auth WHERE deleted = :deleted ORDER BY created_at ASC";
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
    
    /** Management API: lists every auth config. */
    public static Map<String, Object> queryDomainAuths() throws Exception {
        Map<String,Object> resMap = new HashMap<String,Object>();
        String msg = "";
        List<Map<String, Object>> list = getDomainAuths();
        int size = list.size();
        if(size <= 0){
            System.out.println();
            resMap.put("code", 0);
            resMap.put("msg", "No domain authentications found.");
            return resMap;
        }
        for(int i = 0;i < size;i++){
            Map item = list.get(i);
            msg += String.format("%s) %s:%s = %s",i + 1,item.get("domain") , item.get("port"), item.get("auth_enable")) + System.lineSeparator();
        }
        resMap.put("code", 1);
        resMap.put("msg", msg);
        return resMap;
    }    

    /** Management API: reads one auth config (cached). */
    public static Map<String, Object> queryDomainAuth(String domain, String port) {
        Map<String,Object> resMap = new HashMap<String,Object>();
        String msg = "";
        try{
            Map<String,Object> result = getDomainAuth(domain,port);
            if (result == null) {
                resMap.put("code",0);
                resMap.put("msg",String.format("The %s:%s does not exist.", domain, port));
            }else {
                msg += String.format("Id: %s",result.get("id")) + System.lineSeparator();
                msg += String.format("Domain: %s",result.get("domain")) + System.lineSeparator();
                msg += String.format("Port: %s",result.get("port")) + System.lineSeparator();
                msg += String.format("AuthEnable: %s",result.get("auth_enable")) + System.lineSeparator();
                msg += String.format("EncryptMode: %s",result.get("encrypt_mode")) + System.lineSeparator();
                msg += String.format("CreatedAt: %s",result.get("created_at")) + System.lineSeparator();
                msg += String.format("UpdatedAt: %s",result.get("updated_at")) +  System.lineSeparator();
                resMap.put("code",1);
                resMap.put("msg",msg);
            } 
            return resMap;
        }catch(Exception e){
            logError(resMap, e);
            resMap.put("msg",String.format("Failed to query DomainAuth %s:%s.", domain, port));
            return resMap;
        }
    }    

    /**
     * Management API: sets the lock policy (max failed attempts + lock window
     * in minutes) for a domain:port — applies to new user rows.
     */
    public static Map<String, Object> setAuthLockPolicy(String domain, String port, int maxAttempts, int lockMinutes) {
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
        if (maxAttempts <= 0) {
            resMap.put("code", 0);
            resMap.put("msg", "The param 'maxAttempts' must be a positive integer.");
            return resMap;
        }
        if (lockMinutes <= 0) {
            resMap.put("code", 0);
            resMap.put("msg", "The param 'lockMinutes' must be a positive integer.");
            return resMap;
        }
        domain = domain.toLowerCase();
        port = port.toLowerCase();
        CacheDriverExecutor cde = null;
        try {
            Map<String, Object> domainAuth = getDomainAuth(domain, port);
            if (domainAuth == null) {
                resMap.put("code", 0);
                resMap.put("msg", String.format("DomainAuth %s:%s does not exist.", domain, port));
                return resMap;
            }
            String sql = "UPDATE domain_auth SET max_attempts = :max_attempts, lock_minutes = :lock_minutes, updated_at = :updated_at WHERE id = :id";
            Connection conn = Db.getConnection();
            Map<String, Object> params = new HashMap<String, Object>();
            params.put("id", domainAuth.get("id"));
            params.put("max_attempts", maxAttempts);
            params.put("lock_minutes", lockMinutes);
            params.put("updated_at", new Clock().getSqlTimestamp());
            cde = new CacheDriverExecutor(conn);
            cde.execute(sql, params);
            resMap.put("code", 1);
            resMap.put("msg", String.format("Auth lock policy for %s:%s updated (maxAttempts=%d, lockMinutes=%d).",
                    domain, port, maxAttempts, lockMinutes));
            return resMap;
        } catch (Exception e) {
            logError(resMap, e);
            resMap.put("msg", String.format("Failed to update auth lock policy for %s:%s.", domain, port));
            return resMap;
        } finally {
            Db.closeQuietly(cde);
        }
    }

    /** Management API: manually unlocks a locked user (resets attempt counter). */
    public static Map<String, Object> unlockAuthUser(String domain, String port, String username) {
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
        if (CommonTools.isBlank(username)) {
            resMap.put("code", 0);
            resMap.put("msg", "The param 'username' is required.");
            return resMap;
        }
        domain = domain.toLowerCase();
        port = port.toLowerCase();
        username = username.toLowerCase();
        CacheDriverExecutor cde = null;
        try {
            Map<String, Object> domainAuth = getDomainAuth(domain, port);
            if (domainAuth == null) {
                resMap.put("code", 0);
                resMap.put("msg", String.format("DomainAuth %s:%s does not exist.", domain, port));
                return resMap;
            }
            Map<String, Object> user = getDomainAuthUser(domain, port, username);
            if (user == null) {
                resMap.put("code", 0);
                resMap.put("msg", String.format("DomainAuthUser %s:%s.%s does not exist.", domain, port, username));
                return resMap;
            }
            String sql = "UPDATE domain_auth_user SET failed_attempts = 0, locked_until = NULL, updated_at = :updated_at WHERE id = :id";
            Connection conn = Db.getConnection();
            Map<String, Object> params = new HashMap<String, Object>();
            params.put("id", user.get("id"));
            params.put("updated_at", new Clock().getSqlTimestamp());
            cde = new CacheDriverExecutor(conn);
            cde.execute(sql, params);
            // D1 fix: Cache.set(key, null, ...) is a no-op; use Cache.remove so
            // a freshly unlocked account is re-checked against the DB.
            String cacheKey = String.format("%s:%s.%s", domain, port, username);
            Cache.remove(cacheKey);
            resMap.put("code", 1);
            resMap.put("msg", String.format("DomainAuthUser %s:%s.%s unlocked.", domain, port, username));
            return resMap;
        } catch (Exception e) {
            logError(resMap, e);
            resMap.put("msg", String.format("Failed to unlock DomainAuthUser %s:%s.%s.", domain, port, username));
            return resMap;
        } finally {
            Db.closeQuietly(cde);
        }
    }

    /**
     * Reads a boolean column from a result row, tolerant of:
     * <ul>
     *   <li>the column being SQL NULL (schema declares {@code auth_enable BOOLEAN}
     *       without a DEFAULT, so legacy rows may carry NULL);</li>
     *   <li>column-label case differences (Core's {@code COLUMN_NAME_CASE_MODE}
     *       can be set to UPPER/RAW at runtime, not just the default LOWER);</li>
     *   <li>value being {@link Boolean}, {@link Number} (1/0) or a String
     *       ("true"/"false"/"1"/"0").</li>
     * </ul>
     * Without this, {@code (Boolean) row.get("auth_enable")} auto-unboxes a
     * null and throws NPE inside the request filter path.
     *
     * @param row       result row (may be null)
     * @param name      column name (lower-case, as used by the default LOWER mode)
     * @param fallback  value returned when the column is absent or NULL
     * @return the parsed boolean value, or {@code fallback} when unknown
     */
    private static boolean getBooleanCol(Map<String, Object> row, String name, boolean fallback) {
        if (row == null) return fallback;
        Object v = row.get(name);
        if (v == null) {
            // Case-insensitive fallback (mode=UPPER / RAW).
            for (Map.Entry<String, Object> e : row.entrySet()) {
                if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)) {
                    v = e.getValue();
                    break;
                }
            }
        }
        if (v == null) return fallback;
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof Number) return ((Number) v).intValue() != 0;
        String s2 = String.valueOf(v).trim();
        if ("1".equals(s2) || "true".equalsIgnoreCase(s2) || "yes".equalsIgnoreCase(s2)) return true;
        if ("0".equals(s2) || "false".equalsIgnoreCase(s2) || "no".equalsIgnoreCase(s2)) return false;
        return fallback;
    }
    /** Whether auth is enabled for the domain:port (fallback to {@code 0:0}). */
    private static boolean checkDomainAuthEnable(String domain, String port) throws Exception {
        CacheDriverExecutor cde = null;
        String sql = "SELECT * FROM domain_auth WHERE domain = :domain AND port = :port AND deleted = :deleted";
        try {
            Connection conn = Db.getConnection();
            Map<String, Object> params = new HashMap<String, Object>();
            // C7 fix: same case-insensitive lookup as getDomainAuth.
            params.put("domain", domain == null ? "" : domain.toLowerCase());
            params.put("port", port == null ? "" : port.toLowerCase());
            params.put("deleted", false);
            cde = new CacheDriverExecutor(conn);
            Map<String, Object> result = cde.first(sql, params);
            if (result == null)
                return false;

            // B20 fix: a legacy row with auth_enable = SQL NULL used to throw
            // NPE here (auto-unboxing a null Boolean). Treat NULL/unknown as
            // "auth disabled" rather than crashing the request path.
            return getBooleanCol(result, "auth_enable", false);
        } finally {
            Db.closeQuietly(cde);
        }
    }

    /** Sends an HTTP error without leaking stack traces. */
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
 	/** Applies the {@code SecurityFilter} configuration block at startup. */
 	public static void setParams(final Map params) {
		config.putAllAndReturnSelf(params);
	}   

    /** Writes {@code code=1 + msg} into a result map on a management-API error. */
    private static void logError(Map<String, Object> resMap, Exception e) {
        LOG.error(e.getMessage(), e);
        resMap.put("code", -1);
    }

}
