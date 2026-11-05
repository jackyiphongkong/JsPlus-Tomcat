# JsPlus-Tomcat Operations Manual

## Table of Contents
1. [Project Overview](#1-project-overview)
2. [System Requirements](#2-system-requirements)
3. [Build & Startup](#3-build--startup)
4. [Configuration](#4-configuration)
5. [Feature Modules](#5-feature-modules)
6. [JS Console API (TomcatEmbed.js)](#6-js-console-api-tomcatembedjs)
7. [Testing](#7-testing)
8. [Release Process](#8-release-process)
9. [Troubleshooting & Logs](#9-troubleshooting--logs)

---

## 1. Project Overview

JsPlus-Tomcat is a lightweight embedded Tomcat reverse-proxy and security-defense server built on the JsPlus (GraalVM JS) platform. Key features:

- Embedded Tomcat server (HTTP / HTTPS / HTTP2)
- IP Filter (allow/deny rules)
- IP Attack Defense (auto-ban malicious IPs)
- Security Filter (request security checks)
- Domain Proxy (reverse proxy / internal forward)
- Custom Error Pages (CustomErrorReportValve)
- H2 embedded database
- Script Console (JS console)

---

## 2. System Requirements

| Item | Requirement |
|---|---|
| Java | Oracle GraalVM 25+ |
| Ant | 1.10+ |
| OS | Windows / Linux / macOS |
| Memory | 512MB minimum |
| Disk Space | 500MB |

---

## 3. Build & Startup

### 3.1 Build

```bash
# Windows
ant.bat

# Linux / macOS
ant.sh
```

After build, the jar is output to:
```
LibHome/jsplus/default/jsplus-tomcat-<version>.jar
```

### 3.2 Start

```bash
# Windows
JsPlus/bin/StartTomcat.bat

# Linux / macOS
JsPlus/bin/StartTomcat.sh
```

### 3.3 Stop

```bash
# Windows
JsPlus/bin/StopAll.bat

# Linux / macOS
JsPlus/bin/StopAll.sh
```

> **Note**: After forcibly killing the java process, you must start the H2 ghost first, then start Tomcat. Otherwise you'll get H2 connection refused.

---

## 4. Configuration

Config file: `JsPlus/config/Tomcat.properties`

### 4.1 Basic Connectors

```properties
port=8080
sslPort=8443
```

### 4.2 SSL

```properties
SSL.enable=true
SSL.keyRoot=${JS_PLUS_PATH}/key/
SSL.keystoreFile[0]=default_keystore.jks
SSL.keystorePass[0]=changeit
SSL.keyAlias[0]=client
SSL.truststoreFile=default_truststore.jks
SSL.truststorePass=changeit
SSL.truststoreType=JKS
```

### 4.3 HTTP/2

```properties
Http2.enable=false
```

### 4.4 IP Filter

```properties
IpFilter.enable=true
IpFilter.cacheSeconds=0
```

### 4.5 IP Attack Defense

```properties
IpAttackDefense.enable=true
IpAttackDefense.logTimer=5000
IpAttackDefense.cacheSeconds=0
IpAttackDefense.logRetention=1h
IpAttackDefense.logPhysicalDelete=2h
```

### 4.6 Security Filter

```properties
SecurityFilter.cacheSeconds=0
```

### 4.7 Domain Proxy

```properties
DomainProxy.enable=true
DomainProxy.proxyConnectTimeout=60s
DomainProxy.proxyRequestTimeout=30s
```

### 4.8 Script Console

```properties
TomcatScriptConsole.poolSize=100
TomcatScriptConsole.cacheSeconds=2
TomcatScriptConsole.freedDelayMs=10
TomcatScriptConsole.engineName=graalvm.js
```

### 4.9 Webapp

```properties
Webapp.appBase[0]=${JS_PLUS_PATH}/../Webapps/default/
Webapp.appBase[1]=${JS_PLUS_PATH}/../Webapps/default/
Webapp.appBase[2]=${JS_PLUS_PATH}/../Webapps/error/
```

### 4.10 Error Pages

```properties
errorRedirectEnable=true
errorRedirectUrl=https://testerror.com:8443/error/{ErrorCode}.html?RequestUrl={RequestUrl}
```

---

## 5. Feature Modules

### 5.1 IpFilter

IP filter with allow/deny rules. Rules are stored in the H2 database.

```javascript
// Example: list rules
tomcat.queryDomainIpRules();

// Example: add a rule
tomcat.createDomainIpRule('example.com', '443', 'ALLOW', '192.168.1.1');
```

### 5.2 IpAttackDefense

Automatically detects and bans malicious IPs. Configurable log retention and physical deletion windows.

### 5.3 SecurityFilter

Request security checks, filters out malicious requests.

### 5.4 DomainProxy

Reverse proxy / internal forward. Two modes:

- **proxy**: external reverse proxy, forwards to a target URL
- **internal**: internal forward, forwards to a local webapp

```javascript
// Example: set a domain mapping
tomcat.setDomainMapping('example.com', '443', {
  target: 'https://127.0.0.1:8443/default',
  scriptEnable: false
});
```

### 5.5 CustomErrorReportValve

Custom error page that directly outputs HTML (white background, centered, system font, Instagram-blue retry link). No dependency on web.xml or the /error/ webapp.

---

## 6. JS Console API (TomcatEmbed.js)

Invoked via the GraalVM JS console (Script Console). All methods are mounted on the global `tomcat` object.

### 6.1 Server Control

| Method | Description |
|---|---|
| `tomcat.start()` | Start the Tomcat server |
| `tomcat.stop()` | Stop the Tomcat server |
| `tomcat.restart()` | Restart (stop + start) |
| `tomcat.getVersion()` | Get version number |
| `tomcat.getTomcatHome()` | Get Tomcat home path |
| `tomcat.getTomcat()` | Get the underlying Tomcat object |
| `tomcat.getMemoryInfo()` | Get memory info |
| `tomcat.loadParams(params)` | Load a params Map |
| `tomcat.loadTomcatProps(params)` | Load params from Tomcat.properties |
| `tomcat.reloadSslHostConfig(hostName)` | Reload SSL host config (all if no arg) |

### 6.2 Domain Auth (SecurityFilter)

| Method | Description |
|---|---|
| `tomcat.createDomainAuth(domain, port, authEnable)` | Create a domain auth setting |
| `tomcat.updateDomainAuth(domain, port, authEnable)` | Update a domain auth setting |
| `tomcat.removeDomainAuth(domain, port)` | Remove a domain auth setting |
| `tomcat.queryDomainAuth(domain, port)` | Query a single domain auth |
| `tomcat.queryDomainAuths()` | Query all domain auths |
| `tomcat.createDomainAuthUser(domain, port, username, plaintextPass)` | Create an auth user |
| `tomcat.updateDomainAuthUser(domain, port, username, plaintextPass)` | Update an auth user's password |
| `tomcat.removeDomainAuthUser(domain, port, username)` | Remove an auth user |
| `tomcat.queryDomainAuthUser(domain, port, username)` | Query a single auth user |
| `tomcat.queryDomainAuthUsers(domain, port)` | Query all users for a domain |
| `tomcat.setAuthLockPolicy(domain, port, maxAttempts, lockMinutes)` | Set account-lock policy |
| `tomcat.unlockAuthUser(domain, port, username)` | Unlock a locked user |

### 6.3 Domain Mapping (DomainProxy)

| Method | Description |
|---|---|
| `tomcat.setDomainMapping(domain, port, params)` | Set catch-all mapping (uri "") |
| `tomcat.setDomainMapping(domain, port, uri, params)` | Set uri-prefix-specific mapping |
| `tomcat.removeDomainMapping(domain, port)` | Remove catch-all mapping |
| `tomcat.removeDomainMapping(domain, port, uri)` | Remove uri-specific mapping |
| `tomcat.queryDomainMapping(domain, port)` | Query catch-all mapping |
| `tomcat.queryDomainMapping(domain, port, uri)` | Query uri-specific mapping |
| `tomcat.queryDomainMappings()` | Query all mappings |

### 6.4 Domain SSL Host

| Method | Description |
|---|---|
| `tomcat.createDomainSslHost(hostname, keystoreFile, keystorePass, keyAlias)` | Persist an SSL host setting to DB |
| `tomcat.updateDomainSslHost(hostname, keystoreFile, keystorePass, keyAlias)` | Update an SSL host setting |
| `tomcat.removeDomainSslHost(hostname)` | Remove an SSL host setting |
| `tomcat.queryDomainSslHost(hostname)` | Query a single SSL host |
| `tomcat.queryDomainSslHosts()` | Query all SSL hosts |
| `tomcat.addSslHost(hostname, keystoreFile, keystorePass, keyAlias)` | Dynamically add an SSL host (runtime) |
| `tomcat.loadDomainSslHosts()` | Load all SSL hosts from DB |

### 6.5 IpFilter

| Method | Description |
|---|---|
| `tomcat.createDomainIpRule(domain, port, ruleType, ipPattern)` | Create catch-all IP rule (ALLOW/DENY) |
| `tomcat.createDomainIpRule(domain, port, uri, ruleType, ipPattern)` | Create uri-scoped IP rule |
| `tomcat.updateDomainIpRule(domain, port, ruleType, oldIpPattern, newIpPattern)` | Update catch-all IP rule |
| `tomcat.updateDomainIpRule(domain, port, uri, ruleType, oldIpPattern, newIpPattern)` | Update uri-scoped IP rule |
| `tomcat.removeDomainIpRule(domain, port, ruleType, ipPattern)` | Remove catch-all IP rule |
| `tomcat.removeDomainIpRule(domain, port, uri, ruleType, ipPattern)` | Remove uri-scoped IP rule |
| `tomcat.queryDomainIpRule(domain, port, ruleType, ipPattern)` | Query a single IP rule |
| `tomcat.queryDomainIpRules()` | Query all IP rules |
| `tomcat.loadDomainIpRules()` | Load all IP rules from DB |

### 6.6 IpAttackDefense

| Method | Description |
|---|---|
| `tomcat.createDefenseRule(ruleName, domain, port, uriPattern, windowSeconds, threshold, banSeconds, enabled)` | Create a defense rule |
| `tomcat.updateDefenseRule(ruleId, ruleName, domain, port, uriPattern, windowSeconds, threshold, banSeconds, enabled)` | Update a defense rule |
| `tomcat.removeDefenseRule(ruleId)` | Remove a defense rule |
| `tomcat.queryDefenseRules()` | Query all defense rules |
| `tomcat.queryIpBans()` | Query all banned IPs |
| `tomcat.removeIpBan(domain, port, ip)` | Unban an IP |
| `tomcat.queryIpRequestLogs()` | Query IP request logs |
| `tomcat.clearIpRequestLogs()` | Clear IP request logs |

---

## 7. Testing

### 7.1 Full Test Suite

```bash
# Windows
test/TestAll.bat

# Linux / macOS
test/TestAll.sh
```

### 7.2 Test Flow

1. StopAll (kill all java processes)
2. Clean `JsPlus/.tmp/*` and `logs/*`
3. Build via ant.bat
4. Run module-specific tests
5. Run TestAll end-to-end

### 7.3 Test Report

After tests complete, the report is output to:
```
docs/latest/report/TestAll-Report.html
```

---

## 8. Release Process

### 8.1 Build

```bash
ant.bat
```

### 8.2 Release

```bash
release.bat
```

Release scans all sibling project folders and copies the new jar into `LibHome/jsplus/default/` of every project that already holds a `jsplus-tomcat-*.jar`.

Old jars are renamed to `*.jar.bak` (not deleted).

---

## 9. Troubleshooting & Logs

### 9.1 Log Location

```
JsPlus/logs/
```

### 9.2 Common Issues

#### H2 connection refused

**Cause**: After forcibly killing the java process, the H2 ghost process is also killed.

**Fix**: Start `StartH2.bat` first, then `StartTomcat.bat`.

#### 404 Not Found

**Cause**: Domain mapping points to a non-existent host or path.

**Fix**: Check `tomcat.queryDomainMapping(domain, port)` results.

#### Error page not in custom style

**Cause**: CustomErrorReportValve is not registered correctly.

**Fix**: Confirm `TomcatServer.java` calls `setErrorReportValveClass(CustomErrorReportValve.class.getName())`.

---

## Appendix: Version History

| Version | Date | Notes |
|---|---|---|
| 8.0.103 | 2026-11-04 | CustomErrorReportValve + release.bat alignment |
