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

public class SecurityFilter implements Filter {

    public final static String FILTER_NAME = "TomcatServerSecurityFilter";

    private static final Logger LOG = LoggerFactory.getLogger(SecurityFilter.class);

    private static ConfigProperties config = new ConfigProperties();

    /** B4 fix: per-account locks so racing threads on the same cache miss can
     *  never double-increment the failed-attempt counter (which used to lock
     *  accounts early under concurrency). */
    private static final ConcurrentHashMap<String, Object> AUTH_CHECK_LOCKS = new ConcurrentHashMap<>();

    private static Object authCheckLock(String key) {
        return AUTH_CHECK_LOCKS.computeIfAbsent(key, k -> new Object());
    }

    public SecurityFilter() {
        super();
    }

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
                        Cache.set(cacheKey,userAuthed,cacheSeconds);
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

    private static int toInt(Object value) {
        if (value == null) return 0;
        if (value instanceof Number) return ((Number) value).intValue();
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (Exception e) {
            return 0;
        }
    }

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

    private synchronized boolean isAuthEnable(String domain, String port) throws Exception {
        String domainPort = String.format("%s:%s", domain, port);
        String domainPortKey = String.format("SecurityFilter.%s.authEnable", domainPort);
        Boolean authEnable = (Boolean) Cache.get(domainPortKey);

        if (authEnable == null) {
            authEnable = checkDomainAuthEnable(domain, port);
            Integer cacheSeconds = config.getInteger("SecurityFilter.cacheSeconds", 60);
            Cache.set(domainPortKey, authEnable, cacheSeconds);
        }
        return authEnable;
    }    

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
            Integer cacheSeconds = config.getInteger("SecurityFilter.cacheSeconds", 60);
            String cacheKey = String.format("%s:%s.%s",domain,port,username);
            Cache.set(cacheKey,null,cacheSeconds);
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
            Integer cacheSeconds = config.getInteger("SecurityFilter.cacheSeconds", 60);
            String cacheKey = String.format("%s:%s.%s",domain,port,username);
            Cache.set(cacheKey,null,cacheSeconds);
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
    public synchronized static Map<String,Object> createDomainAuth(String domain, String port, Boolean authEnable) {
        Map<String,Object> resMap = new HashMap<String,Object>();

        String sql = "INSERT INTO domain_auth (id,domain,port,auth_enable,encrypt_mode,encrypt_key,created_at,updated_at,deleted_at,deleted) VALUES (:id,:domain,:port,:auth_enable,:encrypt_mode,:encrypt_key,:created_at,:updated_at,:deleted_at,:deleted)";
        CacheDriverExecutor cde = null;
        try {
            domain = domain.toLowerCase();
            port = port.toLowerCase();      
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

    public static Map<String,Object> updateDomainAuth(String domain, String port, Boolean authEnable) {
        Map<String,Object> resMap = new HashMap<String,Object>();
        CacheDriverExecutor cde = null;
        String sql = "UPDATE domain_auth SET auth_enable = :auth_enable,updated_at = :updated_at WHERE domain = :domain AND port = :port AND deleted = :deleted";

        try {
            domain = domain.toLowerCase();
            port = port.toLowerCase();
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
     * Set the account-lockout policy for a domain:port Basic Auth setting.
     * maxAttempts = how many consecutive wrong passwords are allowed before
     * the account is locked; lockMinutes = how long the lock lasts before the
     * account auto-unlocks.
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

    /**
     * Manually unlock a domain auth user: clears failed_attempts and
     * locked_until immediately (no need to wait for the auto-unlock time).
     */
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
            Integer cacheSeconds = config.getInteger("SecurityFilter.cacheSeconds", 60);
            String cacheKey = String.format("%s:%s.%s", domain, port, username);
            Cache.set(cacheKey, null, cacheSeconds);
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

            return (Boolean) result.get("auth_enable");
        } finally {
            Db.closeQuietly(cde);
        }
    }

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
 	public static void setParams(final Map params) {
		config.putAllAndReturnSlef(params);
	}   

    private static void logError(Map<String, Object> resMap, Exception e) {
        LOG.error(e.getMessage(), e);
        resMap.put("code", -1);
    }

}
