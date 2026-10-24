package com.killcoding.jsplus.tomcat;

import java.io.IOException;
import java.sql.Connection;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
                        String userAndPassBase64 = authorization.replaceFirst("Basic[^\\S]+", "");
                        String userAndPass = new String(Base64Tools.decode(userAndPassBase64));
                        String[] up = userAndPass.split(":", 2);
                        String user = up[0];
                        String pass = up[1];
                        allowed = checkUserFromCache(request,domain, port, user, pass);

                        if(!allowed) allowed = checkUserFromCache(request,"0", "0", user, pass);
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
            userAuthed = checkUser( domain, port, username, pass);
            Integer cacheSeconds = config.getInteger("SecurityFilter.cacheSeconds", 60);
            Cache.set(cacheKey,userAuthed,cacheSeconds);
            String logType = userAuthed ? "Authorized" : "Unauthorized";
            String paramsMsg = String.format("username=%s",username);
            ServerLog.writeWebLog(domain, port, request.getRequestURI(), logType,
            System.currentTimeMillis(),
            request.getQueryString(), ServerLog.getHeaders(request),paramsMsg,request.getRemoteAddr());
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
            String encryptKey = (String)domainAuth.get("encrypt_key");
            String encryptPassBase64 = (String)user.get("encrypt_pass");
            byte[] encryptPassBytes = Base64Tools.decodeLines(encryptPassBase64);
            String decryptPass = new String(CipherTools.AESDecrypt(encryptKey,encryptPassBytes),BaseFile.CHARSET);
            return decryptPass.equals(pass);
        } catch (Exception e) {
            LOG.error(e.getMessage(), e);
        }
        return false;
    }

    private synchronized boolean isAuthEnable(String domain, String port) throws Exception {
        String domainPort = String.format("%s:%s", domain, port);
        String domainPortKey = String.format("ScurityFilter.%s.authEnable", domainPort);
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
        String sql = "UPDATE domain_auth_user SET encrypt_pass = :encrypt_pass,updated_at = :updated_at WHERE id = :id";
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

    public static Map<String, Object> createDomainAuthUser(String domain, String port, String username,String plaintextPass) {
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

    public static Map<String,Object> createDomainAuth(String domain, String port, Boolean authEnable) {
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
            params.put("domain", domain);
            params.put("port", port);
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

    private static boolean checkDomainAuthEnable(String domain, String port) throws Exception {
        CacheDriverExecutor cde = null;
        String sql = "SELECT * FROM domain_auth WHERE domain = :domain AND port = :port AND deleted = :deleted";
        try {
            Connection conn = Db.getConnection();
            Map<String, Object> params = new HashMap<String, Object>();
            params.put("domain", domain);
            params.put("port", port);
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
                String errorRedirectUrl = String.format(
                        config.getString("errorRedirectUrl", "http://errorRedirectUrl/%s.html"), code + "");
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
