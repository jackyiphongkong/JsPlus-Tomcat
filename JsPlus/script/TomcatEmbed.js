var Runnable = Java.type("java.lang.Runnable");
var Thread = Java.type("java.lang.Thread");
var HashMap = Java.type("java.util.HashMap");
var TomcatServer = Java.type("com.killcoding.jsplus.tomcat.TomcatServer");
var Thread = Java.type("java.lang.Thread");
var Logger = Java.type("com.killcoding.log.Logger");
var LoggerFactory = Java.type("com.killcoding.log.LoggerFactory");
var LogRecord = Java.type("com.killcoding.log.LogRecord");
var DiskFile = Java.type("com.killcoding.file.DiskFile");
var Exception = Java.type("java.lang.Exception");
var SimpleDateFormat = Java.type("java.text.SimpleDateFormat");
var DriverDataSource = Java.type("com.killcoding.datasource.DriverDataSource");
var System = Java.type("java.lang.System");
var ScriptConsole = Java.type("com.killcoding.jsplus.ScriptConsole");
var String = Java.type("java.lang.String");
var Thread = Java.type("java.lang.Thread");
var SecurityFilter = Java.type("com.killcoding.jsplus.tomcat.SecurityFilter");
var IpFilter = Java.type("com.killcoding.jsplus.tomcat.IpFilter");
var IpAttackDefense = Java.type("com.killcoding.jsplus.tomcat.IpAttackDefense");
var Json = Java.type("com.killcoding.ext.Json");
var DomainProxy = Java.type("com.killcoding.jsplus.tomcat.DomainProxy");

function TomcatEmbed(){
    var the = this;
    the.server = null;
    the.status = 'Stopped';
    the.ca = Console.getGlobalCacheArray(0,5000);
    var CallbackRun = Java.extend(Runnable,{
    	run: function(){
    		Thread.currentThread().setName("TomcatServer");
    		var params = the.ca.getObject() || null;
            the.server = new TomcatServer();
            the.server.start(params);
    	}
    });
    Console.callback(new CallbackRun());
}

TomcatEmbed.prototype.createDomainAuth = function(domain, port, authEnable){
    var the = this;
    var resMap = SecurityFilter.createDomainAuth(domain, port, authEnable);
    System.out.println(resMap.msg);
    return resMap.msg;
}

TomcatEmbed.prototype.updateDomainAuth = function(domain, port, authEnable){
    var the = this;
    var resMap = SecurityFilter.updateDomainAuth(domain, port, authEnable);
    System.out.println(resMap.msg);
    return resMap.msg;
}

TomcatEmbed.prototype.removeDomainAuth = function(domain, port){
    var the = this;
    var resMap = SecurityFilter.removeDomainAuth(domain, port);
    System.out.println(resMap.msg);
    return resMap.msg;
}

TomcatEmbed.prototype.queryDomainAuth = function(domain, port){
    var the = this;
    var resMap = SecurityFilter.queryDomainAuth(domain, port);
    System.out.println(resMap.msg);
    return resMap.msg;
}

TomcatEmbed.prototype.queryDomainAuths = function(){
    var the = this;
    var resMap = SecurityFilter.queryDomainAuths();
    System.out.println(resMap.msg);
    return resMap.msg;
}

TomcatEmbed.prototype.createDomainAuthUser = function(domain, port, username, plaintextPass){
    var the = this;
    var resMap = SecurityFilter.createDomainAuthUser(domain, port, username, plaintextPass);
    System.out.println(resMap.msg);
    return resMap.msg;
}

TomcatEmbed.prototype.updateDomainAuthUser = function(domain, port, username, plaintextPass){
    var the = this;
    var resMap = SecurityFilter.updateDomainAuthUser(domain, port, username, plaintextPass);
    System.out.println(resMap.msg);
    return resMap.msg;
}

TomcatEmbed.prototype.queryDomainAuthUser = function(domain, port, username){
    var the = this;
    var resMap = SecurityFilter.queryDomainAuthUser(domain, port, username);
    System.out.println(resMap.msg);
    return resMap.msg;
}

TomcatEmbed.prototype.queryDomainAuthUsers = function(domain, port){
    var the = this;
    var resMap = SecurityFilter.queryDomainAuthUsers(domain, port);
    System.out.println(resMap.msg);
    return resMap.msg;
}

TomcatEmbed.prototype.removeDomainAuthUser = function(domain, port, username){
    var the = this;
    var resMap = SecurityFilter.removeDomainAuthUser(domain, port, username);
    System.out.println(resMap.msg);
    return resMap.msg;
}

TomcatEmbed.prototype.setAuthLockPolicy = function(domain, port, maxAttempts, lockMinutes){
    var the = this;
    var resMap = SecurityFilter.setAuthLockPolicy(domain, port, maxAttempts, lockMinutes);
    System.out.println(resMap.msg);
    return resMap.msg;
}

TomcatEmbed.prototype.unlockAuthUser = function(domain, port, username){
    var the = this;
    var resMap = SecurityFilter.unlockAuthUser(domain, port, username);
    System.out.println(resMap.msg);
    return resMap.msg;
}

// setDomainMapping(domain, port, domainParams)          -> catch-all mapping (uri "")
// setDomainMapping(domain, port, uri, domainParams)     -> uri-prefix-specific mapping
TomcatEmbed.prototype.setDomainMapping = function(domain, port, uriOrParams, domainParams){
    var the = this;
    var resMap;
    if (domainParams !== undefined && domainParams !== null) {
        resMap = DomainProxy.setDomainMapping(domain, port, uriOrParams, domainParams);
    } else {
        resMap = DomainProxy.setDomainMapping(domain, port, uriOrParams);
    }
    System.out.println(resMap.msg);
    return resMap.msg;
}

// removeDomainMapping(domain, port)      -> removes the catch-all mapping
// removeDomainMapping(domain, port, uri) -> removes the uri-specific mapping
TomcatEmbed.prototype.removeDomainMapping = function(domain, port, uri){
    var the = this;
    var resMap;
    if (uri !== undefined && uri !== null && ("" + uri).length > 0) {
        resMap = DomainProxy.removeDomainMapping(domain, port, uri);
    } else {
        resMap = DomainProxy.removeDomainMapping(domain, port);
    }
    System.out.println(resMap.msg);
    return resMap.msg;
}

// queryDomainMapping(domain, port)      -> queries the catch-all mapping
// queryDomainMapping(domain, port, uri) -> queries the uri-specific mapping
TomcatEmbed.prototype.queryDomainMapping = function(domain, port, uri){
    var the = this;
    var resMap;
    if (uri !== undefined && uri !== null && ("" + uri).length > 0) {
        resMap = DomainProxy.queryDomainMapping(domain, port, uri);
    } else {
        resMap = DomainProxy.queryDomainMapping(domain, port);
    }
    System.out.println(resMap.msg);
    return resMap.msg;
}

TomcatEmbed.prototype.queryDomainMappings = function(){
    var the = this;
    var resMap = DomainProxy.queryDomainMappings();
    System.out.println(resMap.msg);
    return resMap.msg;
}

TomcatEmbed.prototype.createDomainSslHost = function(hostname, keystoreFile, keystorePass, keyAlias){
    var the = this;
    var resMap = TomcatServer.createDomainSslHost(hostname, keystoreFile, keystorePass, keyAlias);
    System.out.println(resMap.msg);
    return resMap.msg;
}

TomcatEmbed.prototype.updateDomainSslHost = function(hostname, keystoreFile, keystorePass, keyAlias){
    var the = this;
    var resMap = TomcatServer.updateDomainSslHost(hostname, keystoreFile, keystorePass, keyAlias);
    System.out.println(resMap.msg);
    return resMap.msg;
}

TomcatEmbed.prototype.removeDomainSslHost = function(hostname){
    var the = this;
    var resMap = TomcatServer.removeDomainSslHost(hostname);
    System.out.println(resMap.msg);
    return resMap.msg;
}

TomcatEmbed.prototype.queryDomainSslHost = function(hostname){
    var the = this;
    var resMap = TomcatServer.queryDomainSslHost(hostname);
    System.out.println(resMap.msg);
    return resMap.msg;
}

TomcatEmbed.prototype.queryDomainSslHosts = function(){
    var the = this;
    var resMap = TomcatServer.queryDomainSslHosts();
    System.out.println(resMap.msg);
    return resMap.msg;
}

TomcatEmbed.prototype.addSslHost = function(hostname, keystoreFile, keystorePass, keyAlias){
    var the = this;
    if (the.server == null) {
        print("ERROR: Tomcat server is not running.");
        return "ERROR: Tomcat server is not running.\n";
    }
    var resMap = the.server.addSslHost(hostname, keystoreFile, keystorePass, keyAlias);
    System.out.println(resMap.msg);
    return resMap.msg;
}

TomcatEmbed.prototype.loadDomainSslHosts = function(){
    var the = this;
    if (the.server == null) {
        print("ERROR: Tomcat server is not running.");
        return "ERROR: Tomcat server is not running.\n";
    }
    var resMap = the.server.loadDomainSslHosts();
    System.out.println(resMap.msg);
    return resMap.msg;
}

// ---------------------------------------------------------------------------
// TomcatServer bootstrap / introspection API
// ---------------------------------------------------------------------------

TomcatEmbed.prototype.getVersion = function(){
    return TomcatServer.VERSION;
}

TomcatEmbed.prototype.getTomcatHome = function(){
    var the = this;
    if (the.server == null) {
        print("ERROR: Tomcat server is not running.");
        return "ERROR: Tomcat server is not running.\n";
    }
    return the.server.getTomcatHome();
}

TomcatEmbed.prototype.getTomcat = function(){
    var the = this;
    if (the.server == null) {
        print("ERROR: Tomcat server is not running.");
        return null;
    }
    return the.server.getTomcat();
}

TomcatEmbed.prototype.getMemoryInfo = function(){
    return TomcatServer.getMemoryInfo();
}

TomcatEmbed.prototype.loadParams = function(params){
    var the = this;
    if (the.server == null) {
        print("ERROR: Tomcat server is not running.");
        return "ERROR: Tomcat server is not running.\n";
    }
    the.server.loadParams(params);
    return "loadParams applied.\n";
}

TomcatEmbed.prototype.loadTomcatProps = function(params){
    TomcatServer.loadTomcatProps(params);
    return "loadTomcatProps applied.\n";
}

TomcatEmbed.prototype.reloadSslHostConfig = function(hostName){
    var the = this;
    if (the.server == null) {
        print("ERROR: Tomcat server is not running.");
        return "ERROR: Tomcat server is not running.\n";
    }
    if (hostName === undefined || hostName === null || ("" + hostName).length === 0) {
        the.server.reloadSslHostConfig();
    } else {
        the.server.reloadSslHostConfig("" + hostName);
    }
    return "SslHostConfig reloaded.\n";
}

// createDomainIpRule(domain, port, ruleType, ipPattern)         -> catch-all rule (uri "")
// createDomainIpRule(domain, port, uri, ruleType, ipPattern)    -> uri-scoped rule
TomcatEmbed.prototype.createDomainIpRule = function(domain, port, uriOrType, typeOrPattern, patternOrUndefined){
    var the = this;
    var resMap;
    var t3 = ("" + uriOrType).toUpperCase();
    if (t3 === "ALLOW" || t3 === "DENY") {
        resMap = IpFilter.createDomainIpRule(domain, port, uriOrType, typeOrPattern);
    } else {
        resMap = IpFilter.createDomainIpRule(domain, port, uriOrType, typeOrPattern, patternOrUndefined);
    }
    System.out.println(resMap.msg);
    return resMap.msg;
}

// updateDomainIpRule(domain, port, ruleType, oldIpPattern, newIpPattern)        -> catch-all rule
// updateDomainIpRule(domain, port, uri, ruleType, oldIpPattern, newIpPattern)   -> uri-scoped rule
TomcatEmbed.prototype.updateDomainIpRule = function(domain, port, uriOrType, typeOrOld, oldOrNew, newOrUndefined){
    var the = this;
    var resMap;
    var t3 = ("" + uriOrType).toUpperCase();
    if (t3 === "ALLOW" || t3 === "DENY") {
        resMap = IpFilter.updateDomainIpRule(domain, port, uriOrType, typeOrOld, oldOrNew);
    } else {
        resMap = IpFilter.updateDomainIpRule(domain, port, uriOrType, typeOrOld, oldOrNew, newOrUndefined);
    }
    System.out.println(resMap.msg);
    return resMap.msg;
}

// removeDomainIpRule(domain, port, ruleType, ipPattern)       -> catch-all rule
// removeDomainIpRule(domain, port, uri, ruleType, ipPattern)  -> uri-scoped rule
TomcatEmbed.prototype.removeDomainIpRule = function(domain, port, uriOrType, typeOrPattern, patternOrUndefined){
    var the = this;
    var resMap;
    var t3 = ("" + uriOrType).toUpperCase();
    if (t3 === "ALLOW" || t3 === "DENY") {
        resMap = IpFilter.removeDomainIpRule(domain, port, uriOrType, typeOrPattern);
    } else {
        resMap = IpFilter.removeDomainIpRule(domain, port, uriOrType, typeOrPattern, patternOrUndefined);
    }
    System.out.println(resMap.msg);
    return resMap.msg;
}

// queryDomainIpRule(domain, port, ruleType, ipPattern)       -> catch-all rule
// queryDomainIpRule(domain, port, uri, ruleType, ipPattern)  -> uri-scoped rule
TomcatEmbed.prototype.queryDomainIpRule = function(domain, port, uriOrType, typeOrPattern, patternOrUndefined){
    var the = this;
    var resMap;
    var t3 = ("" + uriOrType).toUpperCase();
    if (t3 === "ALLOW" || t3 === "DENY") {
        resMap = IpFilter.queryDomainIpRule(domain, port, uriOrType, typeOrPattern);
    } else {
        resMap = IpFilter.queryDomainIpRule(domain, port, uriOrType, typeOrPattern, patternOrUndefined);
    }
    System.out.println(resMap.msg);
    return resMap.msg;
}

TomcatEmbed.prototype.queryDomainIpRules = function(){
    var the = this;
    var resMap = IpFilter.queryDomainIpRules();
    System.out.println(resMap.msg);
    return resMap.msg;
}

TomcatEmbed.prototype.loadDomainIpRules = function(){
    var the = this;
    var resMap = IpFilter.loadDomainIpRules();
    System.out.println(resMap.msg);
    return resMap.msg;
}

TomcatEmbed.prototype.createDefenseRule = function(ruleName, domain, port, uriPattern, windowSeconds, threshold, banSeconds, enabled){
    var the = this;
    var resMap = IpAttackDefense.createDefenseRule(ruleName, domain, port, uriPattern, windowSeconds, threshold, banSeconds, enabled);
    System.out.println(resMap.msg);
    return resMap.msg;
}

TomcatEmbed.prototype.updateDefenseRule = function(ruleId, ruleName, domain, port, uriPattern, windowSeconds, threshold, banSeconds, enabled){
    var the = this;
    var resMap = IpAttackDefense.updateDefenseRule(ruleId, ruleName, domain, port, uriPattern, windowSeconds, threshold, banSeconds, enabled);
    System.out.println(resMap.msg);
    return resMap.msg;
}

TomcatEmbed.prototype.removeDefenseRule = function(ruleId){
    var the = this;
    var resMap = IpAttackDefense.removeDefenseRule(ruleId);
    System.out.println(resMap.msg);
    return resMap.msg;
}

TomcatEmbed.prototype.queryDefenseRules = function(){
    var the = this;
    var resMap = IpAttackDefense.queryDefenseRules();
    System.out.println(resMap.msg);
    return resMap.msg;
}

TomcatEmbed.prototype.queryIpBans = function(){
    var the = this;
    var resMap = IpAttackDefense.queryIpBans();
    System.out.println(resMap.msg);
    return resMap.msg;
}

TomcatEmbed.prototype.removeIpBan = function(domain, port, ip){
    var the = this;
    var resMap = IpAttackDefense.removeIpBan(domain, port, ip);
    System.out.println(resMap.msg);
    return resMap.msg;
}

TomcatEmbed.prototype.queryIpRequestLogs = function(){
    var the = this;
    var resMap = IpAttackDefense.queryIpRequestLogs();
    System.out.println(resMap.msg);
    return resMap.msg;
}

TomcatEmbed.prototype.clearIpRequestLogs = function(){
    var the = this;
    var resMap = IpAttackDefense.clearIpRequestLogs();
    System.out.println(resMap.msg);
    return resMap.msg;
}

TomcatEmbed.prototype.start = function(){
    var the = this;
    if(the.status === 'Stopped'){
        the.status = 'Started';
        var msg = "Starting...\n";
        print("Starting...");
        var params = new HashMap();
        TomcatServer.loadTomcatProps(params);
        the.ca.add(params); 
        msg += "Started.\n";
        print("Started");
        return msg;
    }
    print("ERROR: Tomcat server is running.");
    return "ERROR: Tomcat server is running.\n";    
}

TomcatEmbed.prototype.stop = function(){
    var the = this;
    if(the.status === 'Started'){
        the.status = 'Stopped';
        var msg = "";
        msg += "Stopping...\n";
        print("Stopping...");
        the.server.stop();
        msg += "Stopped.\n";
        print("Stopped.");   
        return msg;
    }
    print("ERROR: Tomcat server is not running.");
    return "ERROR: Tomcat server is not running.\n";    
}

TomcatEmbed.prototype.restart = function(){
    this.stop();
    this.start();
}

if(OriginConsole !== null){
    OriginConsole.getContext().put("tomcat",new TomcatEmbed());
}
